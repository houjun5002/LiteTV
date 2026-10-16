package com.cabletv.player;

import android.app.Application;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.util.TlsCompat;
import com.cabletv.player.update.UpdateManager;
import com.orhanobut.hawk.Hawk;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

import xyz.doikki.videoplayer.player.VideoViewConfig;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.render.SurfaceRenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class App extends Application {
    private static final String TAG = "CableTV";

    @Override
    public void onCreate() {
        super.onCreate();
        Hawk.init(this).build();
        // Before anything reads it: a fresh install gets a playlist to start from.
        AppConfig.seedBuiltInSourceIfNeeded();
        // Old Android releases cannot validate the chains GitHub and Let's Encrypt use now; this
        // extends the trust store for our own connections before anything needs it.
        TlsCompat.init(this);
        applyPlayerConfiguration();
        // The segment cache lives in the player library's own helper, so it is configured here
        // before any stream opens.
        ExoMediaSourceHelper.getInstance(this).setCacheEnabled(AppConfig.isMediaCacheEnabled());
        ExoMediaSourceHelper.getInstance(this).setCacheMaxBytes(AppConfig.getMediaCacheMaxBytes());
        setupExceptionHandler();
        // Delayed and silent: it must not slow down the first channel nor pop anything on screen.
        UpdateManager.get().scheduleStartupCheck(this);
    }

    /**
     * Hands the stored player settings to the player library. This has to run before any VideoView
     * exists — the library reads its global config once per process — which is why it lives in
     * Application.onCreate rather than in the settings screen. A changed setting therefore takes
     * effect on the next launch.
     */
    private void applyPlayerConfiguration() {
        VideoViewManager.setConfig(VideoViewConfig.newBuilder()
                .setRenderViewFactory(AppConfig.getVideoRenderMode() == AppConfig.RENDER_TEXTURE
                        ? TextureRenderViewFactory.create()
                        : SurfaceRenderViewFactory.create())
                .setVideoDecodeMode(AppConfig.getVideoDecodeMode())
                .setTunnelingEnabled(AppConfig.isTunnelingEnabled())
                .build());
    }

    private void setupExceptionHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception", throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
