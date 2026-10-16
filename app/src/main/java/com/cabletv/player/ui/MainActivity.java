package com.cabletv.player.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelNavigator;
import com.cabletv.player.config.ChannelRepository;
import com.cabletv.player.config.KeyMapping;
import com.cabletv.player.config.SourceSpeedStore;
import com.cabletv.player.config.SourceSpeedTester;
import com.cabletv.player.config.VideoQuality;
import com.cabletv.player.model.Channel;

import java.util.ArrayList;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.server.ConfigWebServer;
import com.cabletv.player.util.CatchupUrlBuilder;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private VideoView mVideoView;
    private ChannelRepository mChannelRepository;
    private ChannelNavigator mNavigator;
    private EpgManager mEpgManager;
    private ChannelListComponent mChannelListComponent;
    private PlaybackInfoComponent mPlaybackInfoComponent;
    private PlaybackStatusView mPlaybackStatusView;
    private ChannelNumberView mChannelNumberView;
    private TimeShiftBarComponent mTimeShiftBar;
    private LoadingOverlay mLoadingOverlay;
    /**
     * True while a playlist download is in progress. The load listener fires more than once -
     * once for the cached copy and again for the refresh - so the cover is dropped on the first
     * result that is worth watching rather than on the first callback.
     */
    private boolean mAwaitingChannels = true;
    private GestureDetector mGestureDetector;
    /** Rewinding within what the player already holds, for channels with no server-side replay. */
    private boolean mLocalShiftActive;
    /** Where in the stream's own timeline the local shift is parked. */
    private long mShiftTargetMs;
    private final Runnable mTimeShiftTicker = this::tickTimeShiftBar;
    /** How far back the live time-shift bar may rewind when no EPG is known. */
    private static final long TIMESHIFT_NO_EPG_WINDOW_MS = 2 * 60 * 60 * 1000L;
    /** Dragging to within this of "now" keeps (or returns to) live. */
    private static final long TIMESHIFT_LIVE_MARGIN_MS = 5 * 1000L;
    private ChannelRepository.OnChannelsChangedListener mChannelsChangedListener;
    /**
     * Snapshot of the EPG settings the in-memory programmes were loaded with. Comparing on
     * resume catches edits made in the settings screen (URL or priority), which cannot reach
     * the EpgManager directly.
     */
    private String mLastEpgConfigSignature = "";
    /** Snapshot of the M3U source settings (URL + local file), compared on resume so edits made
     *  in the settings screen reload the channels. */
    private String mLastM3uConfigSignature = "";
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private boolean mChannelListVisible = false;
    private boolean mIsInitialLoad = true;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    /** Backoff between reconnect attempts; its length is also the attempt limit. */
    private static final long[] RECONNECT_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 15_000};
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReconnectRunnable = this::reconnectCurrentChannel;
    private int mReconnectAttempt = 0;
    /** Which of the current channel's URLs is playing; the rest are fallbacks. */
    private int mCurrentUrlIndex = 0;
    /**
     * The exact URL handed to the player.
     *
     * <p>Kept because the index alone does not survive the source list being re-sorted, and
     * because it is not always the channel's own entry: catchup plays a built URL. It is the only
     * reliable way to find where playback has ended up after a re-rank.
     */
    private String mPlayingUrl;
    /** The source last written to the store, so a stall does not rewrite it. */
    private String mRememberedGoodSource;
    private boolean mDestroyed = false;

    /** Catchup (回看) playback: playing a time-shifted stream instead of live. */
    private boolean mCatchupActive = false;
    private Channel mCatchupChannel = null;
    private EpgManager.Program mCatchupProgram = null;
    /** The catchup playhead (programme start + accumulated seek offset), in epoch millis. */
    private long mCatchupPlayheadMs = 0;
    private static final long CATCHUP_SEEK_STEP_MS = 10 * 1000L;
    /** How far to rewind from "now" when live LEFT enters 回看 without EPG data. */
    private static final long CATCHUP_REWIND_MS = 2 * 60 * 1000L;
    /** How far past "now" a still-airing programme's window may extend before the server catches up. */
    private static final long CATCHUP_LIVE_TAIL_MS = 8 * 60 * 60 * 1000L;
    private final Runnable mCatchupSeekHideRunnable = this::hideCatchupSeekIndicator;

    /** How long the digits typed on the remote stay on screen before they tune a channel. */
    private static final long NUMBER_INPUT_TIMEOUT_MS = 2_000;
    private static final int MAX_CHANNEL_DIGITS = 4;
    private final StringBuilder mNumberInput = new StringBuilder();
    private final Runnable mNumberCommitRunnable = this::commitNumberInput;

    /**
     * The screen fits the remote cycles through, in presentation order. The one that fills the
     * screen leads, because that is what an unboxed app is expected to do; the ones that preserve
     * the picture's shape follow.
     */
    private static final int[] SCREEN_SCALES = {
            VideoView.SCREEN_SCALE_MATCH_PARENT,
            VideoView.SCREEN_SCALE_CENTER_CROP,
            VideoView.SCREEN_SCALE_16_9,
            VideoView.SCREEN_SCALE_4_3,
            VideoView.SCREEN_SCALE_ORIGINAL,
            VideoView.SCREEN_SCALE_DEFAULT,
    };

    /** One press of LEFT/RIGHT while rewinding locally. */
    private static final long LOCAL_SHIFT_STEP_MS = 30_000;

    /** How close to the live edge counts as live again. */
    private static final long LOCAL_LIVE_EDGE_MARGIN_MS = 2_000;

    /** How far back the local-shift bar draws, so the live thumb is not glued to its right edge. */
    private static final long LOCAL_SHIFT_WINDOW_MS = 30 * 60 * 1000;

    /** How long OK must be held to mean "long press" rather than "select". */
    private static final long OK_LONG_PRESS_MS = 600;
    private final Runnable mOkLongPressRunnable = this::onOkLongPress;
    private boolean mOkLongPressFired = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImmersiveMode.apply(this);
        setContentView(R.layout.activity_main);

        mVideoView = findViewById(R.id.video_view);
        // Configure VideoView to use ExoPlayer instead of default Android MediaPlayer
        mVideoView.setPlayerFactory(new ExoMediaPlayerFactory());
        // These streams are rarely the shape of the screen; stretch to fill unless the user has
        // chosen to keep the shape.
        mVideoView.setScreenScaleType(AppConfig.getScreenScaleType());
        mVideoView.setMaxVideoHeight(VideoQuality.maxHeight(AppConfig.getVideoQuality()));
        mChannelRepository = new ChannelRepository(this);
        mNavigator = new ChannelNavigator();
        mEpgManager = new EpgManager(this);
        mLastEpgConfigSignature = epgConfigSignature();
        mLastM3uConfigSignature = m3uConfigSignature();

        // Create overlay components
        FrameLayout rootView = (FrameLayout) mVideoView.getParent();
        if (rootView == null) {
            rootView = findViewById(R.id.video_container);
            if (rootView == null) {
                // Fallback: create a root container
                rootView = new FrameLayout(this);
                setContentView(rootView);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                mVideoView.setLayoutParams(lp);
                rootView.addView(mVideoView);
            }
        }

        // First-launch cover. A television showing nothing is indistinguishable from one that
        // crashed; this says a download is in progress and gives the viewer something moving to
        // look at until the cached playlist or the first channel appears.
        mLoadingOverlay = new LoadingOverlay(this);
        rootView.addView(mLoadingOverlay, mLoadingOverlay.createLayoutParams());

        // Add bottom playback info bar
        mPlaybackInfoComponent = new PlaybackInfoComponent(this);
        mPlaybackInfoComponent.setEpgManager(mEpgManager);
        FrameLayout.LayoutParams infoBarlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(100));
        infoBarlp.gravity = android.view.Gravity.BOTTOM;
        mPlaybackInfoComponent.setLayoutParams(infoBarlp);
        rootView.addView(mPlaybackInfoComponent);
        // On phones the info bar is the doorway to the channel list: tap it to open the menu.
        mPlaybackInfoComponent.setClickable(true);
        mPlaybackInfoComponent.setOnClickListener(v -> {
            if (!mChannelListVisible) {
                mChannelListVisible = true;
                showChannelList();
            }
        });

        // Centred status message for buffering / reconnect / playback failure
        mPlaybackStatusView = new PlaybackStatusView(this);
        rootView.addView(mPlaybackStatusView, mPlaybackStatusView.createCenteredLayoutParams());

        // Draggable time-shift seek bar, floating just above the playback info strip
        mTimeShiftBar = new TimeShiftBarComponent(this);
        mTimeShiftBar.setOnSeekListener(new TimeShiftBarComponent.OnSeekListener() {
            @Override
            public void onSeekTo(long targetMs) {
                handleTimeShiftSeek(targetMs);
            }

            @Override
            public void onBackToLive() {
                exitCatchupToLive();
            }
        });
        rootView.addView(mTimeShiftBar, mTimeShiftBar.createBottomLayoutParams());

        // Phone/touch: tap video to show controls, double-tap to cycle sources, long-press
        // to replay the current programme, vertical fling to change channel, horizontal
        // fling for LEFT/RIGHT semantics (day paging / catchup seeking).
        mGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        handleVideoTap();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        handleSourceSwitch();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        onOkLongPress();
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        handleVideoFling(Math.abs(velocityX) > Math.abs(velocityY),
                                velocityX > 0, velocityY < 0);
                        return true;
                    }
                });
        mGestureDetector.setIsLongpressEnabled(true);
        rootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));

        // Digits typed on the remote, shown while the user is still typing
        mChannelNumberView = new ChannelNumberView(this);
        rootView.addView(mChannelNumberView, mChannelNumberView.createTopEndLayoutParams());

        // Surface playback failures and reconnect automatically instead of showing a black screen
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                handlePlayStateChanged(playState);
            }
        });

        // Setup channel change listener (always called on the main thread)
        mChannelsChangedListener = new ChannelRepository.OnChannelsChangedListener() {
            @Override
            public void onChannelsChanged(java.util.List<com.cabletv.player.model.ChannelGroup> channels) {
                Log.i(TAG, "Channels updated: " + channels.size());
                // Which channel to show. The first callback of a launch restores what was last
                // watched; later ones are the background refresh replacing the cached playlist.
                if (mIsInitialLoad) {
                    mIsInitialLoad = false;
                    // The last URL watched is a better key than the channel number, but it is
                    // whatever was playing when the box was switched off - possibly a catchup
                    // stream, and possibly a source that has since died. The last *good* source is
                    // the one playback actually reached, so it is preferred, and whichever is
                    // used only selects the channel: the source order comes from applyOrder().
                    String remembered = SourceSpeedStore.lastGood();
                    if (remembered == null) {
                        remembered = AppConfig.getLastChannelUrl();
                    }
                    mCurrentChannelIndex = mChannelRepository.getCurrentChannelIndex(remembered);
                    Log.d(TAG, "Initial load: restoring channel for url=" + remembered
                            + ", index=" + mCurrentChannelIndex);
                } else {
                    // A refresh must not change the channel. Resetting to the first entry is only
                    // right when nothing is playing yet, which is the case when the cached
                    // playlist has not been published - otherwise it interrupts whatever the
                    // viewer is watching, every single launch, a few seconds in. It is also how a
                    // launch ends up on whatever junk heads the refreshed list.
                    int previous = mCurrentChannelIndex;
                    mCurrentChannelIndex = indexOfChannelWithSameName(
                            mChannelRepository.getChannel(previous));
                    Log.d(TAG, "Subsequent reload: keeping the viewer on channel index "
                            + mCurrentChannelIndex + " (was " + previous + ")");
                }
                // Group the new playlist, and start the channel keys in the group holding whatever
                // is about to play
                mNavigator.setChannels(channels, mChannelRepository.getAllChannels());
                mNavigator.setCurrentGroupIndex(mNavigator.homeGroupFor(mCurrentChannelIndex));
                // Refresh the list after the index is known, so the highlight matches what plays
                if (mChannelListComponent != null) {
                    mChannelListComponent.refreshChannels(mCurrentChannelIndex);
                    syncGuideFocusTime();
                }
                // Hand the channels to the EPG manager now that they are truly available (no
                // race with reload()). It decides for itself whether the cache still serves or
                // the feed has to be fetched, and it runs before playChannel() so the load
                // triggered by the first channel is recognised as the same one.
                java.util.List<Channel> allChannels = mChannelRepository.getAllChannels();
                if (allChannels != null && !allChannels.isEmpty()) {
                    mEpgManager.preloadEpgForAllChannels(allChannels);
                    startSourceProbe(allChannels);
                }
                // Something is about to be shown, so the cover comes down before the tune rather
                // than after: from here on the screen has either a picture or a status message.
                hideLoadingOverlay();
                // Only the channel about to be shown needs its sources ranked, and playChannel()
                // does that for it. Ranking the whole playlist here used to sort two hundred and
                // forty channels on the main thread while the first frame was being decoded,
                // which is what dropped the hundred-odd frames and stalled the UI for seconds.
                // The rest are ranked by the probe's callback, off this thread.
                playChannel(mCurrentChannelIndex);
            }
        };
        mChannelRepository.addListener(mChannelsChangedListener);

        // Tell the user when the playlist cannot be loaded or refreshed
        mChannelRepository.setOnLoadFailedListener((reason, servedFromCache) -> {
            Log.w(TAG, "Playlist load failed: " + reason + ", servedFromCache=" + servedFromCache);
            android.widget.Toast.makeText(MainActivity.this,
                    getString(servedFromCache
                                    ? R.string.playlist_load_failed_cached
                                    : R.string.playlist_load_failed,
                            reason),
                    android.widget.Toast.LENGTH_LONG).show();
        });

        // Load channels from the configured source; guide the user if there is none yet.
        String m3uUrl = AppConfig.getM3uUrl();
        String m3uFilePath = AppConfig.getM3uFilePath();
        if ((m3uUrl == null || m3uUrl.isEmpty()) && (m3uFilePath == null || m3uFilePath.isEmpty())) {
            Log.w(TAG, "No M3U source configured");
            android.widget.Toast.makeText(this,
                    getString(R.string.no_playlist_configured, AppConfig.getWebServerPort()),
                    android.widget.Toast.LENGTH_LONG).show();
        }

        // Cover the black screen for as long as there is genuinely nothing to watch. The cached
        // playlist usually lands within a few hundred milliseconds, and the overlay's fade is
        // shorter than that.
        if (mChannelRepository.getChannelCount() == 0) {
            showLoadingOverlay();
        }
        mChannelRepository.reload();

        // Set up audio manager for volume control
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Register for remote configuration changes unconditionally: the user can switch the web
        // server on from Settings later, and without a listener those edits would only take
        // effect on the next launch.
        ConfigWebServer.setConfigChangeListener(new ConfigWebServer.OnConfigChangeListener() {
            @Override
            public void onM3uUrlChanged(String newUrl) {
                Log.d(TAG, "M3U URL changed, reloading channels: " + newUrl);
                mChannelRepository.reload();
            }

            @Override
            public void onEpgUrlChanged(String newUrl) {
                Log.d(TAG, "EPG URL changed, reloading EPG: " + newUrl);
                mLastEpgConfigSignature = epgConfigSignature();
                mEpgManager.onEpgSourceChanged();
                // Per-channel sources load lazily, so ask for the channel on screen right
                // away instead of leaving its info bar empty until the user navigates.
                Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
                if (current != null) {
                    mEpgManager.loadEpg(current);
                }
            }

            @Override
            public void onEpgPreferenceChanged(String preference) {
                Log.d(TAG, "EPG priority changed, reloading EPG: " + preference);
                mLastEpgConfigSignature = epgConfigSignature();
                mEpgManager.onEpgSourceChanged();
                Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
                if (current != null) {
                    mEpgManager.loadEpg(current);
                }
            }

            @Override
            public void onM3uFileChanged(String newPath) {
                Log.d(TAG, "Local M3U file changed, reloading channels: " + newPath);
                mLastM3uConfigSignature = m3uConfigSignature();
                mChannelRepository.reload();
            }

            @Override
            public void onSourceSpeedChanged(boolean enabled) {
                Log.d(TAG, "Source probing " + (enabled ? "enabled" : "disabled"));
                // Turning it off stops the work in flight; the results it already gathered stay.
                if (!enabled) {
                    SourceSpeedTester.cancelBackground();
                }
            }

            @Override
            public void onSourceRemeasureRequested() {
                Log.d(TAG, "Source remeasure requested from the web page");
                java.util.List<Channel> allChannels = mChannelRepository.getAllChannels();
                if (allChannels == null || allChannels.isEmpty()) {
                    return;
                }
                // Measuring what is already known as well, since a source that died since it was
                // last measured is exactly what a manual re-measure is for.
                SourceSpeedStore.clear();
                startSourceProbe(new ArrayList<>(allChannels));
            }

            @Override
            public void onMediaCacheChanged(boolean enabled, long maxBytes) {
                Log.d(TAG, "Media cache " + (enabled ? "on" : "off") + " max=" + maxBytes);
                xyz.doikki.videoplayer.exo.ExoMediaSourceHelper helper =
                        xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.getInstance(
                                getApplicationContext());
                helper.setCacheEnabled(enabled);
                helper.setCacheMaxBytes(maxBytes);
            }

            @Override
            public void onMediaCacheCleared() {
                Log.d(TAG, "Media cache cleared from the web page");
                xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.getInstance(
                        getApplicationContext()).clearCache();
            }

            @Override
            public void onVideoQualityChanged(int tier) {
                Log.d(TAG, "Video quality changed to tier " + tier);
                mVideoView.setMaxVideoHeight(VideoQuality.maxHeight(tier));
            }
        });
        if (AppConfig.isWebServerEnabled()) {
            ConfigWebServer.startServer(this);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // While the search box is open, the remote's letter keys type into it instead of
        // navigating. This is checked before key mapping because the letters a viewer needs -
        // "hnws" for 湖南卫视 - are not mapped to anything, and would otherwise fall through to
        // the system unmapped.
        if (handleSearchKey(event)) {
            return true;
        }
        KeyMapping.Action action = KeyMapping.resolve(event.getKeyCode());
        if (action == null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "No action mapped for keycode: " + event.getKeyCode());
            }
            return super.dispatchKeyEvent(event);
        }
        // OK is the only key whose short and long press differ, so it is the only one that has to
        // see the whole press-and-release sequence instead of just the press.
        if (action == KeyMapping.Action.OK) {
            return handleOkKey(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        Log.d(TAG, "Key pressed: " + event.getKeyCode() + " ("
                + KeyEvent.keyCodeToString(event.getKeyCode()) + "), action: " + action);

        switch (action) {
            case CHANNEL_UP:
                handleChannelUp();
                return true;
            case CHANNEL_DOWN:
                handleChannelDown();
                return true;
            case VOLUME_UP:
                handleVolumeUp();
                return true;
            case VOLUME_DOWN:
                handleVolumeDown();
                return true;
            case LEFT:
                handleFocusLeft();
                return true;
            case RIGHT:
                handleFocusRight();
                return true;
            case MENU:
                handleMenu();
                return true;
            case BACK:
                handleBack();
                return true;
            case SOURCE_SWITCH:
                handleSourceSwitch();
                return true;
            case SCREEN_SCALE:
                handleScreenScale();
                return true;
            case QUALITY:
                handleQuality();
                return true;
            case SEARCH:
                handleSearch();
                return true;
            default:
                int digit = digitFor(action);
                if (digit >= 0) {
                    handleNumberKey(digit);
                    return true;
                }
                return super.dispatchKeyEvent(event);
        }
    }

    /**
     * Routes keys into the channel search while its box is open.
     *
     * <p>The alphabet is taken from the key events' Unicode character rather than from a table of
     * key codes, so a remote that reports letters, digits, and even a soft keyboard on a phone all
     * work through the same path.
     *
     * @return true when the key was consumed by the search
     */
    private boolean handleSearchKey(KeyEvent event) {
        if (mChannelListComponent == null
                || !mChannelListVisible
                || !mChannelListComponent.isSearchActive()) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            // Still consume the release: letting it through would reach a view that has no idea
            // the press ever happened.
            return true;
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            mChannelListComponent.closeSearch();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            mChannelListComponent.deleteSearchCharacter();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            mChannelListComponent.moveSearchSelection(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            mChannelListComponent.moveSearchSelection(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            // OK tunes whatever the search highlights. Selecting out of a search is the whole
            // point of typing, so it does not require leaving the search first.
            mChannelListComponent.confirmSelection();
            hideChannelList();
            mChannelListVisible = false;
            return true;
        }
        int typed = event.getUnicodeChar();
        if (typed != 0 && !Character.isISOControl(typed)) {
            mChannelListComponent.appendSearchCharacter((char) typed);
            return true;
        }
        // Arrows and anything else: navigate the list rather than the search.
        return false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ImmersiveMode.onWindowFocusChanged(this, hasFocus);
    }

    /**
     * Cycles the picture-quality ceiling.
     *
     * <p>Most of these stations send one rendition and the tiers change nothing, which is why this
     * is a keystroke and not a menu: it costs one press to find out, and the ceiling still helps
     * on the sources that do carry several, where adapting down keeps a weak line watchable
     * instead of sputtering.
     */
    private void handleQuality() {
        int current = AppConfig.getVideoQuality();
        int next = VideoQuality.next(current);
        AppConfig.setVideoQuality(next);
        mVideoView.setMaxVideoHeight(VideoQuality.maxHeight(next));
        android.widget.Toast.makeText(this, qualityLabel(next),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private int qualityLabel(int tier) {
        switch (tier) {
            case VideoQuality.TIER_1080P:
                return R.string.quality_1080p;
            case VideoQuality.TIER_720P:
                return R.string.quality_720p;
            case VideoQuality.TIER_480P:
                return R.string.quality_480p;
            default:
                return R.string.quality_auto;
        }
    }

    /**
     * Cycles how the picture is fitted to the screen.
     *
     * <p>Every fit is a trade, and which one is right depends on the station and on who is
     * watching, so they are all one button apart rather than picked once in a settings screen:
     * stretching fills the screen and costs a little shape, cropping fills it without distortion
     * and costs the edges, and the ratio fits keep the shape at the cost of black bars.
     */
    private void handleScreenScale() {
        int current = AppConfig.getScreenScaleType();
        int index = -1;
        for (int i = 0; i < SCREEN_SCALES.length; i++) {
            if (SCREEN_SCALES[i] == current) {
                index = i;
                break;
            }
        }
        // A stored value outside the list falls through to the first entry.
        int next = SCREEN_SCALES[(index + 1) % SCREEN_SCALES.length];
        AppConfig.setScreenScaleType(next);
        mVideoView.setScreenScaleType(next);
        android.widget.Toast.makeText(this, scaleLabel(next),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private int scaleLabel(int scaleType) {
        switch (scaleType) {
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                return R.string.scale_fill;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                return R.string.scale_crop;
            case VideoView.SCREEN_SCALE_16_9:
                return R.string.scale_16_9;
            case VideoView.SCREEN_SCALE_4_3:
                return R.string.scale_4_3;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                return R.string.scale_original;
            default:
                return R.string.scale_fit;
        }
    }

    /**
     * Cycles through the channel's own source URLs, the same list the automatic failover
     * walks on error. A no-op for channels with a single source.
     */
    private void handleSourceSwitch() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || channel.urlCount() < 2) {
            if (channel != null) {
                android.widget.Toast.makeText(this, R.string.source_only_one,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        mCurrentUrlIndex = (mCurrentUrlIndex + 1) % channel.urlCount();
        String url = channel.urlAt(mCurrentUrlIndex);
        Log.i(TAG, "Manual source switch: " + channel.name + " → source "
                + (mCurrentUrlIndex + 1) + "/" + channel.urlCount());
        android.widget.Toast.makeText(this,
                getString(R.string.source_switched, channel.name,
                        mCurrentUrlIndex + 1, channel.urlCount()),
                android.widget.Toast.LENGTH_SHORT).show();
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        // The new source is a live stream, so leave any 回看 window behind.
        if (mCatchupActive) {
            mCatchupActive = false;
            mCatchupChannel = null;
            mCatchupProgram = null;
            mCatchupPlayheadMs = 0;
        }
        cancelReconnect();
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, channel.headers);
            mVideoView.start();
            refreshTimeShiftBar();
        });
    }

    /** Tap on the video: toggle the control overlay (or the channel list if it is open). */
    private void handleVideoTap() {
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
            return;
        }
        if (mTimeShiftBar.getVisibility() == View.VISIBLE) {
            mTimeShiftBar.hideSmooth();
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel != null && mPlaybackInfoComponent != null) {
            mPlaybackInfoComponent.showChannelInfo(channel);
        }
        refreshTimeShiftBar(true);
    }

    /**
     * Fling on the video. Horizontal maps to LEFT/RIGHT (day paging inside the programme
     * column, catchup seeking full screen); vertical changes channel (or ignores the fling
     * while the list is open, where the list itself scrolls).
     */
    private void handleVideoFling(boolean horizontal, boolean positiveX, boolean upwardY) {
        if (horizontal) {
            if (positiveX) {
                handleFocusRight();
            } else {
                handleFocusLeft();
            }
            return;
        }
        if (mChannelListVisible) {
            return; // the channel list RecyclerViews own vertical swipes
        }
        if (upwardY) {
            handleChannelUp();
        } else {
            handleChannelDown();
        }
    }

    /**
     * Commit a time-shift bar drag. In catchup the window is the replay window itself; in
     * live playback dragging back enters 回看 at that time, and dropping near "now" stays live.
     */
    private void handleTimeShiftSeek(long targetMs) {
        long now = System.currentTimeMillis();
        if (mCatchupActive) {
            seekCatchupTo(targetMs);
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || !channel.hasCatchup()) {
            mTimeShiftBar.hide();
            return;
        }
        if (targetMs >= now - TIMESHIFT_LIVE_MARGIN_MS) {
            // Let go at the live edge: stay on live TV, nothing to rewind.
            mTimeShiftBar.setWindow(timeShiftWindowStart(channel), now, now);
            return;
        }
        enterCatchupFromLive(channel, targetMs);
    }

    /** Rewinds straight from the live bar to the given time (回看 at that moment). */
    private void enterCatchupFromLive(Channel channel, long targetMs) {
        long now = System.currentTimeMillis();
        long end = now + CATCHUP_LIVE_TAIL_MS;
        EpgManager.Program program = null;
        if (targetMs >= timeShiftWindowStart(channel)) {
            EpgManager.Program current = mEpgManager.getCurrentProgram(channel);
            if (current != null && targetMs >= current.startTime) {
                program = current;
                // Past programmes end at their stop time; only still-airing ones keep a tail.
                end = catchupWindowEnd(program, now);
            }
        }
        if (program == null) {
            // No EPG: replay a fixed window from the drop point.
            program = new EpgManager.Program(channel.name, targetMs, end);
        }
        startCatchup(channel, targetMs, end, program);
    }

    /** The left edge of the live time-shift window: today's programme or a fixed lookback. */
    private long timeShiftWindowStart(Channel channel) {
        long now = System.currentTimeMillis();
        EpgManager.Program current = mEpgManager.getCurrentProgram(channel);
        if (current != null && current.startTime > 0 && current.startTime < now) {
            return current.startTime;
        }
        return now - TIMESHIFT_NO_EPG_WINDOW_MS;
    }

    /** Keeps the live thumb glued to "now" while the bar is open. */
    private void tickTimeShiftBar() {
        if (mTimeShiftBar != null && mTimeShiftBar.getVisibility() == View.VISIBLE) {
            mTimeShiftBar.tickIfIdle();
            mMainHandler.postDelayed(mTimeShiftTicker, 1000);
        }
    }

    private void startTimeShiftTicker() {
        mMainHandler.removeCallbacks(mTimeShiftTicker);
        mMainHandler.postDelayed(mTimeShiftTicker, 1000);
    }

    /** Repaints the time-shift bar for the current playback position, showing it when useful. */
    private void refreshTimeShiftBar() {
        refreshTimeShiftBar(false);
    }

    /**
     * @param forceShow live mode only: the bar normally stays hidden until the user taps the
     *                  video, so it never sits over TV playback uninvited. Catchup always shows
     *                  it because the drag IS the way to seek a replay.
     */
    private void refreshTimeShiftBar(boolean forceShow) {
        if (mTimeShiftBar == null) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            mTimeShiftBar.hide();
            return;
        }
        long now = System.currentTimeMillis();
        if (mCatchupActive && mCatchupProgram != null) {
            long end = catchupWindowEnd(mCatchupProgram, now);
            mTimeShiftBar.setChannelLabel(channel.name);
            mTimeShiftBar.setBackToLiveVisible(true);
            mTimeShiftBar.setWindow(mCatchupProgram.startTime, end, mCatchupPlayheadMs);
            mTimeShiftBar.show();
            startTimeShiftTicker();
            return;
        }
        if (!channel.hasCatchup()) {
            // No server-side replay: fall back to the local shift, which works on any source the
            // player can seek within. The bar then shows how far behind live the viewer is.
            if (mLocalShiftActive) {
                long behind = localShiftBehindMs();
                mTimeShiftBar.setChannelLabel(channel.name);
                mTimeShiftBar.setBackToLiveVisible(true);
                mTimeShiftBar.setWindow(now - LOCAL_SHIFT_WINDOW_MS, now, now - behind);
                if (forceShow) {
                    mTimeShiftBar.show();
                    startTimeShiftTicker();
                }
            } else {
                mTimeShiftBar.hide();
            }
            return;
        }
        // Live: the window runs from the programme start to now.
        long start = timeShiftWindowStart(channel);
        mTimeShiftBar.setChannelLabel(channel.name);
        mTimeShiftBar.setBackToLiveVisible(false);
        mTimeShiftBar.setWindow(start, now, now);
        if (forceShow) {
            mTimeShiftBar.show();
            startTimeShiftTicker();
        }
    }

    private void stopTimeShiftTicker() {
        mMainHandler.removeCallbacks(mTimeShiftTicker);
    }

    /**
     * Splits OK into its two meanings: released quickly it selects, held down it favourites. The
     * short press has to wait for the release, otherwise both would fire on the same press.
     */
    private boolean handleOkKey(KeyEvent event) {
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    mOkLongPressFired = false;
                    mMainHandler.removeCallbacks(mOkLongPressRunnable);
                    mMainHandler.postDelayed(mOkLongPressRunnable, OK_LONG_PRESS_MS);
                } else if (event.isLongPress()) {
                    // Remotes that report the long press themselves are believed straight away
                    onOkLongPress();
                }
                return true;
            case KeyEvent.ACTION_UP:
                mMainHandler.removeCallbacks(mOkLongPressRunnable);
                if (!mOkLongPressFired) {
                    handleOk();
                }
                mOkLongPressFired = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * Long-press OK has two meanings depending on where the cursor is: over a programme it
     * replays (回看), over a channel it toggles the favourite star, and on the full-screen
     * player it replays the programme currently on air.
     */
    private void onOkLongPress() {
        if (mOkLongPressFired) {
            return;
        }
        mOkLongPressFired = true;
        mMainHandler.removeCallbacks(mOkLongPressRunnable);

        if (mChannelListVisible && mChannelListComponent != null) {
            if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                Channel channel = mChannelListComponent.getSelectedChannel();
                EpgManager.Program program = mChannelListComponent.getSelectedProgram();
                if (channel == null || program == null) {
                    android.widget.Toast.makeText(this, R.string.catchup_no_program,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                // The replayed programme belongs to the highlighted channel, which becomes the
                // current one: BACK from catchup must land on *its* live stream, not the one
                // that was playing before the list opened.
                mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                playCatchup(channel, program);
                return;
            }
            // Channel / group column: long-press OK manages favourites.
            Channel target = mChannelListComponent.getSelectedChannel();
            if (target != null) {
                toggleFavorite(target);
            }
            return;
        }

        // Full-screen playback: long-press OK replays the programme currently on air.
        Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (current == null) {
            return;
        }
        EpgManager.Program program = mEpgManager.getCurrentProgram(current);
        if (program == null) {
            android.widget.Toast.makeText(this, R.string.catchup_no_program,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        playCatchup(current, program);
    }

    private void toggleFavorite(Channel target) {
        boolean nowFavorite = mNavigator.toggleFavorite(target);
        Log.i(TAG, (nowFavorite ? "Favourited: " : "Unfavourited: ") + target.name);
        if (mChannelListComponent != null) {
            mChannelListComponent.onFavoritesChanged();
        }
        android.widget.Toast.makeText(this,
                getString(nowFavorite ? R.string.favorite_added : R.string.favorite_removed,
                        target.name),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Starts time-shifted playback of the given programme, or explains why it cannot. */
    private void playCatchup(Channel channel, EpgManager.Program program) {
        if (channel == null || program == null) {
            android.widget.Toast.makeText(this, R.string.catchup_no_program,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!channel.hasCatchup()) {
            android.widget.Toast.makeText(this, R.string.catchup_not_available,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        long start = program.startTime;
        if (start >= now) {
            start = now - CATCHUP_SEEK_STEP_MS; // never ask for a window that starts in the future
        }
        startCatchup(channel, start, catchupWindowEnd(program, now), program);
    }

    /**
     * Live-mode LEFT enters 回看: replay the current programme from its start, or rewind a fixed
     * amount when no EPG data is known. No-op on channels without catchup.
     */
    private void enterCatchupFromLive() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || !channel.hasCatchup()) {
            return;
        }
        EpgManager.Program program = mEpgManager.getCurrentProgram(channel);
        if (program != null) {
            playCatchup(channel, program);
            return;
        }
        // No EPG: rewind a fixed amount from "now" so the viewer can still step back.
        long now = System.currentTimeMillis();
        EpgManager.Program synthetic = new EpgManager.Program(
                channel.name, now - CATCHUP_REWIND_MS, now + CATCHUP_LIVE_TAIL_MS);
        startCatchup(channel, synthetic.startTime, synthetic.endTime, synthetic);
    }

    /** Common catchup start: build the playseek URL, arm the state, switch the player. */
    private void startCatchup(Channel channel, long startMs, long endMs, EpgManager.Program program) {
        String url = CatchupUrlBuilder.buildUrl(channel, startMs, endMs);
        if (url == null) {
            android.widget.Toast.makeText(this, R.string.catchup_not_available,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        cancelReconnect();
        mCatchupActive = true;
        mCatchupChannel = channel;
        mCatchupProgram = program;
        mCatchupPlayheadMs = startMs;
        mCurrentUrlIndex = 0;
        // The guide now belongs to the replayed programme, not to whatever airs now.
        syncGuideFocusTime();
        // Leave the list so the replayed stream is visible; short OK reopens it.
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        Log.i(TAG, "Catchup: " + channel.name + " · " + program.title + " → " + url);
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, channel.headers);
            mVideoView.start();
            if (mChannelListComponent != null) {
                mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            }
        });
        if (mPlaybackInfoComponent != null) {
            mPlaybackInfoComponent.showChannelInfo(channel);
        }
        refreshTimeShiftBar();
        android.widget.Toast.makeText(this,
                getString(R.string.catchup_started, program.title),
                android.widget.Toast.LENGTH_SHORT).show();
        mEpgManager.loadEpg(channel);
    }

    /** The end of the playseek window: a past programme ends at its stop time, a live one in the
     *  near future so the stream keeps flowing once it catches up. */
    private long catchupWindowEnd(EpgManager.Program program, long now) {
        return program.endTime > now ? now + CATCHUP_LIVE_TAIL_MS : program.endTime;
    }

    /** Rewinds or fast-forwards the catchup playhead by rebuilding the playseek window. */
    private void seekCatchup(long deltaMs) {
        if (!mCatchupActive || mCatchupChannel == null || mCatchupProgram == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long target = Math.max(mCatchupProgram.startTime,
                Math.min(now - CATCHUP_SEEK_STEP_MS, mCatchupPlayheadMs + deltaMs));
        seekCatchupTo(target);
    }

    /** Seeks the catchup playhead to an absolute position within the programme window. */
    private void seekCatchupTo(long targetMs) {
        if (!mCatchupActive || mCatchupChannel == null || mCatchupProgram == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long minPlayhead = mCatchupProgram.startTime;
        long maxPlayhead = now - CATCHUP_SEEK_STEP_MS;
        long target = Math.max(minPlayhead, Math.min(targetMs, maxPlayhead));
        if (target == mCatchupPlayheadMs) {
            return;
        }
        mCatchupPlayheadMs = target;
        String url = CatchupUrlBuilder.buildUrl(mCatchupChannel, mCatchupPlayheadMs,
                catchupWindowEnd(mCatchupProgram, now));
        if (url == null) {
            return;
        }
        Log.i(TAG, "Catchup seek to +" + ((mCatchupPlayheadMs - mCatchupProgram.startTime) / 1000) + "s");
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, mCatchupChannel.headers);
            refreshTimeShiftBar();
        });
        showCatchupSeekIndicator((mCatchupPlayheadMs - mCatchupProgram.startTime) / 1000);
    }

    private void showCatchupSeekIndicator(long offsetSec) {
        String label = (offsetSec >= 0 ? "+" : "-") + Math.abs(offsetSec) + "s";
        mChannelNumberView.showDigits(label, mCatchupChannel.name);
        mMainHandler.removeCallbacks(mCatchupSeekHideRunnable);
        mMainHandler.postDelayed(mCatchupSeekHideRunnable, 1500);
    }

    private void hideCatchupSeekIndicator() {
        if (!isNumberInputActive()) {
            mChannelNumberView.hide();
        }
    }

    /** Leaves catchup and returns to the live stream of the channel being watched. */
    private void exitCatchupToLive() {
        if (!mCatchupActive) {
            return;
        }
        Log.i(TAG, "Exiting catchup, returning to live");
        mCatchupActive = false;
        mCatchupChannel = null;
        mCatchupProgram = null;
        mCatchupPlayheadMs = 0;
        syncGuideFocusTime();
        android.widget.Toast.makeText(this, R.string.catchup_exited,
                android.widget.Toast.LENGTH_SHORT).show();
        playChannel(mCurrentChannelIndex);
    }

    /** @return 0-9 for the number actions, or -1 for anything else. */
    private static int digitFor(KeyMapping.Action action) {
        int offset = action.ordinal() - KeyMapping.Action.NUM_0.ordinal();
        return offset >= 0 && offset <= 9 ? offset : -1;
    }

    /**
     * Collects the digits typed on the remote and tunes to that channel number once the user
     * stops typing, the way a TV does. Numbers are positions in the channel list, counted from 1.
     */
    private void handleNumberKey(int digit) {
        if (mNumberInput.length() >= MAX_CHANNEL_DIGITS) {
            mNumberInput.setLength(0);
        }
        mNumberInput.append(digit);
        Channel target = channelForNumber(numberOf(mNumberInput));
        mChannelNumberView.showDigits(mNumberInput.toString(), target != null ? target.name : null);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        mMainHandler.postDelayed(mNumberCommitRunnable, NUMBER_INPUT_TIMEOUT_MS);
    }

    private void commitNumberInput() {
        int number = numberOf(mNumberInput);
        cancelNumberInput();
        Channel target = channelForNumber(number);
        if (target == null) {
            Log.d(TAG, "No channel at number " + number);
            return;
        }
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        mCurrentChannelIndex = number - 1;
        // Channel numbers count through the whole playlist, so the one just typed may well be
        // outside the group being walked; the channel keys carry on from wherever it landed.
        mNavigator.setCurrentGroupIndex(mNavigator.homeGroupFor(mCurrentChannelIndex));
        playChannel(mCurrentChannelIndex);
    }

    private void cancelNumberInput() {
        mNumberInput.setLength(0);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        if (mChannelNumberView != null) {
            mChannelNumberView.hide();
        }
    }

    private boolean isNumberInputActive() {
        return mNumberInput.length() > 0;
    }

    private Channel channelForNumber(int number) {
        return number >= 1 ? mChannelRepository.getChannel(number - 1) : null;
    }

    private static int numberOf(CharSequence digits) {
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Identity of the EPG settings the user controls directly (URL + priority). */
    private static String epgConfigSignature() {
        return AppConfig.getEpgUrl() + "\n" + AppConfig.getEpgPreference();
    }

    /** Identity of the M3U source (URL + local file path). */
    private static String m3uConfigSignature() {
        return AppConfig.getM3uUrl() + "\n" + AppConfig.getM3uFilePath();
    }

    private void handleChannelUp() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        Log.d(TAG, "handleChannelUp: mChannelListVisible=" + mChannelListVisible);

        if (mChannelListVisible && mChannelListComponent != null) {
            moveSelectionInList(false);
        } else {
            // No channel list visible: change channel (UP = next channel)
            tuneWithinCurrentGroup(1);
        }
    }

    private void handleChannelDown() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        if (mChannelListVisible && mChannelListComponent != null) {
            moveSelectionInList(true);
        } else {
            // No channel list visible: change channel (DOWN = previous channel)
            tuneWithinCurrentGroup(-1);
        }
    }

    /** Up and down move the cursor inside whichever of the three columns has the focus. */
    private void moveSelectionInList(boolean down) {
        switch (mChannelListComponent.getFocusPanel()) {
            case PROGRAMS:
                if (!mChannelListComponent.moveProgramSelection(down)) {
                    // The cursor ran off an end of the day: page the guide that way, which is how
                    // the viewer reaches earlier days now that LEFT belongs to the column layout.
                    if (mChannelListComponent.moveProgramDay(down ? 1 : -1)) {
                        Log.d(TAG, "moveSelectionInList: paged the guide to another day");
                        // Land where the movement was heading: stepping up past the top of a day
                        // continues at the bottom of the previous one, and the other way round.
                        mChannelListComponent.selectProgramEdge(down);
                    }
                }
                break;
            case GROUPS:
                mChannelListComponent.moveGroupSelection(down);
                break;
            default:
                mChannelListComponent.moveChannelSelection(down);
                break;
        }
    }

    /**
     * Tunes one channel along the group the viewer chose, wrapping at its ends. Leaving the group
     * takes opening the list or typing a number, so stepping through a group of favourites does not
     * drop the viewer into an unrelated part of the playlist.
     */
    private void tuneWithinCurrentGroup(int delta) {
        int next = mNavigator.step(mNavigator.currentGroupIndex(), mCurrentChannelIndex, delta);
        Log.d(TAG, "tuneWithinCurrentGroup: group=" + mNavigator.currentGroupIndex()
                + ", from=" + mCurrentChannelIndex + ", to=" + next);
        if (next < 0) {
            return;
        }
        mCurrentChannelIndex = next;
        playChannel(mCurrentChannelIndex);
    }

    private void handleVolumeUp() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    private void handleVolumeDown() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    /**
     * Whether the player is holding enough of the stream to rewind within it. Most public sources
     * offer no server-side replay, so this is the only timeshift most channels will ever have.
     */
    private boolean isLocalShiftAvailable() {
        return mVideoView != null && mVideoView.getBufferedPosition() > 0
                && mVideoView.getCurrentPosition() > 0;
    }

    /** Rewinds within what the player has buffered, entering the local shift if not already in it. */
    private void rewindLocally() {
        if (!isLocalShiftAvailable()) {
            return;
        }
        if (!mLocalShiftActive) {
            mLocalShiftActive = true;
            mShiftTargetMs = mVideoView.getCurrentPosition();
        }
        // Rewinding past the start of what we hold would just stall at the beginning, so the
        // oldest reachable moment is the floor.
        long floor = 0;
        mShiftTargetMs = Math.max(floor, mShiftTargetMs - LOCAL_SHIFT_STEP_MS);
        mVideoView.seekTo(mShiftTargetMs);
        refreshTimeShiftBar(true);
    }

    /** Steps back towards the live edge, and leaves the local shift when it catches up. */
    private void fastForwardLocally() {
        if (!mLocalShiftActive || mVideoView == null) {
            return;
        }
        long liveEdge = mVideoView.getBufferedPosition();
        mShiftTargetMs += LOCAL_SHIFT_STEP_MS;
        if (mShiftTargetMs >= liveEdge - LOCAL_LIVE_EDGE_MARGIN_MS) {
            exitLocalShiftToLive();
            return;
        }
        mVideoView.seekTo(mShiftTargetMs);
        refreshTimeShiftBar(true);
    }

    /** Drops the local shift and jumps to the live edge. */
    private void exitLocalShiftToLive() {
        mLocalShiftActive = false;
        mShiftTargetMs = 0;
        if (mVideoView != null) {
            mVideoView.seekTo(mVideoView.getBufferedPosition());
        }
        refreshTimeShiftBar();
        if (mTimeShiftBar != null) {
            mTimeShiftBar.hideSmooth();
        }
    }

    /** How far behind the live edge the local shift currently is, in milliseconds. */
    private long localShiftBehindMs() {
        if (!mLocalShiftActive || mVideoView == null) {
            return 0;
        }
        return Math.max(0, mVideoView.getBufferedPosition() - mShiftTargetMs);
    }

    /** Left steps back through the three columns: programmes, channels, groups. */
    private void handleFocusLeft() {
        if (!mChannelListVisible || mChannelListComponent == null) {
            // Full-screen LEFT rewinds the catchup playhead; in live playback it enters 回看 so
            // the viewer can rewind a programme they missed the start of.
            if (mCatchupActive) {
                seekCatchup(-CATCHUP_SEEK_STEP_MS);
            } else if (isLocalShiftAvailable()) {
                // This channel has no server-side replay, so rewind what the player already
                // holds instead. The segment cache is what makes it instant.
                rewindLocally();
            } else {
                enterCatchupFromLive();
            }
            return;
        }
        // Inside the programme column, LEFT steps back a day. When there is no earlier day (most
        // feeds carry today alone) it folds back to the channels instead of doing nothing, so the
        // key is never a dead end; the ends of the list page the days as well (moveSelectionInList).
        if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
            if (!mChannelListComponent.moveProgramDay(-1)) {
                Log.d(TAG, "handleFocusLeft: no earlier day, folding back to channels");
                mChannelListComponent.moveFocusToChannels();
            }
            return;
        }
        Log.d(TAG, "handleFocusLeft: Moving focus to groups");
        mChannelListComponent.moveFocusToGroups();
    }

    /** Right steps forward through the three columns: groups, channels, programmes. */
    private void handleFocusRight() {
        if (!mChannelListVisible || mChannelListComponent == null) {
            // Full-screen RIGHT fast-forwards the catchup playhead, or closes a local rewind
            // towards the live edge, whichever is in progress.
            if (mCatchupActive) {
                seekCatchup(CATCHUP_SEEK_STEP_MS);
            } else if (mLocalShiftActive) {
                fastForwardLocally();
            }
            return;
        }
        switch (mChannelListComponent.getFocusPanel()) {
            case GROUPS:
                Log.d(TAG, "handleFocusRight: Moving focus to channels");
                mChannelListComponent.moveFocusToChannels();
                break;
            case PROGRAMS:
                // Inside the programme column, RIGHT pages the guide one day forward.
                mChannelListComponent.moveProgramDay(1);
                break;
            default:
                Log.d(TAG, "handleFocusRight: Moving focus to programs");
                mChannelListComponent.moveFocusToPrograms();
                break;
        }
    }

    private void handleOk() {
        Log.d(TAG, "handleOk called, mChannelListVisible=" + mChannelListVisible);
        if (mChannelListVisible) {
            if (mChannelListComponent != null) {
                if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS) {
                    // OK on a group means "let me pick from this one", not "play something"
                    mChannelListComponent.moveFocusToChannels();
                    return;
                }
                if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                    // OK on a programme replays it (回看); this is the natural "select" action, so
                    // it must not play the live channel the way a channel-row click does.
                    Channel channel = mChannelListComponent.getSelectedChannel();
                    EpgManager.Program program = mChannelListComponent.getSelectedProgram();
                    if (channel != null && program != null && channel.hasCatchup()) {
                        // Remember the highlighted channel as current so exiting the replay lands
                        // on this channel's live stream, not the previously playing one.
                        mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                        mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                        playCatchup(channel, program);
                        return;
                    }
                    // No catchup or no programme: fall through so OK still does something useful.
                }
                // The group the channel was picked from is the one the channel keys walk from now on
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                mChannelListComponent.confirmSelection();
            }
            hideChannelList();
            mChannelListVisible = false;
            Log.d(TAG, "handleOk - hiding list");
        } else {
            // Show channel list for selection
            mChannelListVisible = true;
            showChannelList();
            Log.d(TAG, "handleOk - showing list, mChannelListVisible now=" + mChannelListVisible);
        }
    }

    /**
     * Tells the guide which moment it should land on: now while watching live, or the programme
     * being replayed during catchup. Without this the guide always aimed at the current time, so
     * opening it during a replay pointed at a programme the viewer was not watching.
     */
    private void syncGuideFocusTime() {
        if (mChannelListComponent == null) {
            return;
        }
        long focusTime = (mCatchupActive && mCatchupProgram != null)
                ? mCatchupProgram.startTime : System.currentTimeMillis();
        mChannelListComponent.setProgramFocusTime(focusTime);
    }

    /**
     * The group the channel list should open on.
     *
     * @param browsedGroup            the group last browsed in the list, or -1 for none
     * @param browsedHoldsPlayingChan whether that group still contains the channel being watched
     * @param walkingGroup            the group the channel up/down keys are stepping through
     */
    static int groupToOpenOn(int browsedGroup, boolean browsedHoldsPlayingChan, int walkingGroup) {
        return browsedGroup >= 0 && browsedHoldsPlayingChan ? browsedGroup : walkingGroup;
    }

    private void showChannelList() {
        if (mChannelListComponent == null) {
            mChannelListComponent = new ChannelListComponent(this, mNavigator);
            mChannelListComponent.setEpgManager(mEpgManager);

            // Set channel selection listener
            mChannelListComponent.setOnChannelSelectedListener((index, channel) -> {
                mCurrentChannelIndex = index;
                playChannel(index);
                // A row tap is a direct command: close the list, tune and play.
                if (mChannelListVisible) {
                    hideChannelList();
                    mChannelListVisible = false;
                }
            });
            // The gear in the list header opens the settings; MENU leads into the list rather
            // than into the form, so this is how the form is still one press from the remote.
            mChannelListComponent.setOnSettingsRequestedListener(this::openSettingsFromMenu);
            // Tapping a programme row replays it (回看), the way long-pressing OK does.
            mChannelListComponent.setOnProgramClickListener((channel, program) -> {
                if (channel == null || program == null || !channel.hasCatchup()) {
                    android.widget.Toast.makeText(this, R.string.catchup_not_available,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                playCatchup(channel, program);
            });

            // Add to VideoView's parent or create appropriate params
            if (mVideoView.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup parent = (android.view.ViewGroup) mVideoView.getParent();
                // The menu measures itself: its columns come and go, so a fixed width would leave
                // an empty stripe of background behind whichever column is folded away.
                if (parent instanceof FrameLayout) {
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
                    lp.gravity = android.view.Gravity.START;
                    mChannelListComponent.setLayoutParams(lp);
                } else {
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
                    mChannelListComponent.setLayoutParams(lp);
                }
                parent.addView(mChannelListComponent);
            }
        }
        // Always refresh when opening menu to show current channel and program
        if (mChannelListComponent != null) {
            // The viewer may have changed the opacity in the settings since the menu was built
            mChannelListComponent.applyOpacity();
            mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            // Reopen where the viewer left off: the group they last browsed while it still holds the
            // playing channel (so the highlight is there to see), otherwise the group the channel
            // keys are walking. Always using the walking group threw away a group the viewer had
            // deliberately picked — choosing 全部频道 and reopening dropped them back into CCTV.
            int browsedGroup = mChannelListComponent.getBrowsedGroupIndex();
            int groupToOpen = groupToOpenOn(browsedGroup,
                    browsedGroup >= 0 && mNavigator.positionOf(browsedGroup, mCurrentChannelIndex) >= 0,
                    mNavigator.currentGroupIndex());
            mChannelListComponent.showGroup(groupToOpen, mCurrentChannelIndex);
            mChannelListComponent.moveFocusToChannels();
            // Re-aim the guide every time the list opens: the programme that aired when the list
            // was last prepared may be over by now, which is what made the guide open on the wrong
            // programme (reported for both live and catchup).
            syncGuideFocusTime();
            mChannelListComponent.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void hideChannelList() {
        if (mChannelListComponent != null) {
            mChannelListComponent.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * Raises the loading cover, unless there is already something on screen worth watching.
     *
     * <p>A channel list already in memory means the app is only refreshing: the viewer keeps
     * watching while it happens, so covering the picture would be an interruption rather than a
     * courtesy.
     */
    private void showLoadingOverlay() {
        if (mLoadingOverlay == null || mDestroyed) {
            return;
        }
        if (mChannelRepository != null && mChannelRepository.getChannelCount() > 0) {
            mLoadingOverlay.setDetail(getString(R.string.loading_refreshing));
            return;
        }
        mAwaitingChannels = true;
        mLoadingOverlay.show(getString(R.string.loading_channels),
                getString(R.string.loading_channels_detail));
    }

    private void hideLoadingOverlay() {
        mAwaitingChannels = false;
        if (mLoadingOverlay != null) {
            mLoadingOverlay.hide();
        }
    }

    /**
     * MENU opens the channel list down the left of the screen, not the settings screen.
     *
     * <p>MENU is what a viewer presses looking for "the other channels": on a television remote
     * it is the channel-menu button, and dropping straight into a settings form is not what it
     * was pressed for. Settings is one press away inside the list itself, which is also where
     * groups, favourites and the EPG live - everything about what to watch, together.
     */
    private void handleMenu() {
        if (mChannelListVisible) {
            // A second press closes it, the way it opened it.
            hideChannelList();
            mChannelListVisible = false;
            return;
        }
        if (mChannelRepository.getChannelCount() == 0) {
            // Nothing to list yet: the download is what the viewer is waiting on, and settings is
            // where they can point the app at a different source if this one is not coming back.
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        mChannelListVisible = true;
        showChannelList();
    }

    /** Opens the settings screen from inside the menu, keeping the menu behind it. */
    private void openSettingsFromMenu() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * Opens the channel search, bringing the list up with it.
     *
     * <p>Search is reached from the remote as well as from inside the menu, so it has to be able
     * to open the menu itself: a viewer pressing SEARCH while watching expects a search box, not
     * an instruction to open the menu first.
     */
    private void handleSearch() {
        if (mChannelRepository.getChannelCount() == 0) {
            android.widget.Toast.makeText(this, R.string.search_nothing_loaded,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mChannelListVisible) {
            mChannelListVisible = true;
            showChannelList();
        }
        mChannelListComponent.requestSearch();
        android.widget.Toast.makeText(this, R.string.search_started,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void handleBack() {
        if (isNumberInputActive()) {
            // BACK abandons a half-typed channel number instead of leaving the app.
            cancelNumberInput();
        } else if (mChannelListVisible && mChannelListComponent != null
                && (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS
                || mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS)) {
            // The groups column was a detour, and so is the programmes (day) column: BACK folds
            // either away again rather than closing the menu.
            mChannelListComponent.moveFocusToChannels();
        } else if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        } else if (mCatchupActive) {
            exitCatchupToLive();
        } else {
            showExitConfirmDialog();
        }
    }

    /** Asks before leaving the player, with the confirm button focused by default. */
    private void showExitConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.exit_confirm, (d, which) -> finish())
                .setNegativeButton(R.string.exit_cancel, (d, which) -> d.dismiss())
                .create();
        dialog.show();
        // Default the focus to "同意退出" so a single OK confirms; LEFT/RIGHT moves to
        // 取消 and BACK dismisses the dialog without leaving the app.
        Button confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (confirm != null) {
            confirm.requestFocus();
        }
    }

    private void playChannel(int index) {
        Log.d(TAG, "playChannel called with index: " + index);
        // Switching channels (or returning to live) always leaves catchup.
        mCatchupActive = false;
        mCatchupChannel = null;
        mCatchupProgram = null;
        mCatchupPlayheadMs = 0;
        // A deliberate channel change cancels any pending reconnect for the previous channel
        cancelReconnect();
        Channel channel = mChannelRepository.getChannel(index);
        Log.d(TAG, "Got channel from repository: " + (channel != null ? channel.name : "null"));
        if (channel != null) {
            // A new channel always starts from its first source, but the sources were sorted
            // fastest-first first, so "first" means the one we know answers rather than the one
            // the playlist happened to list. Failover then walks down that same order.
            SourceSpeedTester.applyOrder(channel);
            mCurrentUrlIndex = 0;
            // A new stream is a new timeline: any position held from the last one is meaningless.
            mLocalShiftActive = false;
            mShiftTargetMs = 0;
            final String url = channel.url();
            Log.d(TAG, "Playing channel: " + channel.name + " - " + url
                    + " (" + channel.urlCount() + " source(s))");
            // Show bottom playback info bar
            runOnUiThread(() -> {
                mVideoView.switchUrl(url, channel.headers);
                mVideoView.start();
                if (mChannelListComponent != null) {
                    mChannelListComponent.setCurrentChannel(index);
                }
                if (mPlaybackInfoComponent != null) {
                    mPlaybackInfoComponent.showChannelInfo(channel);
                }
                refreshTimeShiftBar();
            });
            // Save current channel for resume on next app startup
            AppConfig.setLastChannelUrl(url);
            Log.d(TAG, "About to call mEpgManager.loadEpg for channel: " + channel.name);
            mEpgManager.loadEpg(channel);
        } else {
            Log.d(TAG, "Channel is null for index: " + index);
        }
    }

    private void handlePlayStateChanged(int playState) {
        Log.d(TAG, "Play state changed: " + playState);
        switch (playState) {
            case VideoView.STATE_PLAYING:
            case VideoView.STATE_BUFFERED:
                // Stream is alive again: drop the backoff and clear the message
                cancelReconnect();
                rememberGoodSource();
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                if (mReconnectAttempt == 0) {
                    // Don't overwrite the reconnect countdown with a plain "buffering"
                    mPlaybackStatusView.showMessage(getString(R.string.playback_buffering));
                }
                break;
            case VideoView.STATE_ERROR:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                if (mCatchupActive) {
                    // A catchup stream ended (a past programme finished) or broke: fall back to
                    // the live channel instead of hammering the catchup server.
                    exitCatchupToLive();
                    break;
                }
                if (mVideoView.getCurrentPlayState() == VideoView.STATE_ERROR
                        && mVideoView.getLastErrorReason()
                        == xyz.doikki.videoplayer.player.AbstractPlayer.PlayerEventListener.PLAYER_ERROR_BLOCKED) {
                    // A DRM-protected stream, or one that answered with an anti-piracy notice
                    // instead of the channel. Both play or refuse in a way no retry gets past -
                    // reconnecting to the same source five times over is exactly what the viewer
                    // is watching happen. Move on immediately.
                    Log.w(TAG, "Blocked source, skipping: " + describeCurrentSource());
                    markCurrentSourceDead();
                    if (tryNextUrl()) {
                        break;
                    }
                    // Every source is blocked or dead; worth one reconnect cycle in case it was
                    // a transient licence failure rather than a permanent block.
                }
                // Live streams should never complete; when they do, the source dropped us.
                // A dead source is worth replacing before waiting: try the channel's other
                // URLs first, and only back off once none of them work.
                if (!tryNextUrl()) {
                    scheduleReconnect();
                }
                break;
            default:
                break;
        }
    }

    /**
     * Moves to the next URL the playlist lists for this channel.
     *
     * @return true when another source was started, false when they have all been tried
     */
    /**
     * Probes the playlist's sources in the background, then reorders them so the next channel
     * change opens a source we know answers.
     *
     * <p>The reordering has to come back to the main thread: it mutates the lists the player and
     * the channel list are reading while the picture is up.
     */
    private void startSourceProbe(java.util.List<Channel> channels) {
        if (mDestroyed || channels == null) {
            return;
        }
        final java.util.List<Channel> target = new ArrayList<>(channels);
        SourceSpeedTester.measureChannelsInBackground(target, () -> {
            // On finish: re-rank every channel, then report back. The sorting itself stays on the
            // probe's worker thread - it touches hundreds of lists, and doing it here would be
            // the same main-thread stall the probe exists to avoid. What comes back to the main
            // thread is only the one thing that needs it: which source the channel on screen is
            // now pointing at.
            SourceSpeedTester.applyOrder(target);
            final Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
            if (current != null) {
                SourceSpeedTester.applyOrder(current);
            }
            mMainHandler.post(() -> {
                if (mDestroyed) {
                    return;
                }
                if (current != null) {
                    // The sort moved the entries; the source that is playing has moved with them,
                    // so the index failover walks from has to be corrected or it would point at
                    // whatever now sits in the old slot.
                    int index = current.getUrls().indexOf(mPlayingUrl);
                    if (index >= 0) {
                        mCurrentUrlIndex = index;
                    }
                }
                Log.i(TAG, "Source ranking updated from " + SourceSpeedStore.size() + " measurements");
            });
        });
    }

    /**
     * Remembers the source that just started playing, so the next launch can open on it.
     *
     * <p>Only called when the source has actually changed. A live stream raises the playing state
     * again every time it recovers from a stall, and recording it each time would rewrite
     * preferences - a synchronous write on a low-end box - in the middle of playback.
     */
    private void rememberGoodSource() {
        if (mPlayingUrl == null || mPlayingUrl.equals(mRememberedGoodSource)) {
            return;
        }
        mRememberedGoodSource = mPlayingUrl;
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel != null && mPlayingUrl.equals(channel.urlAt(mCurrentUrlIndex))) {
            // Only the channel's own sources are worth remembering. A catchup URL is built for a
            // past programme and would be stale by the next launch.
            SourceSpeedStore.markLastGood(mPlayingUrl);
        }
    }

    /** The source that is currently playing, for log lines. */
    private String describeCurrentSource() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return "none";
        }
        return channel.name + " @ " + channel.urlAt(mCurrentUrlIndex);
    }

    /**
     * Records that the current source is not merely slow but unusable, so it is not tried first
     * again on the next channel change.
     *
     * <p>Stored with the worst possible latency rather than being deleted: the prober skips
     * sources it already knows, so an entry is what stops this one being measured and ranked
     * straight back to the top. It is unambiguously a dead end, whatever its measured speed was.
     */
    private void markCurrentSourceDead() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return;
        }
        SourceSpeedStore.markUnusable(channel.urlAt(mCurrentUrlIndex));
    }

    private boolean tryNextUrl() {
        if (mDestroyed) {
            return false;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || mCurrentUrlIndex + 1 >= channel.urlCount()) {
            return false;
        }
        mCurrentUrlIndex++;
        String url = channel.urlAt(mCurrentUrlIndex);
        Log.w(TAG, "Source " + (mCurrentUrlIndex + 1) + "/" + channel.urlCount()
                + " for " + channel.name + ": " + url);
        mPlaybackStatusView.showMessage(getString(R.string.playback_trying_backup,
                channel.name, mCurrentUrlIndex + 1, channel.urlCount()));
        restartPlayer(channel, url);
        return true;
    }

    private void scheduleReconnect() {
        if (mDestroyed) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return;
        }
        if (mReconnectAttempt >= RECONNECT_DELAYS_MS.length) {
            Log.w(TAG, "Giving up on " + channel.name + " after " + mReconnectAttempt + " attempts");
            mPlaybackStatusView.showMessage(getString(R.string.playback_failed, channel.name));
            return;
        }
        long delay = RECONNECT_DELAYS_MS[mReconnectAttempt];
        mReconnectAttempt++;
        mPlaybackStatusView.showMessage(getString(R.string.playback_reconnecting,
                mReconnectAttempt, RECONNECT_DELAYS_MS.length));
        Log.w(TAG, "Scheduling reconnect " + mReconnectAttempt + "/" + RECONNECT_DELAYS_MS.length
                + " for " + channel.name + " in " + delay + "ms");
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void reconnectCurrentChannel() {
        if (mDestroyed || mVideoView == null) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return;
        }
        // Retry the source that failed, not the first one in the list.
        //
        // Resetting the index to 0 here used to look harmless - "start the list over" - and was
        // the reason a channel whose first source is dead never played at all. tryNextUrl() walks
        // forward from the current index, so after each failure the index moved on to a source
        // that worked; the reconnect then threw that progress away and pointed the player back at
        // the dead first entry. Every retry re-tested the same unreachable host while a dozen
        // usable sources sat untouched behind it, and the screen stayed black for the full
        // minute and a half of the backoff schedule.
        String url = channel.urlAt(mCurrentUrlIndex);
        Log.i(TAG, "Reconnect attempt " + mReconnectAttempt + " for " + channel.name
                + " (source " + (mCurrentUrlIndex + 1) + "/" + channel.urlCount() + ")");
        if (url == null) {
            // Nothing at this index: the list shrank under us, so start over.
            mCurrentUrlIndex = 0;
            url = channel.url();
        }
        restartPlayer(channel, url);
    }

    /**
     * Finds the refreshed list's entry for the channel that was playing.
     *
     * <p>The playlist is reloaded underneath the viewer on every launch, and the new copy is a
     * different set of objects in a possibly different order. Matching by name is what keeps the
     * picture on the same channel across that; position alone would not survive the reordering,
     * and the URL is exactly what changes when a source is swapped out.
     *
     * @param playing the channel that was on screen, or null when nothing was
     * @return the index of the same channel in the refreshed list, or 0 when it is gone
     */
    private int indexOfChannelWithSameName(Channel playing) {
        if (playing == null || playing.name == null) {
            return 0;
        }
        java.util.List<Channel> channels = mChannelRepository.getAllChannels();
        for (int i = 0; i < channels.size(); i++) {
            Channel candidate = channels.get(i);
            if (candidate != null && playing.name.equals(candidate.name)) {
                return i;
            }
        }
        Log.d(TAG, "Channel " + playing.name + " is gone from the refreshed playlist");
        return 0;
    }

    /** Rebuilds the player: after an error the existing instance cannot be reused. */
    private void restartPlayer(Channel channel, String url) {
        if (url == null) {
            return;
        }
        mPlayingUrl = url;
        mVideoView.release();
        mVideoView.setUrl(url, channel.headers);
        mVideoView.start();
    }

    private void cancelReconnect() {
        mReconnectAttempt = 0;
        mMainHandler.removeCallbacks(mReconnectRunnable);
        if (mPlaybackStatusView != null) {
            mPlaybackStatusView.hideMessage();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String m3uSignature = m3uConfigSignature();
        if (!m3uSignature.equals(mLastM3uConfigSignature)) {
            mLastM3uConfigSignature = m3uSignature;
            if (mChannelRepository != null) {
                Log.d(TAG, "M3U source changed in settings screen, reloading channels");
                mChannelRepository.reload();
            }
        }
        String signature = epgConfigSignature();
        if (!signature.equals(mLastEpgConfigSignature)) {
            mLastEpgConfigSignature = signature;
            if (mEpgManager != null) {
                Log.d(TAG, "EPG settings changed in settings screen, reloading");
                mEpgManager.onEpgSourceChanged();
            }
        }
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTimeShiftTicker();
        // Leaving mid-press must not turn into a favourite once we are no longer on screen
        mMainHandler.removeCallbacks(mOkLongPressRunnable);
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        // A probe still in flight would otherwise keep holding sockets open for an activity that
        // is going away; the results it already measured are saved regardless.
        SourceSpeedTester.cancelBackground();
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        mMainHandler.removeCallbacks(mOkLongPressRunnable);
        stopTimeShiftTicker();
        // The web server keeps its listener in a static field; leaving ours there would leak
        // this Activity for the whole process lifetime.
        ConfigWebServer.setConfigChangeListener(null);
        ConfigWebServer.stopServer();
        if (mChannelsChangedListener != null) {
            mChannelRepository.removeListener(mChannelsChangedListener);
            mChannelsChangedListener = null;
        }
        mChannelRepository.clearListeners();
        mEpgManager.shutdown();
        if (mVideoView != null) {
            mVideoView.release();
        }
        super.onDestroy();
    }

    private int dp2px(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
