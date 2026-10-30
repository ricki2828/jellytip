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
    private static final long SEEK_BACK_MS = 10_000;
    private static final long SEEK_FORWARD_MS = 30_000;
    /** "Live" on a growing recording lands this far before the end of what is recorded so far. */
    private static final long LIVE_EDGE_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView view;
    private ProgressBar spinner;
    private TextView titleView;
    private boolean live;
    private boolean growing;
    /** Set by "Live": once the reloaded recording is ready, jump near its (new) end. */
    private boolean toLiveEdge;
    /** Length at the last end of a growing recording: no growth since = it has finished, so close. */
    private long endedAt = -1;
    private boolean closing;
    private int reconnects;
    private MediaItem item;

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
        long resumeMs = in.getLongExtra(EXTRA_RESUME_MS, 0);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());

        FrameLayout root = new FrameLayout(this);
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
        titleView.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(titleView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> titleView.setVisibility(visibility));

        String note = in.getStringExtra(EXTRA_NOTE);
        if (note != null && !note.isEmpty()) {
            TextView noteView = new TextView(this);
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
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    close("back", null);
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

        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String type = mimeFor(url, mime);
        if (type != null) b.setMimeType(type);
        item = b.build();
        player.addListener(listener);
        if (resumeMs > 0 && !live) player.setMediaItem(item, resumeMs);
        else player.setMediaItem(item);
        player.setPlayWhenReady(true);
        player.prepare();
        if (!live) handler.postDelayed(progressTick, PROGRESS_MS);
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
                else if (state == Player.STATE_ENDED && !live) close("ended", null);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                onError(error);
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
        // Shows and hides with the controls, like the title
        view.setControllerVisibilityListener(
            (PlayerView.ControllerVisibilityListener) visibility -> {
                titleView.setVisibility(visibility);
                liveButton.setVisibility(visibility);
            }
        );
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
        close("error", error);
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

    private void close(String reason, PlaybackException error) {
        if (closing) return;
        closing = true;
        handler.removeCallbacksAndMessages(null);
        Intent data = new Intent();
        data.putExtra(RESULT_REASON, reason);
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

    @Override
    protected void onDestroy() {
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
