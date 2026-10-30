// IPTV player (#/iptv/play?channel=…|recording=…|vod=…|series=…&episode=…[&start&stop][&t=title]): asks the companion
// for a 4 h stream link, plays .ts through mpegts.js, .m3u8 through hls.js and files natively. A link answering 410
// stale or 403 expired gets one fresh /play and a retry; 409 shows who holds the connection. Leaving tears the stream
// down at once (the provider may allow a single connection).
import { useCallback, useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import type Mpegts from "mpegts.js";
import { deviceId } from "../../api/jellyfin";
import { play, saveProgress, streamUrl, type Connection, type PlayAnswer } from "../../api/iptv";
import { engineFor, failureText, fallbackFormat, linkFailure, nativeLink, playbackFailureText, playQuery, type Engine, type PlayTarget } from "../../lib/iptvPlay";
import { NativePlayer, nativeIptvPlayer, type NativePlayerResult } from "../../lib/native";
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

export function IptvPlayer(props: { target: PlayTarget; title: string; resume?: number }) {
  return nativeIptvPlayer ? <NativeIptvPlayer {...props} /> : <WebIptvPlayer {...props} />;
}

function WebIptvPlayer({ target, title, resume = 0 }: { target: PlayTarget; title: string; resume?: number }) {
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

const DIRECT_NOTE = "Playing straight from your provider: Jellytip can't see other devices on this connection.";

/** Why the native player gave up, in words (no links: a direct one carries the provider login). */
function nativeFailureText(r: NativePlayerResult): string {
  if (r.httpStatus) return `The stream was refused (${r.httpStatus}).`;
  switch (r.errorCode) {
    case "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED":
    case "ERROR_CODE_DECODER_INIT_FAILED":
      return "This phone can't play this channel's video or audio.";
    case "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED":
    case "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT":
      return "The stream kept dropping. Check the connection and try again.";
    default:
      return "The stream stopped playing.";
  }
}

/**
 * Phone app (plan "IPTV playback on phones", part A): /play here (the 409 pop-up and renewals stay in the web app),
 * then the full-screen ExoPlayer activity plays the link. Progress is saved from its reports; when it closes with an
 * error the link is renewed once (410 stale / 403 expired) or the reason is shown, and the error goes to
 * /v1/debug/report like the web player's.
 */
function NativeIptvPlayer({ target, title, resume = 0 }: { target: PlayTarget; title: string; resume?: number }) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const alive = useRef(true);
  const renewed = useRef(false);
  const isLive = !!target.channel && target.start == null;
  const key = progressKey(target);

  const exit = useCallback(() => back("/livetv"), []);

  // One native session: open the player, save progress while it plays, then act on how it closed
  const session = useCallback(
    async (a: PlayAnswer, resumeMs: number) => {
      const link = nativeLink(a, streamUrl);
      const live = isLive || a.kind === "live";
      debugEvent("iptv-player", "native attach", { player: "exoplayer", mime: a.mime ?? null, kind: a.kind, direct: link.direct, live }, "info");
      const subs = [
        await NativePlayer.addListener("progress", (e) => {
          if (key && e.durationMs > 0 && e.positionMs > 5000) void saveProgress(key, Math.floor(e.positionMs / 1000), Math.floor(e.durationMs / 1000));
        }),
        await NativePlayer.addListener("playerError", (e) => {
          debugEvent("iptv-player", "native player error", { player: "exoplayer", kind: a.kind, direct: link.direct, ...e });
          void sendDebugReport();
        }),
      ];
      let r: NativePlayerResult;
      try {
        r = await NativePlayer.open({ url: link.url, title, mime: a.mime, live, resumeMs, note: link.direct ? DIRECT_NOTE : undefined });
      } catch (e) {
        r = { reason: "error", positionMs: 0, durationMs: 0, error: String((e as Error).message ?? e) };
      } finally {
        subs.forEach((s) => void s.remove());
      }
      if (key && r.durationMs > 0 && r.positionMs > 5000) void saveProgress(key, Math.floor(r.positionMs / 1000), Math.floor(r.durationMs / 1000));
      if (!alive.current) return;
      if (r.reason !== "error") {
        exit();
        return;
      }
      const what = linkFailure(r.httpStatus ?? 0, r.iptvError);
      debugEvent("iptv-player", "native stream failed", {
        player: "exoplayer",
        kind: a.kind,
        mime: a.mime ?? null,
        direct: link.direct,
        errorCode: r.errorCode ?? null,
        error: r.error ?? null,
        status: r.httpStatus ?? 0,
        code: r.iptvError ?? null,
        time: r.positionMs / 1000,
        verdict: what,
      });
      void sendDebugReport();
      if (what === "renew" && !renewed.current) {
        renewed.current = true;
        // eslint-disable-next-line @typescript-eslint/no-use-before-define
        void start(false, live ? 0 : r.positionMs);
        return;
      }
      if (what === "full") setState({ kind: "full", text: "Every connection to this provider is in use." });
      else setState({ kind: "error", text: failureText(r.iptvError, nativeFailureText(r)) });
    },
    // start is declared below and stable for the target
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [isLive, key, title, exit],
  );

  /** `retry`: the second go after stopping a recording; keeps the pop-up up and turns a second refusal into the error. */
  const start = useCallback(
    async (retry = false, resumeMs = resume * 1000): Promise<boolean> => {
      if (!retry) setState({ kind: "loading" });
      const a = await play(playQuery(target, iptvDevice(), "ts", true));
      if (!alive.current) return false;
      if (a.status === 200 && a.data?.url) {
        setState({ kind: "playing" });
        void session(a.data, resumeMs);
        return true;
      }
      const what = linkFailure(a.status, a.data?.code);
      const text = a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`);
      if (retry) setState({ kind: "error", text });
      else if (what === "full") {
        const b = parseBlocker(a.data);
        const popup = b && (canStop(b) || (b.kind === "viewer" && (b.title || b.channel))) ? b : undefined;
        setState({ kind: "full", text: a.data?.error ?? "Every connection to this provider is in use.", connection: a.data?.connection, blocker: popup });
      } else setState({ kind: "error", text: failureText(a.data?.code, text) });
      return false;
    },
    [target, resume, session],
  );

  useEffect(() => {
    alive.current = true;
    renewed.current = false;
    void start();
    return () => {
      alive.current = false;
    };
  }, [start]);

  return (
    <div className="player show iptv-player">
      {(state.kind === "loading" || state.kind === "playing") && <div className="spinner" />}
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
            <button className="btn primary" data-nav data-autofocus onClick={() => void start()}>
              Try again
            </button>
            <button className="btn" data-nav onClick={exit}>
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
        <span className="player-method">via IPTV</span>
      </div>
    </div>
  );
}
