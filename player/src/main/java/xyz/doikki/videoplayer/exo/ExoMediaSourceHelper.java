package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.ExoDatabaseProvider;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {
    public static final String HEADER_FORMAT = "TVBox-Format";

    private static ExoMediaSourceHelper sInstance;

    /**
     * Default ceiling for the segment cache. Small on purpose: this is a television box, where
     * storage is the one thing the viewer is not meant to have to think about, and a cache that
     * grows on its own is a complaint waiting to happen.
     */
    public static final long DEFAULT_CACHE_MAX_BYTES = 128 * 1024 * 1024;

    private final Context mAppContext;
    private OkHttpDataSource.Factory mHttpDataSourceFactory;
    private Cache mCache;
    private OkHttpClient mClient;
    private volatile boolean mCacheEnabled;
    private volatile long mCacheMaxBytes = DEFAULT_CACHE_MAX_BYTES;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mClient = client;
        mHttpDataSourceFactory = null;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, inferContentType(uri, headers));
    }

    public MediaSource getHlsMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false, C.TYPE_HLS);
    }

    /**
     * Turns the segment cache on or off. Nothing is deleted by turning it off; the space is
     * reclaimed when the entries age out or {@link #clearCache()} is called.
     */
    public void setCacheEnabled(boolean enabled) {
        mCacheEnabled = enabled;
    }

    /**
     * Resizes the segment cache. The old one is released rather than merged, so the ceiling
     * actually drops when the viewer asks for a smaller one.
     */
    public void setCacheMaxBytes(long bytes) {
        long target = bytes <= 0 ? 0 : bytes;
        if (target == mCacheMaxBytes) {
            return;
        }
        synchronized (this) {
            mCacheMaxBytes = target;
            if (mCache != null) {
                try {
                    mCache.release();
                } catch (Exception ignored) {
                    // The cache is being replaced; nothing to do if it was already gone.
                }
                mCache = null;
            }
        }
    }

    /** Empties the segment cache and releases it. */
    public void clearCache() {
        synchronized (this) {
            if (mCache != null) {
                try {
                    mCache.release();
                } catch (Exception ignored) {
                    // Already released.
                }
                mCache = null;
            }
        }
        deleteDir(cacheDir());
    }

    /**
     * What the cache is holding, for a settings screen that shows what it is costing. Walks the
     * cache's own index, so it counts the same bytes the evictor does.
     */
    public long getCacheBytes() {
        Cache cache;
        synchronized (this) {
            if (mCache == null) {
                if (!mCacheEnabled) {
                    return 0;
                }
                mCache = newCache();
            }
            cache = mCache;
        }
        return cache.getCacheSpace();
    }

    /** Only segmented sources can be cached a piece at a time and reused. */
    private static boolean isSegmentable(int contentType) {
        return contentType == C.TYPE_HLS || contentType == C.TYPE_DASH;
    }

    private MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int contentType) {
        Uri contentUri = Uri.parse(uri);
        // An endless progressive stream has no segments to reuse: caching it would just keep
        // appending the same broadcast until the disk was full. Only segmented sources benefit.
        boolean useCache = isCache || (mCacheEnabled && isSegmentable(contentType));
        DataSource.Factory factory;
        if (useCache) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }
        applyHeaders(headers);
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory)
                        .setLoadErrorHandlingPolicy(new HlsErrorHandlingPolicy())  // 设置自定义错误处理策略，跳过坏的切片
                        .createMediaSource(MediaItem.fromUri(contentUri));
            default:
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
        }
    }

    private int inferContentType(String fileName, Map<String, String> headers) {
        int formatType = inferFormatContentType(headers);
        if (formatType != C.TYPE_OTHER) {
            return formatType;
        }
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd") || fileName.contains("type=mpd") || fileName.contains("type=dash") || fileName.contains("format=mpd") || fileName.contains("format=dash")) {
            return C.TYPE_DASH;
        } else if (isHlsUri(fileName)) {
            return C.TYPE_HLS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    private int inferFormatContentType(Map<String, String> headers) {
        if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
            return C.TYPE_OTHER;
        }
        String format = headers.get(HEADER_FORMAT);
        if (format == null) {
            return C.TYPE_OTHER;
        }
        format = format.trim().toLowerCase();
        if (format.equals("hls") || format.contains("mpegurl") || format.contains("m3u8")) {
            return C.TYPE_HLS;
        }
        if (format.equals("dash") || format.equals("mpd") || format.contains("dash+xml")) {
            return C.TYPE_DASH;
        }
        return C.TYPE_OTHER;
    }

    private boolean isHlsUri(String uri) {
        if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
            return true;
        }
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        return path.endsWith("/live.php") || path.contains("/live/");
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        newCache();
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private synchronized Cache newCache() {
        if (mCache != null) {
            return mCache;
        }
        mCache = new SimpleCache(
                cacheDir(),
                // Least-recently-used eviction is what keeps the promise that the cache is
                // bounded: entries past the ceiling go, and what is being watched now stays.
                new LeastRecentlyUsedCacheEvictor(mCacheMaxBytes),
                new ExoDatabaseProvider(mAppContext));
        return mCache;
    }

    private File cacheDir() {
        File externalCacheDir = mAppContext.getExternalCacheDir();
        File base = externalCacheDir != null ? externalCacheDir : mAppContext.getCacheDir();
        return new File(base, "exo-video-cache");
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDir(file);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        return new androidx.media3.datasource.DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory());
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private OkHttpDataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            OkHttpClient client = mClient != null ? mClient : new OkHttpClient.Builder().build();
            mHttpDataSourceFactory = new OkHttpDataSource.Factory(client);
        }
        return mHttpDataSourceFactory;
    }

    private void applyHeaders(Map<String, String> headers) {
        Map<String, String> requestHeaders = new HashMap<>();
        String userAgent = null;
        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    requestHeaders.put(key, value.trim());
                }
            }
        }
        mHttpDataSourceFactory.setUserAgent(userAgent);
        mHttpDataSourceFactory.setDefaultRequestProperties(requestHeaders);
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
