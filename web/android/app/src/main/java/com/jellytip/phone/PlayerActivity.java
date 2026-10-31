package com.jellytip.phone;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.PlayerView;
import com.getcapacitor.JSObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen IPTV player (see {@link PlayerPlugin}). TS and HLS play natively; AC-3/E-AC-3 and other audio the
 * device can't decode goes through the FFmpeg extension. Live streams reconnect on network stalls (the link stays
 * valid for hours); an HTTP refusal (410 stale, 403 expired, 409 full…) closes the player with the status and the
 * server's X-Iptv-Error so the web app can renew or explain. Back closes.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_LIVE = "live";
    static final String EXTRA_RESUME_MS = "resumeMs";
    static final String EXTRA_NOTE = "note";
    static final String EXTRA_GROWING = "growing";
    static final String EXTRA_CHANNEL_ID = "channelId";
    static final String EXTRA_ALT_IDS = "altIds";
    static final String EXTRA_ALT_NAMES = "altNames";
    static final String EXTRA_PROG_START = "programmeStart";
    static final String EXTRA_PROG_STOP = "programmeStop";
    static final String RESULT_CHANNEL_ID = "channelId";
    static final String EXTRA_PHASE = "phase";
    static final String EXTRA_STARTS_AT = "startsAt";
    static final String EXTRA_PRE_ALT_IDS = "preAltIds";
    static final String EXTRA_PRE_ALT_NAMES = "preAltNames";
    static final String EXTRA_MATCH_ID = "matchId";
    static final String EXTRA_API_BASE = "apiBase";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_DESCRIPTION = "description";
    static final String EXTRA_UP_NEXT_TITLES = "upNextTitles";
    static final String EXTRA_UP_NEXT_STARTS = "upNextStarts";
    static final String EXTRA_FANTASY_ALERTS = "fantasyAlerts";
    static final String EXTRA_FLIP_ALERTS = "flipAlerts";
    /** sport.panelStyle: docked (default) | floating, for the landscape live drawer. */
    static final String EXTRA_PANEL_STYLE = "panelStyle";
    /** Which open() this player answers (PlayerPlugin resolves that call when it closes). */
    static final String EXTRA_SESSION = "session";

    static final String RESULT_REASON = "reason";
    static final String RESULT_POSITION_MS = "positionMs";
    static final String RESULT_DURATION_MS = "durationMs";
    static final String RESULT_ERROR = "error";
    static final String RESULT_ERROR_CODE = "errorCode";
    static final String RESULT_HTTP_STATUS = "httpStatus";
    static final String RESULT_IPTV_ERROR = "iptvError";

    private static final long PROGRESS_MS = 30_000;
    private static final int MAX_RECONNECTS = 5;
    private static final long NOTE_MS = 6_000;
    private static final long STALL_MS = 10_000;
    private static final long UNLOCK_ROTATION_MS = 1_500;
    private static final long PRE_CARD_MS = 10_000;
    private static final long URL_TIMEOUT_MS = 15_000;
    private static final long SEEK_BACK_MS = 10_000;
    private static final long SEEK_FORWARD_MS = 30_000;
    /** "Live" on a growing recording lands this far before the end of what is recorded so far. */
    private static final long LIVE_EDGE_MS = 10_000;
    /** Portrait: the video starts below the status bar / cutout area. */
    private static final int PORTRAIT_TOP_DP = 28;
    private static final String ALERT_CHANNEL = "live_alerts";
    private static final int ALERT_ID_BASE = 7_100;
    private static final long ALERT_TIMEOUT_MS = 120_000;
    private static final int PIP_PLAY_PAUSE = 1;
    private static final int PIP_LIVE = 2;
    private static final int PIP_CLOSE = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView view;
    private ProgressBar spinner;
    private TextView titleView;
    private boolean live;
    /** The open player (one at a time), for [provideUrl] answers from the web app. */
    static java.lang.ref.WeakReference<PlayerActivity> instance = new java.lang.ref.WeakReference<>(null);
    private String channelId;
    private String channelTitle;
    private final java.util.List<String[]> alternates = new java.util.ArrayList<>(); // {id, name}
    private final java.util.Set<String> tried = new java.util.HashSet<>();
    private long programmeStart;
    private long programmeStop;
    /** Watching the programme on now from its start (a catch-up link); "Back to live" returns. */
    private boolean fromKickOff;
    private TextView kickOffButton;
    /** In-event drawer (plan "In-event panel"); null when the web app passed no match / base. */
    private LiveStatsDrawer liveDrawer;
    private String matchId;
    /** Pre-game (plan "Pre-game state"): the event's real start (epoch ms) while the channel shows a holding screen. */
    private long startsAtMs;
    private android.widget.LinearLayout preCard;
    private TextView preText;
    private final java.util.List<String[]> preChannels = new java.util.ArrayList<>(); // {id, name}
    private long stalledSince;
    private int requestSeq;
    private final java.util.Map<Integer, UrlCallback> pending = new java.util.HashMap<>();

    interface UrlCallback {
        void onUrl(String url, String mime, String error);
    }
    private boolean growing;
    /** Set by "Live": once the reloaded recording is ready, jump near its (new) end. */
    private boolean toLiveEdge;
    private int session;
    private FrameLayout root;
    /** One /live poller for the drawer, the portrait panel and flip alerts; null without a live match. */
    private LiveFeed liveFeed;
    /** Portrait: below the 16:9 video (match tabs, or the programme's title, description and up next). */
    private MatchPanel matchPanel;
    private LiveAlerts liveAlerts;
    private TextView infoButton;
    /** sport.flipAlerts: flips and moments also alert (toast / notification) while no live view shows. */
    private boolean flipAlertsOn = true;
    /** Match info said 403 / unknown: no ⓘ for this match. */
    private boolean matchHidden;
    /** The opening note (bottom centre in landscape; a banner at the top of the panel in portrait). */
    private TextView noteView;
    private TextView liveButton;
    private TextView pipButton;
    private boolean pipReceiverOn;
    private int alertSeq;
    /** Length at the last end of a growing recording: no growth since = it has finished, so close. */
    private long endedAt = -1;
    private boolean closing;
    private int reconnects;
    private MediaItem item;
    /** Shown and hidden with the controls (title, back, kick-off, Live). */
    private final java.util.List<View> overlays = new java.util.ArrayList<>();

    private final Runnable progressTick =
        new Runnable() {
            @Override
            public void run() {
                emitProgress();
                handler.postDelayed(this, PROGRESS_MS);
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String url = in.getStringExtra(EXTRA_URL);
        String mime = in.getStringExtra(EXTRA_MIME);
        live = in.getBooleanExtra(EXTRA_LIVE, false);
        growing = in.getBooleanExtra(EXTRA_GROWING, false);
        channelId = in.getStringExtra(EXTRA_CHANNEL_ID);
        channelTitle = in.getStringExtra(EXTRA_TITLE);
        String[] altIds = in.getStringArrayExtra(EXTRA_ALT_IDS);
        String[] altNames = in.getStringArrayExtra(EXTRA_ALT_NAMES);
        if (altIds != null) {
            for (int i = 0; i < altIds.length; i++) alternates.add(new String[] {altIds[i], altNames != null && i < altNames.length ? altNames[i] : altIds[i]});
        }
        if (channelId != null) tried.add(channelId);
        programmeStart = in.getLongExtra(EXTRA_PROG_START, 0);
        programmeStop = in.getLongExtra(EXTRA_PROG_STOP, 0);
        instance = new java.lang.ref.WeakReference<>(this);
        session = in.getIntExtra(EXTRA_SESSION, 0);
        long resumeMs = in.getLongExtra(EXTRA_RESUME_MS, 0);

        // Opens in landscape (either side) whatever way the phone is held; free rotation starts later ([unlockRotation])
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        handler.postDelayed(this::unlockRotation, UNLOCK_ROTATION_MS);
        getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.ACCELEROMETER_ROTATION), false, rotationLockObserver);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setKeepContentOnPlayerReset(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        titleView = new TextView(this);
        titleView.setText(in.getStringExtra(EXTRA_TITLE));
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setShadowLayer(8, 0, 2, Color.BLACK);
        titleView.setPadding(dp(64), dp(16), dp(24), dp(16));
        root.addView(titleView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        overlays.add(titleView);
        // On-screen back (live too): same as Android back
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(Color.WHITE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        back.setShadowLayer(8, 0, 2, Color.BLACK);
        back.setPadding(dp(18), dp(2), dp(18), dp(8));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> close("back", null));
        root.addView(back, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        overlays.add(back);
        // Top-right buttons (with the controls): ⓘ match info, Live stats, From kick-off
        android.widget.LinearLayout topRight = new android.widget.LinearLayout(this);
        topRight.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        trp.topMargin = dp(16);
        trp.rightMargin = dp(24);
        root.addView(topRight, trp);
        overlays.add(topRight);
        matchId = in.getStringExtra(EXTRA_MATCH_ID);
        if (matchId != null && !matchId.isEmpty()) {
            // ⓘ: the web app shows match info (after the player closes, or over it when it can)
            infoButton = pill("\u24D8", 0xCC333333);
            infoButton.setContentDescription("Match info");
            infoButton.setOnClickListener(v -> matchAction("matchInfo"));
            topRight.addView(infoButton, gap());
            String apiBase = in.getStringExtra(EXTRA_API_BASE);
            if (live && apiBase != null && !apiBase.isEmpty()) {
                liveFeed = new LiveFeed(handler, apiBase, in.getStringExtra(EXTRA_TOKEN), matchId);
                liveDrawer =
                    new LiveStatsDrawer(this, root, liveFeed, apiBase, in.getStringExtra(EXTRA_TOKEN), () -> matchPanel == null ? LiveTeams.NONE : matchPanel.teams(), "floating".equals(in.getStringExtra(EXTRA_PANEL_STYLE)));
                liveDrawer.onOpenChanged = this::updateFeedSpeed;
                // 403 / unknown match: the Live button goes
                liveFeed.addListener(() -> {
                    if (liveFeed.hidden) root.post(this::applyLayout);
                });
                liveButton = pill("Live", 0xCC333333);
                liveButton.setContentDescription("Live stats");
                liveButton.setOnClickListener(v -> {
                    matchAction("liveStats");
                    liveDrawer.open();
                });
                topRight.addView(liveButton, gap());
            }
        }
        // Mini player (Android 8+, where the device has picture-in-picture)
        if (supportsPip()) {
            pipButton = pill("\u29C9", 0xCC333333);
            pipButton.setContentDescription("Mini player");
            pipButton.setOnClickListener(v -> enterPip());
            topRight.addView(pipButton, gap());
        }
        // From kick-off for a live programme the web app knows the start of (catch-up of what is on now)
        if (live && programmeStart > 0 && channelId != null) {
            kickOffButton = pill("From kick-off", 0xCC333333);
            kickOffButton.setOnClickListener(v -> toggleKickOff());
            topRight.addView(kickOffButton, gap());
        }
        // Pre-game: a card bottom-left until the event starts; hides after 10 s, any tap brings it back
        String phase = in.getStringExtra(EXTRA_PHASE);
        startsAtMs = in.getLongExtra(EXTRA_STARTS_AT, 0L);
        String[] preIds = in.getStringArrayExtra(EXTRA_PRE_ALT_IDS);
        String[] preNames = in.getStringArrayExtra(EXTRA_PRE_ALT_NAMES);
        if (preIds != null) {
            for (int k = 0; k < preIds.length; k++) preChannels.add(new String[] {preIds[k], preNames != null && k < preNames.length ? preNames[k] : preIds[k]});
        } else {
            preChannels.addAll(alternates);
        }
        if ("pre".equals(phase) && startsAtMs > System.currentTimeMillis()) addPreGameCard(root);
        view.setControllerVisibilityListener(
            (PlayerView.ControllerVisibilityListener) visibility -> {
                for (View o : overlays) o.setVisibility(visibility);
                if (visibility == View.VISIBLE) showPreCard();
            }
        );

        String note = in.getStringExtra(EXTRA_NOTE);
        if (note != null && !note.isEmpty()) {
            noteView = new TextView(this);
            noteView.setText(note);
            noteView.setTextColor(Color.WHITE);
            noteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            noteView.setBackgroundColor(0xCC000000);
            noteView.setPadding(dp(14), dp(8), dp(14), dp(8));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(72);
            root.addView(noteView, lp);
            handler.postDelayed(() -> noteView.setVisibility(View.GONE), NOTE_MS);
        }
        // Portrait: the panel below the video (match tabs, or the programme's details)
        String apiBaseForPanel = in.getStringExtra(EXTRA_API_BASE);
        boolean sport = matchId != null && !matchId.isEmpty() && apiBaseForPanel != null && !apiBaseForPanel.isEmpty();
        java.util.List<String[]> upNext = new java.util.ArrayList<>();
        String[] nextTitles = in.getStringArrayExtra(EXTRA_UP_NEXT_TITLES);
        long[] nextStarts = in.getLongArrayExtra(EXTRA_UP_NEXT_STARTS);
        for (int k = 0; nextTitles != null && k < nextTitles.length; k++) {
            long st = nextStarts != null && k < nextStarts.length ? nextStarts[k] : 0L;
            String at = st > 0 ? new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(st * 1000L)) : "";
            upNext.add(new String[] {at, nextTitles[k]});
        }
        matchPanel =
            new MatchPanel(
                this,
                root,
                handler,
                panelHost,
                liveFeed,
                sport ? apiBaseForPanel : null,
                in.getStringExtra(EXTRA_TOKEN),
                sport ? matchId : null,
                channelTitle,
                in.getStringExtra(EXTRA_DESCRIPTION),
                upNext
            );
        // Alerts: fantasy scoring (NFL) and market flips; toasts on screen, notifications in the mini player
        if (sport && live) {
            boolean fantasyOn = in.getBooleanExtra(EXTRA_FANTASY_ALERTS, true);
            boolean flipOn = in.getBooleanExtra(EXTRA_FLIP_ALERTS, true);
            flipAlertsOn = flipOn;
            // Always on for a live match: moments flash the strip; fantasy and flips follow the user's settings
            liveAlerts = new LiveAlerts(handler, liveFeed, apiBaseForPanel, in.getStringExtra(EXTRA_TOKEN), matchId, fantasyOn, flipOn, alertSink);
            if (fantasyOn || flipOn) askForNotificationsOnce();
        }
        if (liveFeed != null) liveFeed.start();
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) root.post(this::applyLayout);
        });
        addOnPictureInPictureModeChangedListener(info -> onPipChanged(info.isInPictureInPictureMode()));
        if (supportsPip()) {
            androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver, new android.content.IntentFilter(pipAction()), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            pipReceiverOn = true;
        }
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (liveDrawer != null && liveDrawer.isOpen()) liveDrawer.close();
                    else close("back", null);
                }
            }
        );

        DefaultHttpDataSource.Factory http =
            new DefaultHttpDataSource.Factory()
                .setUserAgent("Jellytip-phone")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000);
        DefaultExtractorsFactory extractors =
            new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setTsExtractorTimestampSearchBytes(1500 * 188);
        // Platform decoders first; FFmpeg (AC-3, E-AC-3, DTS, MP2…) when the device has none for the track
        DefaultRenderersFactory renderers =
            new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player =
            new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http, extractors))
                .setSeekBackIncrementMs(SEEK_BACK_MS)
                .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
                .build();
        view.setPlayer(player);
        if (live) {
            view.setShowFastForwardButton(false);
            view.setShowRewindButton(false);
        } else {
            // Films, episodes, recordings: the default controller's seek bar plus -10 s / +30 s
            view.setShowFastForwardButton(true);
            view.setShowRewindButton(true);
        }
        if (growing) addLiveButton(root);
        updatePip();

        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String type = mimeFor(url, mime);
        if (!live) {
            // Seeks land on the nearest keyframe: one range request instead of decoding forward to the exact frame
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
            // Indexed files (MP4 recordings, MKV/MP4 films) show the picture under the finger while dragging the bar.
            // MPEG-TS can only seek by binary search over HTTP, so there the bar seeks once, on release.
            boolean ts = MimeTypes.VIDEO_MP2T.equals(type);
            view.setTimeBarScrubbingEnabled(!ts && !growing);
        }
        if (type != null) b.setMimeType(type);
        item = b.build();
        player.addListener(listener);
        if (resumeMs > 0 && !live) player.setMediaItem(item, resumeMs);
        else player.setMediaItem(item);
        player.setPlayWhenReady(true);
        player.prepare();
        if (!live) handler.postDelayed(progressTick, PROGRESS_MS);
        if (live) handler.postDelayed(stallTick, 1_000);
    }

    private final Player.Listener listener =
        new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                spinner.setVisibility(state == Player.STATE_BUFFERING || state == Player.STATE_IDLE ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    reconnects = 0;
                    if (toLiveEdge) {
                        toLiveEdge = false;
                        long d = player.getDuration();
                        if (d != C.TIME_UNSET && d > LIVE_EDGE_MS) player.seekTo(d - LIVE_EDGE_MS);
                    }
                }
                // A growing recording "ends" at what was recorded when it opened: reload to pick up the rest
                if (state == Player.STATE_ENDED && growing && !closing && player.getDuration() > endedAt) {
                    endedAt = player.getDuration();
                    jumpToLive();
                }
                // From kick-off caught up (the catch-up ends where the button was pressed): back to live
                else if (state == Player.STATE_ENDED && live && fromKickOff && !closing) backToLiveAfterKickOff();
                else if (state == Player.STATE_ENDED && !live) close("ended", null);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                onError(error);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePip();
            }
        };

    /**
     * "Live" for a recording still being written: the file's length is fixed when it is opened, so reload it and seek
     * near the new end.
     */
    private void jumpToLive() {
        if (player == null || closing) return;
        toLiveEdge = true;
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void addLiveButton(FrameLayout root) {
        TextView liveButton = new TextView(this);
        liveButton.setText("● Live");
        liveButton.setTextColor(Color.WHITE);
        liveButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        liveButton.setBackgroundColor(0xCCB3261E);
        liveButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        liveButton.setOnClickListener(v -> jumpToLive());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(16);
        lp.rightMargin = dp(24);
        root.addView(liveButton, lp);
        overlays.add(liveButton);
    }

    private void onError(PlaybackException error) {
        HttpDataSource.InvalidResponseCodeException http = httpError(error);
        JSObject ev = errorDetails(error, http);
        ev.put("reconnects", reconnects);
        PlayerPlugin p = PlayerPlugin.current;
        if (p != null) p.emit("playerError", ev);
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        // A stall or dropped connection on a link that is still good: reconnect (live picks up at the edge)
        boolean network =
            http == null &&
            (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED);
        if (network && reconnects < MAX_RECONNECTS) {
            reconnects++;
            long at = player.getCurrentPosition();
            handler.postDelayed(
                () -> {
                    if (closing) return;
                    if (live) player.setMediaItem(item);
                    else player.setMediaItem(item, at);
                    player.prepare();
                },
                Math.min(8_000, 1_000L * reconnects)
            );
            return;
        }
        // Live: a fatal error moves to the event's next channel before giving up
        if (live && !fromKickOff && failover("error " + error.getErrorCodeName())) return;
        close("error", error);
    }

    /** Live and meant to play but buffering for 10 s: the same as a fatal error (failover). */
    private final Runnable stallTick =
        new Runnable() {
            @Override
            public void run() {
                if (closing || player == null) return;
                boolean stalled = player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING;
                long now = android.os.SystemClock.elapsedRealtime();
                if (!stalled) {
                    stalledSince = 0;
                } else if (stalledSince == 0) {
                    stalledSince = now;
                } else if (now - stalledSince >= STALL_MS && !fromKickOff) {
                    stalledSince = now;
                    failover("stalled 10 s");
                }
                handler.postDelayed(this, 1_000);
            }
        };

    /**
     * Ask the web app for a stream link ("needUrl" event, answered by JellytipPlayer.provideUrl). [reason] is
     * failover | kickoff | live. No answer in 15 s = error.
     */
    private void requestUrl(String reason, String forChannel, long start, long stop, UrlCallback cb) {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null) {
            cb.onUrl(null, null, "no web app");
            return;
        }
        int id = ++requestSeq;
        pending.put(id, cb);
        JSObject o = new JSObject();
        o.put("requestId", id);
        o.put("reason", reason);
        o.put("channelId", forChannel);
        if (start > 0) {
            o.put("start", start);
            o.put("stop", stop);
        }
        p.emit("needUrl", o);
        handler.postDelayed(
            () -> {
                UrlCallback left = pending.remove(id);
                if (left != null) left.onUrl(null, null, "timeout");
            },
            URL_TIMEOUT_MS
        );
    }

    /** From PlayerPlugin.provideUrl (main thread). */
    void onProvidedUrl(int requestId, String url, String mime, String error) {
        UrlCallback cb = pending.remove(requestId);
        if (cb != null && !closing) cb.onUrl(url, mime, error);
    }

    /** Play another link in place (failover, kick-off, back to live). */
    private void switchTo(String url, String mime, boolean seekable) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String type = mimeFor(url, mime);
        if (type != null) b.setMimeType(type);
        item = b.build();
        reconnects = 0;
        stalledSince = 0;
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
        view.setShowRewindButton(seekable);
        view.setShowFastForwardButton(seekable);
    }

    /** Next untried alternate of the event; false = none left. */
    private boolean failover(String why) {
        String[] next = null;
        for (String[] a : alternates) {
            if (!tried.contains(a[0])) {
                next = a;
                break;
            }
        }
        if (next == null) return false;
        final String[] alt = next;
        tried.add(alt[0]);
        emitEvent("failover", "{\"from\":\"" + channelId + "\",\"to\":\"" + alt[0] + "\",\"why\":\"" + why + "\"}");
        spinner.setVisibility(View.VISIBLE);
        requestUrl(
            "failover",
            alt[0],
            0,
            0,
            (url, mime, error) -> {
                if (url == null) {
                    // This one couldn't be opened: try the next, else stop with the original error text
                    if (!failover(why)) close("error", null);
                    return;
                }
                channelId = alt[0];
                titleView.setText(alt[1]);
                switchTo(url, mime, false);
                android.widget.Toast.makeText(this, "Switched to " + alt[1], android.widget.Toast.LENGTH_SHORT).show();
            }
        );
        return true;
    }

    private void backToLiveAfterKickOff() {
        requestUrl(
            "live",
            channelId,
            0,
            0,
            (url, mime, error) -> {
                if (url == null) {
                    close("ended", null);
                    return;
                }
                fromKickOff = false;
                if (kickOffButton != null) kickOffButton.setText("From kick-off");
                switchTo(url, mime, false);
                updatePip();
                android.widget.Toast.makeText(this, "Back to live", android.widget.Toast.LENGTH_SHORT).show();
            }
        );
    }

    /** "From kick-off" (catch-up from the programme's start) or "Back to live". */
    private void toggleKickOff() {
        boolean toStart = !fromKickOff;
        kickOffButton.setEnabled(false);
        requestUrl(
            toStart ? "kickoff" : "live",
            channelId,
            toStart ? programmeStart : 0,
            toStart ? programmeStop : 0,
            (url, mime, error) -> {
                kickOffButton.setEnabled(true);
                if (url == null) {
                    android.widget.Toast.makeText(this, toStart ? "Can't start from kick-off" : "Can't go back to live", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                fromKickOff = toStart;
                kickOffButton.setText(toStart ? "\u25CF Back to live" : "From kick-off");
                switchTo(url, mime, toStart);
                updatePip();
            }
        );
    }

    private void emitEvent(String name, String json) {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null) return;
        try {
            p.emit(name, new JSObject(json));
        } catch (org.json.JSONException ignored) {
            // diagnostic only
        }
    }

    private void addPreGameCard(FrameLayout root) {
        preCard = new android.widget.LinearLayout(this);
        preCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        preCard.setBackgroundColor(0xD9000000);
        preCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        preText = new TextView(this);
        preText.setTextColor(Color.WHITE);
        preText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        preCard.addView(preText);
        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        TextView remind = pill("Remind me", 0xCC444444);
        remind.setOnClickListener(v -> preGameAction("remind", null));
        buttons.addView(remind);
        if (!preChannels.isEmpty()) {
            TextView others = pill("Other channels", 0xCC444444);
            android.widget.LinearLayout.LayoutParams olp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = dp(10);
            others.setOnClickListener(v -> pickOtherChannel());
            buttons.addView(others, olp);
        }
        preCard.addView(buttons);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(24);
        lp.bottomMargin = dp(88);
        root.addView(preCard, lp);
        // Portrait stacks the note under the card: re-place it once the card has its height
        preCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) root.post(this::applyLayout);
        });
        showPreCard();
        // "Started" at the real start
        handler.postDelayed(
            () -> {
                if (closing) return;
                hidePreCard();
                preCard = null;
                android.widget.Toast.makeText(this, "Started", android.widget.Toast.LENGTH_SHORT).show();
            },
            Math.max(0, startsAtMs - System.currentTimeMillis())
        );
    }

    private final Runnable hidePre = this::hidePreCard;

    private void showPreCard() {
        if (preCard == null) return;
        long now = System.currentTimeMillis();
        if (startsAtMs <= now) return;
        long mins = (startsAtMs - now + 59_999) / 60_000;
        String in = mins < 60 ? "in " + mins + " min" : "in " + (mins / 60) + "h" + (mins % 60 > 0 ? " " + (mins % 60) + "m" : "");
        String at = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(startsAtMs));
        preText.setText("Pre-game \u2014 the event starts at " + at + " (" + in + ")");
        preCard.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePre);
        handler.postDelayed(hidePre, PRE_CARD_MS);
    }

    private void hidePreCard() {
        if (preCard != null) preCard.setVisibility(View.GONE);
    }

    private void pickOtherChannel() {
        String[] names = new String[preChannels.size()];
        for (int k = 0; k < names.length; k++) names[k] = preChannels.get(k)[1];
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Other channels")
            .setItems(names, (d, which) -> preGameAction("channel", preChannels.get(which)[0]))
            .show();
    }

    /** Back to the web app: `matchInfo` / `liveStats` with `{matchId, channelId?}`. */
    private void matchAction(String event) {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("matchId", matchId);
        if (channelId != null) o.put("channelId", channelId);
        p.emit(event, o);
    }

    private android.widget.LinearLayout.LayoutParams gap() {
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        return lp;
    }

    /** Back to the web app: `pregameAction {action: remind | channel, channelId?}`. */
    private void preGameAction(String action, String forChannel) {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        if (forChannel != null) o.put("channelId", forChannel);
        else if (channelId != null) o.put("channelId", channelId);
        p.emit("pregameAction", o);
        // Another channel: the web app reopens the player on it when open() resolves, so this one closes now
        if ("channel".equals(action)) close("back", null);
    }

    private TextView pill(String text, int background) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setBackgroundColor(background);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        return t;
    }

    private static HttpDataSource.InvalidResponseCodeException httpError(Throwable t) {
        while (t != null) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) return (HttpDataSource.InvalidResponseCodeException) t;
            t = t.getCause();
        }
        return null;
    }

    private JSObject errorDetails(PlaybackException error, HttpDataSource.InvalidResponseCodeException http) {
        JSObject o = new JSObject();
        o.put("errorCode", error.getErrorCodeName());
        o.put("message", String.valueOf(error.getMessage()));
        Throwable cause = error.getCause();
        if (cause != null) o.put("cause", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        if (http != null) {
            o.put("httpStatus", http.responseCode);
            String iptv = header(http.headerFields, "X-Iptv-Error");
            if (iptv != null) o.put("iptvError", iptv);
        }
        if (player != null) {
            o.put("positionMs", player.getCurrentPosition());
            if (player.getAudioFormat() != null) o.put("audio", String.valueOf(player.getAudioFormat().sampleMimeType));
            if (player.getVideoFormat() != null) o.put("video", String.valueOf(player.getVideoFormat().sampleMimeType));
        }
        o.put("live", live);
        return o;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    private void emitProgress() {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null || player == null) return;
        long d = player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) return;
        JSObject o = new JSObject();
        o.put("positionMs", player.getCurrentPosition());
        o.put("durationMs", d);
        p.emit("progress", o);
    }

    /** A new open() replaced this player (its call was answered already). */
    void closeReplaced() {
        close("replaced", null);
    }

    private void close(String reason, PlaybackException error) {
        if (closing) return;
        closing = true;
        handler.removeCallbacksAndMessages(null);
        Intent data = new Intent();
        data.putExtra(RESULT_REASON, reason);
        if (channelId != null) data.putExtra(RESULT_CHANNEL_ID, channelId);
        if (player != null) {
            data.putExtra(RESULT_POSITION_MS, player.getCurrentPosition());
            long d = player.getDuration();
            data.putExtra(RESULT_DURATION_MS, d == C.TIME_UNSET ? 0 : d);
        }
        if (error != null) {
            HttpDataSource.InvalidResponseCodeException http = httpError(error);
            data.putExtra(RESULT_ERROR, String.valueOf(error.getMessage()));
            data.putExtra(RESULT_ERROR_CODE, error.getErrorCodeName());
            if (http != null) {
                data.putExtra(RESULT_HTTP_STATUS, http.responseCode);
                String iptv = header(http.headerFields, "X-Iptv-Error");
                if (iptv != null) data.putExtra(RESULT_IPTV_ERROR, iptv);
            }
        }
        setResult(RESULT_OK, data);
        // The player runs in its own task (the mini player floats over the app), so open() is answered directly
        PlayerPlugin.closed(session, data);
        release();
        finish();
    }

    private void release() {
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app frees the provider's one connection at once (as the web player does on pagehide)
        if (!isChangingConfigurations()) close("back", null);
    }

    /** Set once the player has opened in landscape: from then on the phone's own rotation decides. */
    private boolean rotationUnlocked;

    /**
     * After the landscape start (or 1.5 s): turning the phone to portrait rotates, as long as the system auto-rotate
     * is on. With rotation locked it stays landscape.
     */
    private void unlockRotation() {
        if (closing) return;
        rotationUnlocked = true;
        applyRotationRule();
    }

    private void applyRotationRule() {
        if (!rotationUnlocked || isInPictureInPictureMode()) return;
        boolean autoRotate = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        setRequestedOrientation(autoRotate ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private final android.database.ContentObserver rotationLockObserver =
        new android.database.ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                applyRotationRule();
            }
        };

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // The device reported landscape: safe to let it rotate (it won't flip straight back to portrait)
        if (landscape && !rotationUnlocked) unlockRotation();
        root.post(this::applyLayout);
    }

    // ---- Layout: portrait = 16:9 video on top + panel below; landscape and the mini player = full-screen video ----

    private boolean inPip() {
        return isInPictureInPictureMode();
    }

    private void applyLayout() {
        if (root == null || view == null || closing) return;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        boolean pip = inPip();
        boolean portrait = h > w && !pip;
        int top = portrait ? dp(PORTRAIT_TOP_DP) : 0;
        int height = portrait ? w * 9 / 16 : FrameLayout.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.height != height || lp.topMargin != top) {
            lp.height = height;
            lp.topMargin = top;
            lp.gravity = Gravity.TOP;
            view.setLayoutParams(lp);
        }
        if (matchPanel != null) matchPanel.show(portrait, top + (portrait ? height : 0));
        // Pre-game card and note: over the picture's foot in landscape; in portrait a banner at the top of the panel
        int panelTop = top + (portrait ? height : 0);
        placeBanner(preCard, portrait, panelTop, Gravity.BOTTOM | Gravity.START, dp(24), dp(88));
        placeBanner(noteView, portrait, panelTop + (preCard != null && preCard.getVisibility() == View.VISIBLE ? preCard.getHeight() : 0), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(72));
        // The spinner sits on the picture
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) spinner.getLayoutParams();
        int spinTop = portrait ? top + height / 2 - dp(24) : 0;
        int spinGravity = portrait ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        if (sp.topMargin != spinTop || sp.gravity != spinGravity) {
            sp.topMargin = spinTop;
            sp.gravity = spinGravity;
            spinner.setLayoutParams(sp);
        }
        // Portrait has the panel's Info and live tabs; landscape keeps ⓘ and the Live drawer
        if (infoButton != null) infoButton.setVisibility(portrait || matchHidden ? View.GONE : View.VISIBLE);
        boolean liveHidden = liveFeed != null && liveFeed.hidden;
        if (liveButton != null) liveButton.setVisibility(portrait || liveHidden ? View.GONE : View.VISIBLE);
        if ((portrait || pip) && liveDrawer != null) liveDrawer.hideNow();
        updateFeedSpeed();
    }

    private void placeBanner(View v, boolean portrait, int panelTop, int landGravity, int landLeft, int landBottom) {
        if (v == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        int width = portrait ? FrameLayout.LayoutParams.MATCH_PARENT : FrameLayout.LayoutParams.WRAP_CONTENT;
        int gravity = portrait ? Gravity.TOP | Gravity.START : landGravity;
        int topMargin = portrait ? panelTop : 0;
        int left = portrait ? 0 : landLeft;
        int bottom = portrait ? 0 : landBottom;
        if (lp.width != width || lp.gravity != gravity || lp.topMargin != topMargin || lp.leftMargin != left || lp.bottomMargin != bottom) {
            lp.width = width;
            lp.gravity = gravity;
            lp.topMargin = topMargin;
            lp.leftMargin = left;
            lp.bottomMargin = bottom;
            v.setLayoutParams(lp);
        }
        // Above the panel (added later)
        if (portrait) v.bringToFront();
    }

    private void updateFeedSpeed() {
        if (liveFeed == null) return;
        liveFeed.setFast((liveDrawer != null && liveDrawer.isOpen()) || (matchPanel != null && matchPanel.showsLive()));
    }

    private final MatchPanel.Host panelHost =
        new MatchPanel.Host() {
            @Override
            public String currentChannelId() {
                return channelId;
            }

            @Override
            public void switchChannel(String id, String name) {
                switchChannelInPlace(id, name);
            }

            @Override
            public void matchAction(String action) {
                emitMatchAction(action);
            }

            @Override
            public void onPanelTabChanged() {
                updateFeedSpeed();
            }

            @Override
            public void onMatchHidden() {
                matchHidden = true;
                root.post(PlayerActivity.this::applyLayout);
            }
        };

    /** "Where to watch" in the portrait Info tab: play [id] here (link via needUrl "switch"), then tell the web app. */
    private void switchChannelInPlace(String id, String name) {
        if (closing || id == null || id.equals(channelId)) return;
        spinner.setVisibility(View.VISIBLE);
        requestUrl(
            "switch",
            id,
            0,
            0,
            (url, mime, error) -> {
                if (url == null) {
                    if (player != null) spinner.setVisibility(player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    android.widget.Toast.makeText(this, "Can't play " + name, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String from = channelId;
                channelId = id;
                channelTitle = name;
                tried.add(id);
                titleView.setText(name);
                fromKickOff = false;
                if (kickOffButton != null) kickOffButton.setText("From kick-off");
                switchTo(url, mime, false);
                updatePip();
                if (matchPanel != null) matchPanel.onChannelChanged();
                PlayerPlugin p = PlayerPlugin.current;
                if (p != null) {
                    JSObject o = new JSObject();
                    if (from != null) o.put("from", from);
                    o.put("to", id);
                    o.put("name", name);
                    p.emit("channelSwitch", o);
                }
            }
        );
    }

    /** Back to the web app: `matchAction {action: remind | record, matchId, channelId?}`. */
    private void emitMatchAction(String action) {
        PlayerPlugin p = PlayerPlugin.current;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("matchId", matchId);
        if (channelId != null) o.put("channelId", channelId);
        p.emit("matchAction", o);
        android.widget.Toast.makeText(this, "remind".equals(action) ? "Setting a reminder…" : "Recording…", android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- Alerts: a toast on screen, a heads-up notification in the mini player -------------------------------

    private final LiveAlerts.Sink alertSink =
        new LiveAlerts.Sink() {
            @Override
            public void alert(String text, String side) {
                if (closing) return;
                boolean onScreen = !inPip() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
                if (onScreen) android.widget.Toast.makeText(PlayerActivity.this, text, android.widget.Toast.LENGTH_LONG).show();
                else postAlert(text, side);
            }

            @Override
            public String teamName(String side) {
                return matchPanel == null ? null : matchPanel.teamShortFor(side);
            }

            @Override
            public void moment(String label, String side, String detail) {
                if (closing) return;
                LiveTeams t = matchPanel == null ? LiveTeams.NONE : matchPanel.teams();
                int color = side == null || !t.known ? Broadcast.LIVE : t.side(side);
                // The strip's 2 s wipe where a live view shows; else (closed panel, mini player) an alert
                boolean shown = !inPip() && ((liveDrawer != null && liveDrawer.flash(label, detail, color)) || (matchPanel != null && matchPanel.flash(label, detail, color)));
                if (!shown && flipAlertsOn) alert("\u26A1  " + label + (detail == null ? "" : " · " + detail), side);
            }
        };

    private boolean canNotify() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private void postAlert(String text, String side) {
        if (!canNotify()) return;
        androidx.core.app.NotificationManagerCompat nm = androidx.core.app.NotificationManagerCompat.from(this);
        nm.createNotificationChannel(
            new androidx.core.app.NotificationChannelCompat.Builder(ALERT_CHANNEL, androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Live alerts")
                .setDescription("Fantasy scoring and odds flips while the mini player is open")
                .build()
        );
        androidx.core.app.NotificationCompat.Builder b =
            new androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_live)
                .setContentTitle(channelTitle == null || channelTitle.isEmpty() ? "Live" : channelTitle)
                .setContentText(text)
                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setTimeoutAfter(ALERT_TIMEOUT_MS);
        if ("me".equals(side)) b.setColor(0xFF4CAF50);
        else if ("opp".equals(side)) b.setColor(0xFFE5534B);
        try {
            nm.notify(ALERT_ID_BASE + (alertSeq++ % 20), b.build());
        } catch (SecurityException ignored) {
            // permission withdrawn meanwhile
        }
    }

    /** Android 13+: ask for notifications the first time a live match with alerts plays (alerts in the mini player). */
    private void askForNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences prefs = getSharedPreferences("jellytip-player", MODE_PRIVATE);
        if (prefs.getBoolean("askedNotifications", false)) return;
        prefs.edit().putBoolean("askedNotifications", true).apply();
        androidx.core.app.ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 41);
    }

    // ---- Mini player (picture-in-picture, Android 8+) --------------------------------------------------------

    private boolean supportsPip() {
        return android.os.Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private String pipAction() {
        return getPackageName() + ".PIP_CONTROL";
    }

    private void enterPip() {
        if (android.os.Build.VERSION.SDK_INT < 26 || !supportsPip() || closing) return;
        try {
            enterPictureInPictureMode(pipParams());
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // not allowed right now
        }
    }

    /** Home / gesture away while playing: Android 12+ enters by itself (setAutoEnterEnabled); 8-11 do it here. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (android.os.Build.VERSION.SDK_INT < 31 && player != null && player.getPlayWhenReady() && !closing) enterPip();
    }

    private void updatePip() {
        if (android.os.Build.VERSION.SDK_INT < 26 || !supportsPip() || closing || player == null) return;
        try {
            setPictureInPictureParams(pipParams());
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // not now
        }
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.PictureInPictureParams pipParams() {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16, 9));
        android.graphics.Rect hint = new android.graphics.Rect();
        if (view != null && view.getGlobalVisibleRect(hint) && hint.width() > 0) b.setSourceRectHint(hint);
        boolean playing = player != null && player.getPlayWhenReady();
        java.util.List<android.app.RemoteAction> actions = new java.util.ArrayList<>();
        actions.add(pipAction(PIP_PLAY_PAUSE, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play"));
        if (fromKickOff || growing) actions.add(pipAction(PIP_LIVE, android.R.drawable.ic_media_next, "Back to live"));
        actions.add(pipAction(PIP_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "Close"));
        int max = getMaxNumPictureInPictureActions();
        while (actions.size() > max && actions.size() > 1) actions.remove(actions.size() - 2); // keep Close
        b.setActions(actions);
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing && !closing);
        return b.build();
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.RemoteAction pipAction(int control, int icon, String title) {
        Intent i = new Intent(pipAction()).setPackage(getPackageName()).putExtra("control", control);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, control, i, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), title, title, pi);
    }

    private final android.content.BroadcastReceiver pipReceiver =
        new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (closing || player == null) return;
                switch (intent.getIntExtra("control", 0)) {
                    case PIP_PLAY_PAUSE:
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        break;
                    case PIP_LIVE:
                        if (fromKickOff && kickOffButton != null) toggleKickOff();
                        else if (growing) jumpToLive();
                        break;
                    case PIP_CLOSE:
                        close("back", null);
                        break;
                    default:
                        break;
                }
            }
        };

    /** The mini player shows only the picture: no controls, buttons, drawer or panel. */
    private void onPipChanged(boolean pip) {
        if (closing || view == null) return;
        // The web app leaves the player route while the mini player floats (the app underneath stays usable)
        PlayerPlugin p = PlayerPlugin.current;
        if (p != null) {
            JSObject o = new JSObject();
            o.put("active", pip);
            p.emit("pipChanged", o);
        }
        if (pip) {
            view.hideController();
            view.setUseController(false);
            for (View o : overlays) o.setVisibility(View.GONE);
            hidePreCard();
            if (noteView != null) noteView.setVisibility(View.GONE);
            if (liveDrawer != null) liveDrawer.hideNow();
        } else {
            view.setUseController(true);
            view.showController();
            applyRotationRule();
        }
        root.post(this::applyLayout);
    }


    @Override
    protected void onDestroy() {
        // Gone without closing (the system finished it): still answer open()
        if (!closing && !isChangingConfigurations()) close("back", null);
        if (liveFeed != null) liveFeed.release();
        if (matchPanel != null) matchPanel.release();
        if (liveAlerts != null) liveAlerts.release();
        if (pipReceiverOn) unregisterReceiver(pipReceiver);
        getContentResolver().unregisterContentObserver(rotationLockObserver);
        handler.removeCallbacksAndMessages(null);
        release();
        super.onDestroy();
    }

    private static String mimeFor(String url, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String path = Uri.parse(url).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || m.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".ts") || m.contains("mp2t")) return MimeTypes.VIDEO_MP2T;
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
