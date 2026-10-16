package com.cabletv.player.config;

import android.os.SystemClock;
import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.util.TlsCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Finds out which of a channel's sources still answer, and how quickly.
 *
 * <p>Public playlists list every source they ever saw and mark none of them dead, so the first
 * entry is as likely to be a server that shut down two years ago as a working one. Learning that
 * by trying to play is what makes changing channel slow: a dead source does not fail fast, it
 * makes the player wait. Probing them up front turns an unknown into a known before anyone is
 * waiting for a picture.
 *
 * <p>The probes run a bounded number at a time and each is abandoned after {@value TIMEOUT_MS} ms,
 * so measuring a channel cannot itself become the stall it exists to prevent, and a slow source
 * cannot hold up the ones queued behind it.
 *
 * <p>What is measured is time to first response. It is a proxy, not a speed test: a source that
 * answers at all is worth more than one that answers a few milliseconds sooner, so the ranking
 * is about reachability first and latency second.
 */
public final class SourceSpeedTester {

    private static final String TAG = "SourceSpeedTester";

    /** How many sources are probed at once. */
    private static final int MAX_PARALLEL = 6;

    /** Overall budget for one source, including the queue behind it. */
    private static final int TIMEOUT_MS = 3_000;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int READ_TIMEOUT_MS = 2_500;

    /** Enough to prove bytes are flowing; a live stream is never fully read. */
    private static final int PROBE_BYTES = 16 * 1024;

    /** A source slower than this is recorded but ranks last: it worked, but barely. */
    private static final int UNUSABLE_MS = 2_800;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    /** Sources probed per channel when measuring a whole playlist. */
    private static final int MAX_SOURCES_PER_CHANNEL = 8;

    private static final Object sBulkLock = new Object();
    private static ExecutorService sBulkExecutor;

    private SourceSpeedTester() {
    }

    /**
     * Probes the given sources.
     *
     * @return latency in milliseconds for every source that answered, in time and with a status
     * that is not an error; sources that did not are simply absent
     */
    public static Map<String, Integer> measure(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return new HashMap<>();
        }
        List<String> targets = new ArrayList<>(urls);
        ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL);
        Map<String, Future<Integer>> pending = new LinkedHashMap<>();
        try {
            for (String url : targets) {
                pending.put(url, pool.submit(new Probe(url)));
            }
            Map<String, Integer> reachable = new HashMap<>();
            for (Map.Entry<String, Future<Integer>> entry : pending.entrySet()) {
                try {
                    // The budget is per source: a queue of six that all time out costs one
                    // timeout, not six.
                    int ms = entry.getValue().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (ms >= 0) {
                        reachable.put(entry.getKey(), ms);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Timed out, or the probe itself failed. Either way this source is not
                    // one to open when someone is waiting for a picture.
                }
            }
            Log.i(TAG, "Probed " + targets.size() + " sources: " + reachable.size() + " answered");
            return reachable;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Reorders a channel's sources fastest-first, so {@link Channel#url()} opens the best one and
     * failover walks down the list instead of across it.
     *
     * <p>Sources nothing is known about keep their playlist order and go last: a measured source
     * is proven to answer, an unmeasured one is a gamble, and being slow is still better than
     * being unknown. Reordering in place is what makes the existing failover and manual switching
     * follow the ranking for free.
     */
    public static void applyOrder(Channel channel) {
        applyOrder(channel, SourceSpeedStore.snapshot());
    }

    /**
     * {@link #applyOrder(Channel)} with the measurements already in hand.
     *
     * <p>The snapshot is taken once by the caller rather than per channel, because each one is a
     * full copy of a map that can hold hundreds of entries. Sorting a whole playlist used to take
     * that copy a couple of hundred times over, on the main thread, at exactly the moment the
     * first picture was being decoded - which is where the dropped frames and the multi-second
     * frame came from.
     */
    public static void applyOrder(Channel channel, Map<String, Integer> known) {
        if (channel == null) {
            return;
        }
        List<String> urls = channel.urls;
        if (urls.size() < 2) {
            return;
        }
        final String lastGood = SourceSpeedStore.lastGood();
        // Collections.sort is stable, so ties (and everything unknown) keep playlist order.
        // Written out rather than urls.sort(lambda): List.sort is API 24, and this app runs on
        // Android 5 boxes.
        java.util.Collections.sort(urls, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                // A source that was seen playing outranks every measurement. On a cold start
                // there is nothing else to go on, and this is the one fact that is not a guess -
                // it beats a probe that answered a HEAD request, because a stream that responds
                // and then stalls is exactly the failure a probe cannot see.
                if (lastGood != null) {
                    boolean leftGood = lastGood.equals(a);
                    boolean rightGood = lastGood.equals(b);
                    if (leftGood != rightGood) {
                        return leftGood ? -1 : 1;
                    }
                }
                Integer left = known == null ? null : known.get(a);
                Integer right = known == null ? null : known.get(b);
                if (left == null && right == null) {
                    return 0;
                }
                if (left == null) {
                    return 1;
                }
                if (right == null) {
                    return -1;
                }
                return Integer.compare(left, right);
            }
        });
    }

    /** Applies {@link #applyOrder(Channel)} to every channel. Must run off the main thread. */
    public static void applyOrder(List<Channel> channels) {
        if (channels == null) {
            return;
        }
        Map<String, Integer> known = SourceSpeedStore.snapshot();
        for (Channel channel : channels) {
            applyOrder(channel, known);
        }
    }

    /**
     * Measures a whole playlist's sources in the background and stores what it learns.
     *
     * <p>This is a courtesy to the next channel change, never a step before it: it is throttled to
     * a few sources per channel (a channel with a dozen has more fallbacks than it needs) and runs
     * at background priority, because a probe racing the player for bandwidth would undo the point
     * of measuring.
     *
     * <p>Only one run is kept alive. A second playlist load while one is in flight would otherwise
     * stack up hundreds of requests against the same servers.
     */
    public static void measureChannelsInBackground(List<Channel> channels, Runnable onFinished) {
        if (channels == null || channels.isEmpty() || !SourceSpeedStore.isEnabled()) {
            return;
        }
        List<String> targets = collectTargets(channels);
        if (targets.isEmpty()) {
            Log.i(TAG, "Every source is already measured; skipping the background probe");
            return;
        }

        synchronized (sBulkLock) {
            if (sBulkExecutor != null && !sBulkExecutor.isShutdown()) {
                // Already probing; the results will land just the same.
                return;
            }
            sBulkExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "source-probe");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            sBulkExecutor.execute(() -> {
                try {
                    Log.i(TAG, "Probing " + targets.size() + " sources in the background");
                    Map<String, Integer> measured = measure(targets);
                    SourceSpeedStore.merge(measured);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Background probe failed", e);
                } finally {
                    synchronized (sBulkLock) {
                        sBulkExecutor = null;
                    }
                    if (onFinished != null) {
                        onFinished.run();
                    }
                }
            });
        }
    }

    /**
     * Picks the sources worth probing.
     *
     * <p>Sources nothing is known about come first. Probing the top of the list every time would
     * keep re-measuring what we already know and never reach the rest, so the ranking would never
     * improve past the first few entries; probing what is unknown is what fills it in. A couple of
     * known ones follow each time, so a source that died since it was last measured gets noticed
     * rather than trusted for a week.
     */
    private static List<String> collectTargets(List<Channel> channels) {
        Map<String, Integer> known = SourceSpeedStore.snapshot();
        List<String> targets = new ArrayList<>();
        for (Channel channel : channels) {
            if (channel == null) {
                continue;
            }
            int picked = 0;
            for (int i = 0; i < channel.urlCount() && picked < MAX_SOURCES_PER_CHANNEL; i++) {
                String url = channel.urlAt(i);
                if (url != null && url.length() > 0 && !known.containsKey(url)) {
                    targets.add(url);
                    picked++;
                }
            }
            for (int i = 0; i < channel.urlCount() && picked < MAX_SOURCES_PER_CHANNEL; i++) {
                String url = channel.urlAt(i);
                if (url != null && url.length() > 0 && known.containsKey(url)) {
                    targets.add(url);
                    picked++;
                }
            }
        }
        return targets;
    }

    /**
     * Whether a channel has a source that is known to work.
     *
     * <p>"Known to work" means a recorded measurement that is not the unusable marker: a source
     * the prober reached, or one that was seen playing. Assumes {@link #applyOrder(Channel)} has
     * already run, so the best source is first.
     *
     * @return true when the channel's first source is one we have reason to trust
     */
    public static boolean hasKnownWorkingSource(Channel channel) {
        if (channel == null || channel.urlCount() == 0) {
            return false;
        }
        // applyOrder puts the last-good source first, then the fastest measured ones, then the
        // unknown; so a first entry that is neither unusable nor unknown is a real measurement.
        String first = channel.url();
        return SourceSpeedStore.rememberedLatency(first) != null;
    }

    /** Stops any in-flight background probe. */
    public static void cancelBackground() {
        synchronized (sBulkLock) {
            if (sBulkExecutor != null) {
                sBulkExecutor.shutdownNow();
                sBulkExecutor = null;
            }
        }
    }

    /** Opens one source and reports how long it took to answer, or -1 when it is unusable. */
    private static final class Probe implements Callable<Integer> {

        private final String mUrl;

        Probe(String url) {
            mUrl = url;
        }

        @Override
        public Integer call() {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(mUrl).openConnection();
                if (connection instanceof HttpsURLConnection) {
                    // Old devices cannot verify the chains these servers use now.
                    TlsCompat.applyTo((HttpsURLConnection) connection);
                }
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "*/*");
                if (!isManifest(mUrl)) {
                    // A media stream is endless; a range request asks for the first frame's worth
                    // instead of opening a tap that never closes.
                    connection.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
                }

                long started = SystemClock.elapsedRealtime();
                int status = connection.getResponseCode();
                int elapsed = (int) (SystemClock.elapsedRealtime() - started);
                if (status >= 400) {
                    return -1;
                }
                // Headers alone can come from a front-end proxy; confirm the bytes behind them.
                readSome(connection, status >= 400);
                return Math.min(elapsed, UNUSABLE_MS);
            } catch (Exception e) {
                return -1;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        /** Reads a little to prove the stream delivers; a short read or a timeout is not fatal. */
        private void readSome(HttpURLConnection connection, boolean error) {
            InputStream in = null;
            try {
                in = error ? connection.getErrorStream() : connection.getInputStream();
                if (in == null) {
                    return;
                }
                byte[] buffer = new byte[4096];
                int total = 0;
                while (total < PROBE_BYTES) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, PROBE_BYTES - total));
                    if (read < 0) {
                        break;
                    }
                    total += read;
                }
            } catch (Exception ignored) {
                // The response already arrived; a body that will not come is the player's
                // problem to discover, and it will rank this source low anyway.
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                        // Nothing useful to do while closing a probe.
                    }
                }
            }
        }
    }

    /**
     * A playlist is small and worth reading whole; a segment or stream is not. Servers that ignore
     * a range request answer 200 with the whole thing, which the read cap handles.
     */
    private static boolean isManifest(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains(".m3u8") || lower.contains(".mpd");
    }
}
