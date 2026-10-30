// IPTV player (#/iptv/play?channel=…|recording=…|vod=…|series=…&episode=…[&start&stop][&t=title]): asks the companion
// for a 4 h stream link, plays .ts through mpegts.js, .m3u8 through hls.js and files natively. A link answering 410
// stale or 403 expired gets one fresh /play and a retry; 409 shows who holds the connection. Leaving tears the stream
// down at once (the provider may allow a single connection).
import { useCallback, useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import type Mpegts from "mpegts.js";
import { deviceId } from "../../api/jellyfin";
import { play, recordingById, saveProgress, streamUrl, type Connection, type PlayAnswer } from "../../api/iptv";
import { clampSeek, clockText, recordingLength, type RecMeta } from "../../lib/recPlay";
import { engineFor, failureText, fallbackFormat, linkFailure, playbackFailureText, playQuery, type Engine, type PlayTarget } from "../../lib/iptvPlay";
import { StreamBusy } from "../../components/StreamBusy";
import { canStop, parseBlocker, type Blocker } from "../../lib/holders";
import { debugEvent, sendDebugReport } from "../../lib/debugLog";
import { back } from "../../lib/router";
import "./iptv.css";

const PROGRESS_MS = 30_000;
// Connected but no picture and no clock after this long: treat as a failed stream (silent spinners hide codec problems)
const NO_PICTURE_MS = 15_000;
const loadHls = () => import("hls.js");
const loadMpegts = () => import("mpegts.js");

/** Stable per-browser id for the connection guard (a new /play from the same device replaces its stream = zap). */
export const iptvDevice = () => `web-${deviceId().replace(/[^A-Za-z0-9]/g, "").slice(0, 16)}`;

type State = { kind: "loading" } | { kind: "playing" } | { kind: "full"; text: string; connection?: Connection; blocker?: Blocker } | { kind: "error"; text: string };

/** Status + X-Iptv-Error of a stream link, without opening the provider (HEAD never does). */
async function probe(url: string): Promise<{ status: number; code: string | null }> {
  try {
    const r = await fetch(url, { method: "HEAD" });
    return { status: r.status, code: r.headers.get("X-Iptv-Error") };
  } catch {
    return { status: 0, code: null };
  }
}

function progressKey(t: PlayTarget): string | null {
  if (t.recording) return `rec:${t.recording}`;
  if (t.episode) return t.episode;
  if (t.vod) return t.vod;
  return null;
}

export function IptvPlayer({ target, title, resume = 0 }: { target: PlayTarget; title: string; resume?: number }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const tsRef = useRef<Mpegts.Player | null>(null);
  const linkRef = useRef<string | null>(null);
  const answerRef = useRef<PlayAnswer | null>(null);
  const engineRef = useRef<Engine>("native");
  const fmtRef = useRef<"ts" | "m3u8">("ts");
  const watchRef = useRef<number | undefined>(undefined);
  const renewed = useRef(false);
  const alive = useRef(true);
  const [state, setState] = useState<State>({ kind: "loading" });
  const [answer, setAnswer] = useState<PlayAnswer | null>(null);
  const [paused, setPaused] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const isLive = !!target.channel && target.start == null;
  const [meta, setMeta] = useState<RecMeta | null>(null);
  const [controls, setControls] = useState(true);
  const hideTimer = useRef<number | null>(null);
  const lenRef = useRef(0);
  const growRef = useRef(false);
  // Where to start once the media is known: the resume point, then (after a renewed link) where the old one stopped
  const seekTo = useRef(resume);

  const teardown = useCallback(() => {
    window.clearTimeout(watchRef.current);
    hlsRef.current?.destroy();
    hlsRef.current = null;
    const ts = tsRef.current;
    tsRef.current = null;
    if (ts) {
      try {
        ts.pause();
        ts.unload();
        ts.detachMediaElement();
        ts.destroy();
      } catch {
        // already gone
      }
    }
    const v = videoRef.current;
    if (v) {
      v.removeAttribute("src");
      v.load();
    }
    linkRef.current = null;
  }, []);

  const report = useCallback(() => {
    const key = progressKey(target);
    const v = videoRef.current;
    const dur = isFinite(v?.duration ?? NaN) && (v?.duration ?? 0) > 0 ? v!.duration : lenRef.current;
    // An in-progress recording has no final length yet: don't save a half-length as if it were the whole
    if (!key || !v || !(dur > 0) || v.currentTime < 5 || growRef.current) return;
    void saveProgress(key, Math.floor(v.currentTime), Math.floor(dur));
  }, [target]);

  // A stream link failed: work out why, then renew once, show who holds the connection, or give up
  const onLinkError = useCallback(
    async (url: string, detail = "") => {
      if (!alive.current || linkRef.current !== url) return;
      const { status, code } = await probe(url);
      if (!alive.current || linkRef.current !== url) return;
      const what = linkFailure(status, code);
      const v = videoRef.current;
      const engine = engineRef.current;
      debugEvent("iptv-player", "stream failed", {
        player: engine,
        format: fmtRef.current,
        mime: answerRef.current?.mime ?? null,
        kind: answerRef.current?.kind ?? null,
        status,
        code,
        detail,
        mediaError: v?.error ? `${v.error.code} ${v.error.message}` : null,
        readyState: v?.readyState,
        networkState: v?.networkState,
        size: v ? `${v.videoWidth}x${v.videoHeight}` : null,
        time: v?.currentTime,
        verdict: what,
      });
      void sendDebugReport();
      const hls = what === "fatal" && status >= 200 && status < 300 ? fallbackFormat(isLive, fmtRef.current) : null;
      if (hls) {
        // Live TS connected but would not play: ask for the HLS variant once
        fmtRef.current = hls;
        debugEvent("iptv-player", "falling back to HLS", {}, "info");
        teardown();
        void start();
        return;
      }
      if (what === "renew" && !renewed.current) {
        renewed.current = true;
        if (!isLive && videoRef.current && videoRef.current.currentTime > 5) seekTo.current = videoRef.current.currentTime;
        teardown();
        void start();
        return;
      }
      teardown();
      if (what === "full") setState({ kind: "full", text: "Every connection to this provider is in use." });
      else setState({ kind: "error", text: failureText(code, playbackFailureText(engine, detail, status)) });
    },
    // start is defined below and stable for the target
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [teardown, isLive],
  );

  const attach = useCallback(
    async (a: PlayAnswer) => {
      const v = videoRef.current;
      if (!v) return;
      const url = streamUrl(a.url);
      linkRef.current = url;
      const engine = engineFor(a.url, a.mime);
      engineRef.current = engine;
      answerRef.current = a;
      debugEvent("iptv-player", "attach", { player: engine, mime: a.mime ?? null, kind: a.kind, format: fmtRef.current, canPlay: a.mime ? v.canPlayType(a.mime) : null }, "info");
      watchRef.current = window.setTimeout(() => {
        const el = videoRef.current;
        if (!alive.current || linkRef.current !== url || !el || el.paused) return;
        if (el.currentTime > 0.5 && el.videoWidth > 0) return;
        void onLinkError(url, "no picture after 15 s");
      }, NO_PICTURE_MS);
      if (engine === "mpegts") {
        const mpegts = (await loadMpegts()).default;
        if (!alive.current || linkRef.current !== url) return;
        if (!mpegts.getFeatureList().mseLivePlayback) {
          setState({ kind: "error", text: "This browser can't play live TV streams." });
          return;
        }
        const p = mpegts.createPlayer(
          { type: "mpegts", isLive: a.kind === "live" || a.kind === "cu" || !!a.growing, url },
          // No worker (CSP-friendly); chase the live edge so a zap never drifts minutes behind
          { enableWorker: false, lazyLoad: false, liveBufferLatencyChasing: a.kind === "live", liveBufferLatencyMaxLatency: 6, liveBufferLatencyMinRemain: 1.5 },
        );
        tsRef.current = p;
        p.on(mpegts.Events.ERROR, (type: string, detail: string, info?: { code?: number; msg?: string }) => {
          if (type === mpegts.ErrorTypes.NETWORK_ERROR || type === mpegts.ErrorTypes.MEDIA_ERROR)
            void onLinkError(url, [type, detail, info?.code, info?.msg].filter((x) => x != null && x !== "").join(" "));
        });
        p.attachMediaElement(v);
        p.load();
        void Promise.resolve(p.play()).catch(() => setPaused(true));
        return;
      }
      // hls.js wherever MSE exists (Android WebViews claim native HLS but handle live playlists badly); else native
      const H = engine === "hls" ? (await loadHls()).default : null;
      if (!alive.current || linkRef.current !== url) return;
      if (H && H.isSupported()) {
        const h = new H({ liveDurationInfinity: true, maxBufferLength: 30, backBufferLength: 30 });
        hlsRef.current = h;
        h.on(H.Events.ERROR, (_e, data) => {
          if (data.fatal && hlsRef.current === h)
            void onLinkError(url, [data.type, data.details, data.response?.code, data.error?.message].filter((x) => x != null && x !== "").join(" "));
        });
        h.loadSource(url);
        h.attachMedia(v);
      } else v.src = url;
      v.play().catch(() => setPaused(true));
    },
    [onLinkError],
  );

  /** `retry`: the second go after stopping a recording; keeps the pop-up up and turns a second refusal into the error. */
  const start = useCallback(async (retry = false): Promise<boolean> => {
    if (!retry) setState({ kind: "loading" });
    if (isLive && fmtRef.current === "ts" && !(await loadMpegts()).default.getFeatureList().mseLivePlayback) fmtRef.current = "m3u8";
    const a = await play(playQuery(target, iptvDevice(), fmtRef.current));
    if (!alive.current) return false;
    if (a.status === 200 && a.data?.url) {
      setAnswer(a.data);
      setState({ kind: "playing" });
      await attach(a.data);
      return true;
    }
    const what = linkFailure(a.status, a.data?.code);
    if (retry) setState({ kind: "error", text: a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`) });
    else if (what === "full") {
      const b = parseBlocker(a.data);
      // Only a blocker we can say something useful about gets the pop-up; older servers keep today's message
      const popup = b && (canStop(b) || (b.kind === "viewer" && (b.title || b.channel))) ? b : undefined;
      setState({ kind: "full", text: a.data?.error ?? "Every connection to this provider is in use.", connection: a.data?.connection, blocker: popup });
    } else setState({ kind: "error", text: failureText(a.data?.code, a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`)) });
    return false;
  }, [target, isLive, attach]);

  useEffect(() => {
    alive.current = true;
    renewed.current = false;
    void start();
    const onHide = () => teardown();
    window.addEventListener("pagehide", onHide);
    return () => {
      alive.current = false;
      window.removeEventListener("pagehide", onHide);
      report();
      teardown();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start]);

  // A recording's own times: its length when the file can't say, and whether it is still growing
  useEffect(() => {
    if (!target.recording) return;
    let on = true;
    void recordingById(target.recording).then((r) => {
      if (on && r)
        setMeta({ state: r.state, start: r.start, stop: r.stop, stopPadded: r.stopPadded, began: r.began, ended: r.ended, progressDuration: r.progress?.duration ?? null });
    });
    return () => {
      on = false;
    };
  }, [target.recording]);

  // Progress for films, episodes and recordings (live has none)
  useEffect(() => {
    if (!progressKey(target)) return;
    const t = setInterval(report, PROGRESS_MS);
    return () => clearInterval(t);
  }, [target, report]);

  // A link that played for a while may renew again later (the 4 h expiry, a server restart)
  useEffect(() => {
    if (state.kind !== "playing") return;
    const t = setTimeout(() => (renewed.current = false), 60_000);
    return () => clearTimeout(t);
  }, [state.kind, answer]);

  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setControls(false), 3500);
  }, []);
  useEffect(() => {
    poke();
    return () => {
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
    };
  }, [poke]);

  const growing = !!answer?.growing || meta?.state === "recording";
  growRef.current = growing;
  const length = isLive ? 0 : recordingLength(target.recording ? meta : null, Date.now() / 1000, duration);
  lenRef.current = length;

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) void v.play().catch(() => undefined);
    else v.pause();
  };
  const seekTo_ = (to: number) => {
    const v = videoRef.current;
    if (!v) return;
    // In-progress: only what is recorded so far (the buffer's end when the player knows it)
    const sk = v.seekable;
    const cap = growing && sk.length ? Math.min(length || Infinity, sk.end(sk.length - 1) + 1) : length;
    v.currentTime = clampSeek(to, cap, growing);
    setTime(v.currentTime);
  };
  const seekBy = (d: number) => {
    const v = videoRef.current;
    if (v) seekTo_(v.currentTime + d);
  };
  const jumpLive = () => {
    const v = videoRef.current;
    if (!v) return;
    const sk = v.seekable;
    v.currentTime = sk.length ? Math.max(0, sk.end(sk.length - 1) - 1) : Math.max(0, length - 3);
    void v.play().catch(() => undefined);
  };
  const exit = () => {
    teardown();
    back(target.recording ? "/recordings" : "/livetv");
  };
  const touchClick = useRef(false);
  const lastTap = useRef<{ at: number; side: number } | null>(null);
  const tapTimer = useRef<number | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const onVideoPointerUp = (e: React.PointerEvent<HTMLVideoElement>) => {
    if (e.pointerType === "mouse" || isLive) return;
    touchClick.current = true;
    const rect = e.currentTarget.getBoundingClientRect();
    const side = e.clientX - rect.left < rect.width / 2 ? -1 : 1;
    const now = Date.now();
    const prev = lastTap.current;
    lastTap.current = { at: now, side };
    if (prev && now - prev.at < 300 && prev.side === side) {
      if (tapTimer.current) window.clearTimeout(tapTimer.current);
      tapTimer.current = null;
      seekBy(10 * side);
      setFlash(`${side < 0 ? "−" : "+"}10 s`);
      window.setTimeout(() => setFlash(null), 600);
      return;
    }
    if (tapTimer.current) window.clearTimeout(tapTimer.current);
    tapTimer.current = window.setTimeout(() => {
      tapTimer.current = null;
      if (controls && !videoRef.current?.paused) setControls(false);
      else poke();
    }, 300);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        exit();
        return;
      }
      if (isLive) return;
      poke();
      const onButton = (document.activeElement as HTMLElement | null)?.tagName === "BUTTON";
      let handled = true;
      switch (e.key) {
        case " ":
        case "k":
        case "MediaPlayPause":
          togglePlay();
          break;
        case "Enter":
          // On a focused control Enter is that control's click; elsewhere (D-pad OK on the picture) it plays / pauses
          if (onButton) handled = false;
          else togglePlay();
          break;
        case "ArrowLeft":
          seekBy(-10);
          break;
        case "ArrowRight":
          seekBy(10);
          break;
        case "j":
        case "PageDown":
        case "MediaRewind":
          seekBy(-30);
          break;
        case "l":
        case "PageUp":
        case "MediaFastForward":
          seekBy(30);
          break;
        default:
          handled = false;
      }
      if (handled) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLive, controls, length, growing]);

  // Where to start: the resume point (or, after a renewed link, where the old one stopped), once the player can take a seek
  const applyResume = (v: HTMLVideoElement) => {
    const at = seekTo.current;
    if (!(at > 0) || isLive) return;
    if (v.readyState < 1) return;
    const total = isFinite(v.duration) && v.duration > 0 ? v.duration : lenRef.current;
    seekTo.current = 0;
    if (!total || at < total - 30) v.currentTime = at;
  };
  const seekable = !isLive && length > 0;
  const fmt = clockText;

  return (
    <div
      className={`player iptv-player${isLive || controls || paused || state.kind !== "playing" ? " show" : ""}`}
      onMouseMove={isLive ? undefined : poke}
      onClick={() => {
        if (touchClick.current) touchClick.current = false;
        else if (!isLive) poke();
      }}
    >
      <video
        ref={videoRef}
        className="player-video"
        playsInline
        onClick={() => !isLive && !touchClick.current && togglePlay()}
        onPointerUp={onVideoPointerUp}
        onPlay={() => setPaused(false)}
        onPause={() => setPaused(true)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onDurationChange={(e) => setDuration(isFinite(e.currentTarget.duration) ? e.currentTarget.duration : 0)}
        onLoadedMetadata={(e) => applyResume(e.currentTarget)}
        onCanPlay={(e) => applyResume(e.currentTarget)}
        onError={(e) => linkRef.current && !tsRef.current && !hlsRef.current && void onLinkError(linkRef.current, `MediaError ${e.currentTarget.error?.code ?? ""} ${e.currentTarget.error?.message ?? ""}`.trim())}
      />
      {state.kind === "loading" && <div className="spinner" />}
      {state.kind === "full" && state.blocker && <StreamBusy blocker={state.blocker} onRetry={() => start(true)} onClose={exit} />}
      {(state.kind === "error" || (state.kind === "full" && !state.blocker)) && (
        <div className="player-error">
          <p>{state.text}</p>
          {state.kind === "full" && state.connection?.active?.length ? (
            <ul className="iptv-holders">
              {state.connection.active.map((h, i) => (
                <li key={i}>
                  {h.kind === "recording" ? "Recording" : "Watching"} {h.label ?? ""}
                  {h.userName || h.user ? ` · ${h.userName ?? h.user}` : ""}
                </li>
              ))}
            </ul>
          ) : null}
          <div className="buttons">
            {state.kind === "full" && (
              <button className="btn primary" data-nav data-autofocus onClick={() => void start()}>
                Try again
              </button>
            )}
            <button className="btn" data-nav data-autofocus={state.kind === "error" || undefined} onClick={exit}>
              Back
            </button>
          </div>
        </div>
      )}
      <div className="player-top">
        <button className="btn ghost" data-nav onClick={exit} aria-label="Back">
          ‹ Back
        </button>
        <span className="player-title">{title}</span>
        {isLive && state.kind === "playing" && <span className="iptv-live-pill">Live</span>}
        <span className="player-method">via IPTV</span>
      </div>
      {flash && <div className="seek-flash iptv-flash">{flash}</div>}
      {!isLive && state.kind === "playing" && (
        <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
          {seekable && (
            <input
              className="seek"
              type="range"
              min={0}
              max={length}
              step={1}
              value={Math.min(time, length)}
              onChange={(e) => seekTo_(Number(e.target.value))}
              style={{ ["--p" as string]: `${Math.min(100, (time / length) * 100)}%` }}
              aria-label="Seek"
            />
          )}
          <div className="player-row iptv-controls">
            <button className="btn ghost" data-nav onClick={() => seekBy(-30)} aria-label="Back 30 seconds">
              −30s
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(-10)} aria-label="Back 10 seconds">
              −10s
            </button>
            <button className="btn ghost" data-nav data-autofocus onClick={togglePlay}>
              {paused ? "Play" : "Pause"}
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(10)} aria-label="Forward 10 seconds">
              +10s
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(30)} aria-label="Forward 30 seconds">
              +30s
            </button>
            <span className="clock">
              {fmt(time)}
              {seekable ? ` / ${fmt(length)}${growing ? "+" : ""}` : ""}
            </span>
            {growing && (
              <>
                <span className="spacer" />
                <button className="btn ghost" data-nav onClick={jumpLive}>
                  Jump to live
                </button>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
