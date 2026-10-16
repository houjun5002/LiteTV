package xyz.doikki.videoplayer.player;

import android.content.res.AssetFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

/**
 * 抽象的播放器，继承此接口扩展自己的播放器
 * Created by Doikki on 2017/12/21.
 */
public abstract class AbstractPlayer {

    /**
     * 视频/音频开始渲染
     */
    public static final int MEDIA_INFO_RENDERING_START = 3;

    /**
     * 缓冲开始
     */
    public static final int MEDIA_INFO_BUFFERING_START = 701;

    /**
     * 缓冲结束
     */
    public static final int MEDIA_INFO_BUFFERING_END = 702;

    /**
     * 视频旋转信息
     */
    public static final int MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001;

    /**
     * 播放器事件回调
     */
    protected PlayerEventListener mPlayerEventListener;

    /**
     * 初始化播放器实例
     */
    public abstract void initPlayer();

    /**
     * 设置播放地址
     *
     * @param path    播放地址
     * @param headers 播放地址请求头
     */
    public abstract void setDataSource(String path, Map<String, String> headers);

    /**
     * 用于播放raw和asset里面的视频文件
     */
    public abstract void setDataSource(AssetFileDescriptor fd);

    /**
     * 播放
     */
    public abstract void start();

    /**
     * 暂停
     */
    public abstract void pause();

    /**
     * 停止
     */
    public abstract void stop();

    /**
     * 准备开始播放（异步）
     */
    public abstract void prepareAsync();

    /**
     * 在播放器不重建的前提下切换数据源（无缝换台）
     * 仅在播放器已初始化时才有效，否则退化为 setDataSource + prepareAsync
     *
     * @param path    新的播放地址
     * @param headers 播放地址请求头
     */
    public void switchDataSource(String path, Map<String, String> headers) {
        // 默认实现：等价于 setDataSource + prepareAsync
        setDataSource(path, headers);
        prepareAsync();
    }

    /**
     * 重置播放器
     */
    public abstract void reset();

    /**
     * 是否正在播放
     */
    public abstract boolean isPlaying();

    /**
     * 调整进度
     */
    public abstract void seekTo(long time);

    /**
     * 释放播放器
     */
    public abstract void release();

    /**
     * 获取当前播放的位置
     */
    public abstract long getCurrentPosition();

    /**
     * 获取视频总时长
     */
    public abstract long getDuration();

    /**
     * 获取缓冲百分比
     */
    public abstract int getBufferedPercentage();

    /**
     * The furthest position the player has buffered, in milliseconds.
     *
     * <p>Not left abstract: only ExoPlayer can answer it, and every caller has to cope with not
     * knowing anyway - a live stream may not be seekable at all.
     *
     * @return the buffered position, or the current position when it is unknown
     */
    public long getBufferedPosition() {
        return getCurrentPosition();
    }

    /**
     * Caps the video height, or 0 for no cap. Only some sources carry several renditions, so this
     * is a request: a stream with one quality ignores it, and a caller that assumed otherwise
     * would be showing the viewer a menu that does nothing.
     */
    public void setMaxVideoHeight(int height) {
    }

    /**
     * 设置渲染视频的View,主要用于TextureView
     */
    public abstract void setSurface(Surface surface);

    /**
     * 设置渲染视频的View,主要用于SurfaceView
     */
    public abstract void setDisplay(SurfaceHolder holder);

    /**
     * 设置音量
     */
    public abstract void setVolume(float v1, float v2);

    /**
     * 设置是否循环播放
     */
    public abstract void setLooping(boolean isLooping);

    /**
     * 设置其他播放配置
     */
    public abstract void setOptions();

    /**
     * 设置播放速度
     */
    public abstract void setSpeed(float speed);

    /**
     * 获取播放速度
     */
    public abstract float getSpeed();

    /**
     * 获取当前缓冲的网速
     */
    public abstract long getTcpSpeed();

    /**
     * 绑定VideoView
     */
    public void setPlayerEventListener(PlayerEventListener playerEventListener) {
        this.mPlayerEventListener = playerEventListener;
    }

    public interface PlayerEventListener {

        /** The stream failed for some unspecified reason. */
        int PLAYER_ERROR_UNKNOWN = 0;

        /**
         * The stream was refused rather than broken: a DRM check or a substituted
         * anti-piracy notice. Retrying and format-switching cannot help; only another
         * source can.
         */
        int PLAYER_ERROR_BLOCKED = 1;

        void onError();

        /**
         * The stream failed, with a reason.
         *
         * <p>Not abstract: a player that cannot tell the cases apart reports the unknown one,
         * and callers must handle it anyway.
         */
        default void onError(int reason) {
            onError();
        }

        void onCompletion();

        void onInfo(int what, int extra);

        void onPrepared();

        void onVideoSizeChanged(int width, int height);

    }

}
