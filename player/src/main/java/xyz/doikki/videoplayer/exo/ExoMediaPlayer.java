package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.common.VideoSize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewConfig;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    private static final String TAG = "ExoMediaPlayer";

    protected Context mAppContext;
    protected ExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    protected TrackSelector mTrackSelector;

    protected DefaultTrackSelector trackSelector;

    protected String currentPlayPath;
    protected Map<String, String> currentHeaders;
    private boolean mRetriedAsHls;
    /** 0 for no cap; otherwise the tallest rendition the track selector may pick. */
    private int mMaxVideoHeight;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mLoadControl == null) {
            mLoadControl = createDefaultLoadControl();
        }
        mInternalPlayer = new ExoPlayer.Builder(
                mAppContext,
                mRenderersFactory == null ? mRenderersFactory = createRenderersFactory(mAppContext) : mRenderersFactory,
                // Media3's Builder takes the MediaSource factory before the TrackSelector; ExoPlayer 2.x
                // had the two the other way round.
                new DefaultMediaSourceFactory(mAppContext),
                mTrackSelector == null ? mTrackSelector = new DefaultTrackSelector(mAppContext) : mTrackSelector,
                mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new DefaultAnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();
        applyOutputPreferences();

        // 播放器日志（当开启日志且 mTrackSelector 为 MappingTrackSelector 时）
        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger((MappingTrackSelector) mTrackSelector, "ExoPlayer"));
        }
        if(trackSelector == null)trackSelector=(DefaultTrackSelector)mTrackSelector;
        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    /**
     * Builds the renderers factory for the configured decode mode. Software decoding is the escape
     * hatch for devices whose hardware decoder cannot handle a stream ("channel will not open"),
     * and insisting on hardware is for the opposite case: a soft fallback that looks worse than
     * failing loudly.
     */
    static RenderersFactory createRenderersFactory(Context context) {        int mode = VideoViewManager.getConfig().mVideoDecodeMode;
        DefaultRenderersFactory factory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(mode != VideoViewConfig.DECODE_HARDWARE)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        MediaCodecSelector selector = createCodecSelector(mode);
        if (selector != null) {
            factory.setMediaCodecSelector(selector);
        }
        return factory;
    }

    /**
     * @return the selector for the decode mode, or null to keep ExoPlayer's own preference.
     *     Public so hosts (and their tests) can reason about the chosen policy.
     */
    public static MediaCodecSelector createCodecSelector(int mode) {
        if (mode == VideoViewConfig.DECODE_SOFTWARE) {
            return new DecodePreferenceSelector(false);
        }
        if (mode == VideoViewConfig.DECODE_HARDWARE) {
            return new DecodePreferenceSelector(true);
        }
        return null;
    }

    /**
     * Applies per-output preferences that are not part of the renderers factory: the track
     * selection policy and the frame-rate hint passed to the display.
     */
    private void applyOutputPreferences() {
        VideoViewConfig config = VideoViewManager.getConfig();
        if (mTrackSelector instanceof DefaultTrackSelector) {
            DefaultTrackSelector trackSelector = (DefaultTrackSelector) mTrackSelector;
            DefaultTrackSelector.Parameters.Builder parameters = trackSelector.buildUponParameters();
            if (mMaxVideoHeight > 0) {
                // The viewer asked for a ceiling, so stop at the best rendition under it.
                // Asking for the highest bitrate at the same time would contradict the cap.
                // Width is unconstrained: a 4:3 picture has to stay 4:3.
                parameters.setMaxVideoSize(Integer.MAX_VALUE, mMaxVideoHeight);
            } else {
                // Playlists often carry several renditions of one channel; take the best one
                // instead of adapting down to the first that fits.
                parameters.setForceHighestSupportedBitrate(true);
            }
            if (config.mTunnelingEnabled) {
                parameters.setTunnelingEnabled(true);
            }
            trackSelector.setParameters(parameters.build());
        }
        try {
            // Ask the TV to match its panel refresh rate to the stream (50Hz IPTV content on a
            // 60Hz panel judders otherwise). Honoured from Android 11; harmless below that.
            mInternalPlayer.setVideoChangeFrameRateStrategy(
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS);
        } catch (Exception e) {
            Log.d(TAG, "Frame-rate matching unavailable: " + e.getMessage());
        }
    }

    /**
     * Keeps only the decoders matching the requested hardware/software preference. The platform's
     * full list is returned when the device has none of the wanted kind, because falling back
     * beats failing to start.
     */
    private static final class DecodePreferenceSelector implements MediaCodecSelector {

        private final boolean mHardware;

        DecodePreferenceSelector(boolean hardware) {
            mHardware = hardware;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                   boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (all.size() <= 1) {
                return all;
            }
            List<MediaCodecInfo> preferred = new ArrayList<>(all.size());
            for (MediaCodecInfo info : all) {
                if (info.hardwareAccelerated == mHardware) {
                    preferred.add(info);
                }
            }
            return preferred.isEmpty() ? all : preferred;
        }
    }

    /**
     * The buffering policy for live streams. Kept in one place and public so a unit test can
     * guard the values: {@link DefaultLoadControl.Builder#setBufferDurationsMs} asserts
     * {@code minBufferMs >= bufferForPlaybackMs} and
     * {@code minBufferMs >= bufferForPlaybackAfterRebufferMs}, and violating that crashed the app
     * on the first channel change (issue #6).
     *
     * <p>The proportions matter as much as the values. Playback starts once
     * {@code bufferForPlaybackMs} is buffered, so 500ms keeps channel switching snappy; a
     * rebuffer only resumes after {@code bufferForPlaybackAfterRebufferMs} (1.5s) so a jitter
     * burst cannot loop; {@code minBufferMs} (1.5s) is what the loader keeps topped up and
     * {@code maxBufferMs} (12s) gives 4K feeds room to ride out network bursts. The previous
     * 200/500ms thresholds started playback with barely any buffer, which showed up as constant
     * rebuffering.
     */
    public static LoadControl createDefaultLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 12_000, 500, 1_500)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build();
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        Log.i("Tvbox-runtime","echo-setDataSource:"+path);
        currentPlayPath = path;
        currentHeaders = copyHeaders(headers);
        mRetriedAsHls = false;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, copyHeaders(currentHeaders));
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        // 不支持AssetFileDescriptor方式
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource);
        mInternalPlayer.prepare();
    }

    @Override
    public void switchDataSource(String path, Map<String, String> headers) {
        if (mInternalPlayer == null) {
            // Player not initialized, fall back to setDataSource + prepareAsync
            super.switchDataSource(path, headers);
            return;
        }
        // Seamless switch: reuse the same ExoPlayer instance
        currentPlayPath = path;
        currentHeaders = copyHeaders(headers);
        mRetriedAsHls = false;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, copyHeaders(currentHeaders));
        if (mMediaSource != null) {
            mInternalPlayer.setMediaSource(mMediaSource);
            mInternalPlayer.prepare();
        }
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mInternalPlayer.setVideoSurface(null);
            mIsPreparing = false;
            mRetriedAsHls = false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public long getBufferedPosition() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPosition();
    }

    @Override
    public void setMaxVideoHeight(int height) {
        mMaxVideoHeight = height;
        // Applied at once so the change is visible on the stream that is already playing; the
        // stored value is picked up again by the next player, and on the next channel.
        if (mInternalPlayer != null) {
            applyOutputPreferences();
        }
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        return PlayerUtils.getNetSpeed(mAppContext);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e("Tvbox-runtime", "echo-Exo player error: " + currentPlayPath, error);
        if (retryAsHls(error)) {
            return;
        }
        if (mPlayerEventListener != null) {
            // Some public sources answer with a DRM or anti-piracy notice instead of the stream:
            // a short clip, a still frame telling the viewer they are "a victim of piracy" and to
            // contact a Telegram account. It plays perfectly and is not an error at all, so no
            // amount of retrying or format-switching gets past it - only moving to the next
            // source does. Flagging it lets the caller skip rather than reconnect forever.
            mPlayerEventListener.onError(isBlockedByNotice(error)
                    ? PlayerEventListener.PLAYER_ERROR_BLOCKED
                    : PlayerEventListener.PLAYER_ERROR_UNKNOWN);
        }
    }

    /**
     * True when a failure looks like a rejected or substituted stream rather than a broken one.
     *
     * <p>A DRM-protected stream cannot be played by any player that has no licence for it, and
     * counting it as a transport failure would make the app reconnect to it five times over.
     */
    private boolean isBlockedByNotice(PlaybackException error) {
        int code = error.errorCode;
        if (code == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
                || code == PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
                || code == PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION
                || code == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
                || code == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
                || code == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR
                || code == PlaybackException.ERROR_CODE_DRM_UNSPECIFIED) {
            return true;
        }
        // A substituted notice is not marked as any of those; it is served as ordinary content
        // whose only failing is that it is far too short to be a live channel. Callers treat the
        // "played to the end almost immediately" case as the same signal.
        return code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                && currentPlayPath != null && currentPlayPath.startsWith("rtsp");
    }

    private boolean retryAsHls(PlaybackException error) {
        if (mRetriedAsHls || mInternalPlayer == null || currentPlayPath == null) {
            return false;
        }
        if (!isParsingError(error)) {
            return false;
        }
        mRetriedAsHls = true;
        Log.i("Tvbox-runtime", "echo-Exo retry as HLS: " + currentPlayPath);
        mMediaSource = mMediaSourceHelper.getHlsMediaSource(currentPlayPath, copyHeaders(currentHeaders));
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource);
        mInternalPlayer.prepare();
        mInternalPlayer.setPlayWhenReady(true);
        return true;
    }

    private boolean isParsingError(PlaybackException error) {
        int errorCode = error.errorCode;
        return errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? null : new HashMap<>(headers);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }
}
