package com.cabletv.player.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;
import com.cabletv.player.util.M3uParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChannelRepository {
    private static final String TAG = "ChannelRepository";
    private static final String CACHE_DIR = "playlist_cache";
    private static final String CACHE_FILE = "playlist.m3u";
    /** Which URL the cached playlist came from, so replaying it keeps the same shape. */
    private static final String CACHE_SOURCE_FILE = "playlist.m3u.source";
    /** The handful of channels bundled with the app, for a first launch that has no cache yet. */
    private static final String SEED_PLAYLIST = "seed_playlist.m3u";

    /**
     * The channel a launch with nothing remembered opens on.
     *
     * <p>CCTV2 rather than CCTV1, which is where a playlist normally starts: its sources were
     * verified by fetching real video data, and it is the channel most reliably available across
     * the public sources this app ships with. Opening on a channel that plays is worth more than
     * opening on the lowest number.
     */
    private static final String OPENING_CHANNEL = "CCTV2";

    /**
     * The index of a channel by name, or -1.
     *
     * <p>Compared with separators and case ignored, because the playlists are inconsistent about
     * both: CCTV2 and CCTV-2 are the same station.
     */
    private int indexOfChannel(String name) {
        List<Channel> channels = mFlatChannels;
        if (name == null || channels == null) {
            return -1;
        }
        String wanted = normalizeChannelName(name);
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            if (channel != null && wanted.equals(normalizeChannelName(channel.name))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeChannelName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '\u3000') {
                continue;
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
    /**
     * How long to wait for a playlist to answer.
     *
     * <p>Short on purpose. This is the delay before a picture appears, and the sources are tried
     * in turn - at the old eight seconds each, a first launch that walked three of them kept the
     * screen dark for the best part of half a minute. A playlist is a few hundred kilobytes from
     * a content network: if it has not answered in four seconds it is not going to.
     */
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<OnChannelsChangedListener> mListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean mLoading = new AtomicBoolean(false);
    private final File mCacheFile;
    /** Invalidates a background refresh when a newer load has already started. */
    private final AtomicInteger mLoadGeneration = new AtomicInteger(0);
    /** The bundled channels are only worth showing once; the refresh replaces them. */
    private volatile boolean mSeedPublished;

    private volatile List<ChannelGroup> mChannels = Collections.emptyList();
    private volatile List<Channel> mFlatChannels = Collections.emptyList();
    private volatile OnLoadFailedListener mLoadFailedListener;

    public interface OnChannelsChangedListener {
        void onChannelsChanged(List<ChannelGroup> channels);
    }

    /**
     * Reports that the configured playlist could not be loaded, so the UI can say so
     * instead of showing an empty channel list with no explanation.
     */
    public interface OnLoadFailedListener {
        /**
         * @param reason           short human readable cause (HTTP status, IO message, ...)
         * @param servedFromCache  true when the last saved playlist was shown instead
         */
        void onLoadFailed(String reason, boolean servedFromCache);
    }

    public ChannelRepository(Context context) {
        mContext = context.getApplicationContext();
        File cacheDir = new File(mContext.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Cannot create playlist cache dir: " + cacheDir);
        }
        mCacheFile = new File(cacheDir, CACHE_FILE);
    }

    public void addListener(OnChannelsChangedListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(OnChannelsChangedListener listener) {
        mListeners.remove(listener);
    }

    public void setOnLoadFailedListener(OnLoadFailedListener listener) {
        mLoadFailedListener = listener;
    }

    /** Drops every callback so a destroyed Activity is not kept alive by a running load. */
    public void clearListeners() {
        mListeners.clear();
        mLoadFailedListener = null;
    }

    /**
     * The first channel whose sources are all known to work, or -1 when nothing is known.
     *
     * <p>Used to pick what a first launch opens on. The ordering of the playlist reflects channel
     * numbers, not whether the streams behind them answer, so "first" is an arbitrary choice that
     * has nothing to do with what will play. A channel with at least one measured source is a
     * far better bet, and the measurement is what makes it a bet worth taking.
     *
     * @return a flat channel index, or -1 when no channel has a usable source on record
     */
    public int firstChannelWithKnownSource() {
        List<Channel> channels = mFlatChannels;
        if (channels == null || channels.isEmpty()) {
            return -1;
        }
        SourceSpeedTester.applyOrder(channels);
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            if (channel != null && SourceSpeedTester.hasKnownWorkingSource(channel)) {
                return i;
            }
        }
        // Nothing measured: prefer the channel the bundled list opens on. It is checked by
        // segment before shipping, so it is the only source on a fresh install with evidence
        // behind it.
        int preferred = indexOfChannel(OPENING_CHANNEL);
        if (preferred >= 0 && channels.get(preferred).urlCount() > 0) {
            return preferred;
        }
        if (mSeedPublished) {
            // The bundled channels have no measurements yet - they have never been probed - but
            // their sources were verified by fetching actual video data before being shipped, so
            // they are known to work in the only sense that matters. Without this a first launch
            // would ignore the one list on the device that is guaranteed to play.
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i) != null && channels.get(i).urlCount() > 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public void reload() {
        if (!mLoading.compareAndSet(false, true)) {
            Log.d(TAG, "reload() ignored: a load is already in progress");
            return;
        }
        new Thread(() -> {
            try {
                loadBlocking();
            } catch (Throwable t) {
                Log.e(TAG, "Unexpected error loading channels", t);
            } finally {
                mLoading.set(false);
            }
        }, "channel-reload").start();
    }

    private void loadBlocking() {
        // A playlist file the user put on the device is a deliberate choice of its own, not a
        // built-in to fall back from.
        String m3uFilePath = AppConfig.getM3uFilePath();
        if (!TextUtils.isEmpty(m3uFilePath) && new File(m3uFilePath).exists()) {
            String content = readFileQuietly(new File(m3uFilePath));
            if (content != null) {
                List<ChannelGroup> local = parseQuietly(content, null);
                if (local != null) {
                    publishParsed(content, null, local);
                    return;
                }
            }
            finishWithCache(content == null
                    ? "Cannot read " + m3uFilePath
                    : "Playlist is empty or could not be parsed");
            return;
        }

        String configured = AppConfig.getM3uUrl();

        // Nothing on disk yet: this is the very first launch. Publishing the bundled channels
        // first means the viewer has CCTV on screen while the real playlist downloads, instead of
        // a black screen for as long as that takes. It is a stopgap, replaced by the refresh
        // below within a few seconds and never written to the cache, so a stale bundled list can
        // never outlive the launch it was there for.
        boolean seedPublished = publishBundledSeed();

        List<String> candidates = new ArrayList<>();
        if (!TextUtils.isEmpty(configured)) {
            candidates.add(configured);
        }
        candidates.addAll(BuiltInSources.fallbacksFrom(configured));

        if (candidates.isEmpty()) {
            // MainActivity already tells the user how to configure a source.
            Log.w(TAG, "No M3U source configured");
            AppConfig.setM3uEpgUrls(Collections.emptyList());
            return;
        }

        // What was playing last time is what the viewer expects to see now, and it is already on
        // disk. Publishing it first turns the first launch from a download people stare at into a
        // picture that is up before the download starts; the refresh below replaces it silently.
        // Only for our own sources: the viewer may have just edited their URL, and showing the
        // old playlist would look like their change was ignored.
        boolean cacheWasPublished = publishCacheFirst(configured);

        int generation = mLoadGeneration.incrementAndGet();
        String failure = null;
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            String content;
            try {
                content = loadFromUrl(url);
            } catch (Exception e) {
                Log.w(TAG, "Playlist unreachable: " + url + " (" + describe(e) + ")");
                failure = describe(e);
                continue;
            }
            List<ChannelGroup> groups = parseQuietly(content, url);
            if (groups == null) {
                failure = "Playlist is empty or could not be parsed";
                continue;
            }
            if (mLoadGeneration.get() != generation) {
                Log.d(TAG, "A newer load superseded this one; discarding " + url);
                return;
            }
            publishParsed(content, url, groups);
            saveToCache(content, url);
            return;
        }
        if (!cacheWasPublished && !seedPublished) {
            finishWithCache(failure == null ? "No playlist available" : failure);
        } else {
            // Something watchable is already on screen - the cached playlist or the bundled
            // channels; a failed refresh is worth a log, not an interruption.
            Log.w(TAG, "Refresh failed, keeping what is on screen: " + failure);
            notifyLoadFailed(failure == null ? "Refresh failed" : failure, true);
        }
    }

    /**
     * Publishes the channels bundled with the app, for a launch that has nothing cached.
     *
     * <p>These are a handful of CCTV streams, present so the app has something to show while the
     * real playlist is fetched. They are deliberately not cached and not treated as a playlist:
     * they exist to cover the seconds between the app starting and the download finishing, and
     * the refresh replaces them.
     *
     * @return true when the bundled channels were published
     */
    private boolean publishBundledSeed() {
        if (mSeedPublished || mCacheFile.exists()) {
            // Already used this launch, or there is a real playlist on disk that is better.
            return false;
        }
        mSeedPublished = true;
        try {
            InputStream in = mContext.getAssets().open(SEED_PLAYLIST);
            String content;
            try {
                content = readStream(in);
            } finally {
                closeQuietly(in);
            }
            List<ChannelGroup> groups = parseQuietly(content, null);
            if (groups == null) {
                return false;
            }
            Log.i(TAG, "Publishing the bundled channels while the playlist downloads");
            publish(groups);
            return true;
        } catch (Exception e) {
            // A missing or unreadable seed is not an error worth reporting; the real playlist is
            // already on its way.
            Log.w(TAG, "Cannot read the bundled channels", e);
            return false;
        }
    }

    /**
     * Shows the cached playlist if there is one, so a launch has something to watch while the
     * real thing downloads.
     *
     * @param configured the URL the user has set; the cache is only used when it matches what the
     *                   cache was built from
     * @return true when a cached playlist was published
     */
    private boolean publishCacheFirst(String configured) {
        String cachedSource = readCachedSource();
        if (cachedSource == null || !cachedSource.equals(configured)) {
            return false;
        }
        String content = readFileQuietly(mCacheFile);
        List<ChannelGroup> cached = parseQuietly(content, cachedSource);
        if (cached == null) {
            return false;
        }
        Log.i(TAG, "Publishing the cached playlist while the source is refreshed");
        AppConfig.setM3uEpgUrls(M3uParser.parseEpgUrls(content, cachedSource));
        publish(cached);
        return true;
    }

    /**
     * Parses one playlist and hands it to the UI.
     *
     * @param sourceUrl where it came from, or null for a local file; decides whether the
     *                  public-playlist narrowing applies
     * @return true when something was published
     */
    private void publishParsed(String content, String sourceUrl, List<ChannelGroup> groups) {
        // Whatever we are about to show, its header is the truth for "EPG embedded in the
        // playlist": keep it in sync so the EPG manager can fall back to it. The base URL
        // is only meaningful for a URL-loaded playlist, which is what relative paths imply.
        AppConfig.setM3uEpgUrls(M3uParser.parseEpgUrls(content, sourceUrl));
        publish(groups);
    }

    /** Last resort after every source failed: replay the last playlist that did work. */
    private void finishWithCache(String reason) {
        String cachedContent = readFileQuietly(mCacheFile);
        List<ChannelGroup> cached = parseQuietly(cachedContent, readCachedSource());
        if (cached != null) {
            Log.w(TAG, "Serving cached playlist after failure: " + reason);
            AppConfig.setM3uEpgUrls(M3uParser.parseEpgUrls(cachedContent, readCachedSource()));
            publish(cached);
            notifyLoadFailed(reason, true);
        } else {
            Log.e(TAG, "Playlist load failed and no cached copy is available: " + reason);
            notifyLoadFailed(reason, false);
        }
    }

    /** @return parsed groups, or null when the content is missing, empty or channel-less. */
    private List<ChannelGroup> parseQuietly(String content, String sourceUrl) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            List<ChannelGroup> groups = M3uParser.parse(content, groupsToKeep(sourceUrl));
            if (groups == null || groups.isEmpty()) {
                return null;
            }
            for (ChannelGroup group : groups) {
                if (group.channels != null && !group.channels.isEmpty()) {
                    return groups;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing playlist", e);
            return null;
        }
    }

    /**
     * Public playlists are narrowed to CCTV and the satellite channels; a user's own playlist is
     * shown whole, because they chose its contents.
     */
    private static Collection<String> groupsToKeep(String sourceUrl) {
        return BuiltInSources.isBuiltIn(sourceUrl) ? BuiltInSources.nationalGroups() : null;
    }

    private void publish(List<ChannelGroup> groups) {
        List<Channel> flat = new ArrayList<>();
        for (ChannelGroup group : groups) {
            if (group.channels != null) {
                flat.addAll(group.channels);
            }
        }
        mChannels = Collections.unmodifiableList(groups);
        mFlatChannels = Collections.unmodifiableList(flat);
        Log.i(TAG, "Channels loaded: " + groups.size() + " groups, " + flat.size() + " channels");
        notifyListeners();
    }

    private String loadFromUrl(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            return decode(readAll(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private String readFileQuietly(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            InputStream in = new FileInputStream(file);
            try {
                return decode(readAll(in));
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading playlist file: " + file, e);
            return null;
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_PLAYLIST_BYTES) {
                    throw new IOException("Playlist exceeds " + MAX_PLAYLIST_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** M3U playlists are UTF-8 in practice; decode explicitly and drop a leading BOM. */
    private String decode(byte[] bytes) throws IOException {
        String text = new String(bytes, "UTF-8");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private void saveToCache(String content, String sourceUrl) {
        File tmp = new File(mCacheFile.getParentFile(), CACHE_FILE + ".tmp");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
            if (mCacheFile.exists() && !mCacheFile.delete()) {
                Log.w(TAG, "Cannot replace cached playlist");
            }
            if (!tmp.renameTo(mCacheFile)) {
                Log.w(TAG, "Cannot move cached playlist into place");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else {
                Log.d(TAG, "Playlist cached to " + mCacheFile.getAbsolutePath());
                saveCachedSource(sourceUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error caching playlist", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Remembers which source the cached playlist came from. Without it, replaying the cache
     * could not tell a public playlist (narrowed to CCTV and the satellites) from a user's own
     * one (shown whole).
     */
    private void saveCachedSource(String sourceUrl) {
        if (mCacheFile.getParentFile() == null) {
            return;
        }
        File sourceFile = new File(mCacheFile.getParentFile(), CACHE_SOURCE_FILE);
        if (sourceUrl == null) {
            //noinspection ResultOfMethodCallIgnored
            sourceFile.delete();
            return;
        }
        try {
            OutputStream out = new FileOutputStream(sourceFile);
            try {
                out.write(sourceUrl.getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot note the cached playlist source", e);
        }
    }

    /** @return the URL the cached playlist was downloaded from, or null when it is unknown. */
    private String readCachedSource() {
        File sourceFile = new File(mCacheFile.getParentFile(), CACHE_SOURCE_FILE);
        String url = readFileQuietly(sourceFile);
        if (url == null) {
            return null;
        }
        url = url.trim();
        return url.isEmpty() ? null : url;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /** Reads a stream fully into a string. Used for the bundled playlist, which is small. */
    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PLAYLIST_BYTES) {
                throw new IOException("Bundled playlist is too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    /** Listeners touch views, so they always run on the main thread. */
    private void notifyListeners() {
        final List<ChannelGroup> snapshot = mChannels;
        mMainHandler.post(() -> {
            for (OnChannelsChangedListener listener : mListeners) {
                listener.onChannelsChanged(snapshot);
            }
        });
    }

    private void notifyLoadFailed(final String reason, final boolean servedFromCache) {
        mMainHandler.post(() -> {
            OnLoadFailedListener listener = mLoadFailedListener;
            if (listener != null) {
                listener.onLoadFailed(reason, servedFromCache);
            }
        });
    }

    public List<ChannelGroup> getChannels() {
        return mChannels;
    }

    public List<Channel> getAllChannels() {
        return mFlatChannels;
    }

    public Channel getChannel(int index) {
        List<Channel> all = mFlatChannels;
        if (index >= 0 && index < all.size()) {
            return all.get(index);
        }
        return null;
    }

    public int getChannelCount() {
        return mFlatChannels.size();
    }

    public int getCurrentChannelIndex(String currentUrl) {
        if (TextUtils.isEmpty(currentUrl)) {
            return 0;
        }
        List<Channel> all = mFlatChannels;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).hasUrl(currentUrl)) {
                return i;
            }
        }
        return 0;
    }
}
