// IPTV player (#/iptv/play?channel=…|recording=…|vod=…|series=…&episode=…[&start&stop][&t=title]): asks the companion
// for a 4 h stream link, plays .ts through mpegts.js, .m3u8 through hls.js and files natively. A link answering 410
// stale or 403 expired gets one fresh /play and a retry; 409 shows who holds the connection. Leaving tears the stream
// down at once (the provider may allow a single connection).
import { useCallback, useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import type Mpegts from "mpegts.js";
import { deviceId } from "../../api/jellyfin";
import { play, saveProgress, streamUrl, type Connection, type PlayAnswer } from "../../api/iptv";
import { engineFor, failureText, fallbackFormat, linkFailure, playbackFailureText, playQuery, type Engine, type PlayTarget } from "../../lib/iptvPlay";
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

type State = { kind: "loading" } | { kind: "playing" } | { kind: "full"; text: string; connection?: Connection } | { kind: "error"; text: string };

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
    if (!key || !v || !isFinite(v.duration) || v.duration <= 0 || v.currentTime < 5) return;
    void saveProgress(key, Math.floor(v.currentTime), Math.floor(v.duration));
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

  const start = useCallback(async () => {
    setState({ kind: "loading" });
    if (isLive && fmtRef.current === "ts" && !(await loadMpegts()).default.getFeatureList().mseLivePlayback) fmtRef.current = "m3u8";
    const a = await play(playQuery(target, iptvDevice(), fmtRef.current));
    if (!alive.current) return;
    if (a.status === 200 && a.data?.url) {
      setAnswer(a.data);
      setState({ kind: "playing" });
      await attach(a.data);
      return;
    }
    const what = linkFailure(a.status, a.data?.code);
    if (what === "full") setState({ kind: "full", text: a.data?.error ?? "Every connection to this provider is in use.", connection: a.data?.connection });
    else setState({ kind: "error", text: failureText(a.data?.code, a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`)) });
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

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) void v.play().catch(() => undefined);
    else v.pause();
  };
  const exit = () => {
    teardown();
    back("/livetv");
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        exit();
      } else if (e.key === " " && !isLive) {
        e.preventDefault();
        togglePlay();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLive]);

  const seekable = !isLive && duration > 0;
  const fmt = (s: number) => `${Math.floor(s / 3600) ? `${Math.floor(s / 3600)}:` : ""}${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(Math.floor(s % 60)).padStart(2, "0")}`;

  return (
    <div className="player show iptv-player">
      <video
        ref={videoRef}
        className="player-video"
        playsInline
        onClick={() => !isLive && togglePlay()}
        onPlay={() => setPaused(false)}
        onPause={() => setPaused(true)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onDurationChange={(e) => setDuration(isFinite(e.currentTarget.duration) ? e.currentTarget.duration : 0)}
        onLoadedMetadata={(e) => {
          const at = seekTo.current;
          seekTo.current = 0;
          if (at > 0 && !isLive && at < (e.currentTarget.duration || Infinity) - 30) e.currentTarget.currentTime = at;
        }}
        onError={(e) => linkRef.current && !tsRef.current && !hlsRef.current && void onLinkError(linkRef.current, `MediaError ${e.currentTarget.error?.code ?? ""} ${e.currentTarget.error?.message ?? ""}`.trim())}
      />
      {state.kind === "loading" && <div className="spinner" />}
      {(state.kind === "error" || state.kind === "full") && (
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
      {!isLive && state.kind === "playing" && (
        <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
          {seekable && (
            <input
              className="seek"
              type="range"
              min={0}
              max={duration}
              step={1}
              value={Math.min(time, duration)}
              onChange={(e) => {
                if (videoRef.current) videoRef.current.currentTime = Number(e.target.value);
              }}
              style={{ ["--p" as string]: `${(time / duration) * 100}%` }}
              aria-label="Seek"
            />
          )}
          <div className="player-row">
            <button className="btn ghost" data-nav onClick={togglePlay}>
              {paused ? "Play" : "Pause"}
            </button>
            <span className="clock">
              {fmt(time)}
              {seekable ? ` / ${fmt(duration)}` : ""}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
