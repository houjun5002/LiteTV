package com.cabletv.player.config;

import android.util.Log;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers how fast each stream source answered, across runs.
 *
 * <p>A public playlist lists a dozen or more sources per channel and says nothing about which of
 * them still work. Probing them all on every channel change would be far too slow, so the answers
 * are remembered instead: this is what lets playback open the source it knows responds, rather
 * than the first one the playlist happens to list.
 *
 * <p>Measurements are kept per source and expire individually, so a source that was merely slow
 * last week is not trusted forever, and one that has just been re-probed does not extend the life
 * of results gathered alongside it.
 *
 * <p>The entries are stored as one flat string rather than through a serialiser. It keeps the
 * format readable in a bug report, avoids a parser dependency for a plain two-number record, and
 * URLs cannot contain the separators used here.
 */
public final class SourceSpeedStore {

    private static final String TAG = "SourceSpeedStore";

    private static final String KEY = "app_source_speeds";

    /**
     * The source that was last seen actually playing.
     *
     * <p>Kept apart from the latency table because it is a stronger fact. A latency is a guess
     * about a host answering a probe; this is a stream that was on screen. On a cold start there
     * is no time to probe anything, and this is what lets the app open on a source it knows
     * worked rather than the first line of the playlist.
     */
    private static final String LAST_GOOD_KEY = "app_last_good_source";

    /** How long a "last good" source is trusted; past this it is a guess like any other. */
    private static final long LAST_GOOD_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /** A result older than this is treated as unknown: servers and routes do change. */
    static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Upper bound on how many sources are remembered. Sources are shared across channels, so this
     * covers far more channels than the raw count suggests, and it caps what a low-memory box has
     * to hold in its preferences.
     */
    private static final int MAX_ENTRIES = 800;

    private static final char FIELD = '\t';
    private static final char RECORD = '\n';

    /** Sources to latency in milliseconds, either measured or the default when nothing is known. */
    private static final Map<String, Integer> sLatency = new HashMap<>();
    private static final Map<String, Long> sMeasuredAt = new HashMap<>();
    private static volatile boolean sLoaded;

    private SourceSpeedStore() {
    }

    /**
     * Latency per source, in milliseconds.
     *
     * <p>Reads go through an in-memory copy: this is consulted on every channel change, and
     * re-reading preferences each time would put a synchronous disk read on the tuning path.
     */
    public static synchronized Map<String, Integer> snapshot() {
        ensureLoaded();
        return new HashMap<>(sLatency);
    }

    /** How many sources are currently remembered, for diagnostics. */
    public static synchronized int size() {
        ensureLoaded();
        return sLatency.size();
    }

    /**
     * Stores freshly measured latencies. Sources not mentioned keep whatever they had, so probing
     * one channel never forgets what the others taught us.
     */
    public static synchronized void merge(Map<String, Integer> measured) {
        if (measured == null || measured.isEmpty()) {
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> entry : measured.entrySet()) {
            String url = entry.getKey();
            Integer ms = entry.getValue();
            if (url == null || url.isEmpty() || ms == null) {
                continue;
            }
            if (ms < 0) {
                // The probe answered "this one did not respond". Recording it as dead is the
                // entire point of probing: skipping these, as this used to, meant a source that
                // failed every single probe kept whatever optimistic number it was first given,
                // and went on being ranked to the front of the list forever. That is what made
                // a channel open a host that no longer answers, time out after ten seconds, and
                // do it again on every retry while the working sources behind it were never
                // reached.
                sLatency.put(url, UNUSABLE_MS);
                sMeasuredAt.put(url, now);
                continue;
            }
            sLatency.put(url, ms);
            sMeasuredAt.put(url, now);
        }
        evictOldestIfNeeded();
        save();
    }

    /** Forgets every measurement, so the next run probes from scratch. */
    public static synchronized void clear() {
        sLatency.clear();
        sMeasuredAt.clear();
        Hawk.delete(KEY);
    }

    /**
     * The latency recorded for a source that was proven unusable: a DRM block, a notice clip, or
     * a stream that refused to play. Higher than any real measurement, so it sorts last.
     */
    private static final int UNUSABLE_MS = Integer.MAX_VALUE - 1;

    /**
     * The latency on record for a source, or null when the source is unknown or was rejected.
     *
     * <p>Returns null for the unusable marker rather than the marker itself, because callers are
     * asking "do we have reason to trust this source", and "we know it is broken" is not a yes.
     *
     * @param url the source to look up
     * @return the recorded latency in milliseconds, or null when there is nothing usable on record
     */
    public static synchronized Integer rememberedLatency(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // Loading already drops records past their expiry, so what is in the maps is current.
        ensureLoaded();
        Integer ms = sLatency.get(url);
        if (ms == null || ms == UNUSABLE_MS) {
            return null;
        }
        return ms;
    }

    /**
     * Records a source as unusable.
     *
     * <p>It is kept rather than deleted, which looks backwards but is not: the background prober
     * only measures sources it has no entry for, so deleting this one would have it measured and
     * ranked by speed on the very next run - and a stream that answers a probe quickly while
     * playing an anti-piracy notice would come straight back to the front. An entry saying "last
     * known dead" is what keeps it at the back, and its age still expires it like any other.
     */
    public static synchronized void markUnusable(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        ensureLoaded();
        sLatency.put(url, UNUSABLE_MS);
        sMeasuredAt.put(url, System.currentTimeMillis());
        evictOldestIfNeeded();
        save();
    }

    /**
     * Records the source that is playing right now, for the next launch to start from.
     *
     * <p>Called when playback actually begins, not when a URL is handed over: the whole point is
     * to remember one that is known to work, and a source that was merely attempted is not.
     */
    public static synchronized void markLastGood(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Hawk.put(LAST_GOOD_KEY, url + FIELD + System.currentTimeMillis());
    }

    /**
     * The last source seen playing, if recent enough to be worth trying first.
     *
     * @return the URL, or null when nothing is known or the memory is stale
     */
    public static synchronized String lastGood() {
        String stored = Hawk.get(LAST_GOOD_KEY, "");
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        int split = stored.lastIndexOf(FIELD);
        if (split <= 0) {
            return null;
        }
        long at = parseLong(stored.substring(split + 1), -1);
        if (at < 0 || System.currentTimeMillis() - at > LAST_GOOD_MAX_AGE_MS) {
            return null;
        }
        return stored.substring(0, split);
    }

    /** The automatic background probe can be turned off; the stored results stay usable. */
    public static boolean isEnabled() {
        return AppConfig.isSourceSpeedEnabled();
    }

    public static void setEnabled(boolean enabled) {
        AppConfig.setSourceSpeedEnabled(enabled);
    }

    private static void ensureLoaded() {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        String stored = Hawk.get(KEY, "");
        if (stored == null || stored.isEmpty()) {
            return;
        }
        int expired = 0;
        for (String record : stored.split(String.valueOf(RECORD))) {
            String[] parts = record.split(String.valueOf(FIELD));
            if (parts.length != 3) {
                continue;
            }
            long at = parseLong(parts[2], -1);
            int ms = parseInt(parts[1], -1);
            if (at < 0 || ms < 0) {
                continue;
            }
            if (at < cutoff) {
                expired++;
                continue;
            }
            sLatency.put(parts[0], ms);
            sMeasuredAt.put(parts[0], at);
        }
        if (sLatency.size() + expired > 0) {
            Log.i(TAG, "Restored " + sLatency.size() + " source speeds (" + expired + " expired)");
        }
    }

    /** Drops the least recently measured sources once too many are held. */
    private static void evictOldestIfNeeded() {
        if (sLatency.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<String, Long>> byAge =
                new ArrayList<>(sMeasuredAt.entrySet());
        // Written out rather than sort(Comparator.comparingLong(...)): that is API 24, and this
        // app runs on Android 5 boxes.
        java.util.Collections.sort(byAge, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(a.getValue(), b.getValue());
            }
        });
        int toDrop = sLatency.size() - MAX_ENTRIES;
        for (int i = 0; i < toDrop && i < byAge.size(); i++) {
            String url = byAge.get(i).getKey();
            sLatency.remove(url);
            sMeasuredAt.remove(url);
        }
    }

    private static void save() {
        StringBuilder out = new StringBuilder();
        // LinkedHashMap keeps the output stable, so the stored value only changes when a
        // measurement does.
        Map<String, Integer> sorted = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(sLatency.keySet());
        java.util.Collections.sort(keys);
        for (String url : keys) {
            sorted.put(url, sLatency.get(url));
        }
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            Long at = sMeasuredAt.get(entry.getKey());
            if (at == null) {
                continue;
            }
            out.append(entry.getKey()).append(FIELD)
                    .append(entry.getValue()).append(FIELD)
                    .append(at).append(RECORD);
        }
        if (out.length() == 0) {
            Hawk.delete(KEY);
        } else {
            Hawk.put(KEY, out.toString());
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
