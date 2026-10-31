// IPTV player (#/iptv/play?channel=…|recording=…|vod=…|series=…&episode=…[&start&stop][&t=title]): asks the companion
// for a 4 h stream link, plays .ts through mpegts.js, .m3u8 through hls.js and files natively. A link answering 410
// stale or 403 expired gets one fresh /play and a retry; 409 shows who holds the connection. Leaving tears the stream
// down at once (the provider may allow a single connection).
import { BackArrow } from "../../components/BackArrow";
import { flashNote } from "../../components/TasteButtons";
import { addReminderFor, removeReminder, useReminders } from "../../api/reminderStore";
import { isPre, preGameCard } from "../../lib/preGame";
import { FantasyAlerts, FantasyChip } from "../../components/Fantasy";
import { FlipAlerts } from "../../components/FlipAlerts";
import { LiveDrawer } from "../../components/LiveDrawer";
import { usePrefs } from "../../api/me";
import { MatchInfoButton } from "../../components/MatchInfo";
import { Scrubber } from "../../components/Scrubber";
import { useCallback, useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import type Mpegts from "mpegts.js";
import { deviceId, getSession } from "../../api/jellyfin";
import { COMPANION_BASE } from "../../api/companion";
import { openMatchInfo } from "../../components/MatchInfo";
import { matchDetailCached } from "../../api/matchInfo";
import { refreshRecs } from "../../api/recStore";
import { matchupTitle } from "../../lib/matchInfo";
import { addRecording, play, recordingById, saveProgress, streamUrl, type Connection, type PlayAnswer } from "../../api/iptv";
import { clampSeek, clockText, recordingLength, type RecMeta } from "../../lib/recPlay";
import { engineFor, failureText, fallbackFormat, linkFailure, nativeLink, playbackFailureText, playQuery, livePath, liveExtrasQuery, targetPath, type LiveExtras, type Engine, type PlayTarget } from "../../lib/iptvPlay";
import { NativePlayer, nativeIptvPlayer, type NativePlayerResult } from "../../lib/native";
import { StreamBusy } from "../../components/StreamBusy";
import { canStop, parseBlocker, type Blocker } from "../../lib/holders";
import { debugEvent, sendDebugReport } from "../../lib/debugLog";
import { back, go } from "../../lib/router";
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

const isLiveTarget = (t: PlayTarget) => !!t.channel && t.start == null;

function progressKey(t: PlayTarget): string | null {
  if (t.recording) return `rec:${t.recording}`;
  if (t.episode) return t.episode;
  if (t.vod) return t.vod;
  return null;
}

export function IptvPlayer(props: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras }) {
  return nativeIptvPlayer ? <NativeIptvPlayer {...props} /> : <WebIptvPlayer {...props} />;
}

const STALL_MS = 10_000;

function WebIptvPlayer({ target, title, resume = 0, extras }: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras }) {
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
  // Live stall: no picture moving for 10 s offers the same event on another channel
  const lastMove = useRef(Date.now());
  const quietUntil = useRef(0);
  const [stalled, setStalled] = useState(false);
  // A "From kick-off" catch-up (the programme was still on air when it started) ends at the moment it was pressed: then back to live
  const kickoffRun = useRef(!isLiveTarget(target) && !!target.channel && target.stop != null && target.stop * 1000 > Date.now());
  const loadDone = useRef(false);
  const returned = useRef(false);
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
    void saveProgress(key, Math.floor(v.currentTime), Math.floor(dur), target.episode ? target.series : undefined);
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
        p.on(mpegts.Events.LOADING_COMPLETE, () => (loadDone.current = true));
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

  const backToLive = useCallback(() => {
    if (returned.current || !target.channel) return;
    returned.current = true;
    flashNote("Back to live");
    go(livePath(target.channel, title, extras), true);
  }, [target.channel, title, extras]);
  useEffect(() => {
    if (!kickoffRun.current || state.kind !== "playing") return;
    // mpegts has no "ended": the stream finished loading and playback has stopped moving
    const t = setInterval(() => {
      if (loadDone.current && Date.now() - lastMove.current >= 3000) backToLive();
    }, 1000);
    return () => clearInterval(t);
  }, [state.kind, backToLive]);
  const alt = extras?.alternates?.[0];
  useEffect(() => {
    if (!isLive || !alt || state.kind !== "playing") return;
    lastMove.current = Date.now();
    const t = setInterval(() => {
      const v = videoRef.current;
      if (v && !v.paused && Date.now() - lastMove.current >= STALL_MS && Date.now() > quietUntil.current) setStalled(true);
    }, 1000);
    return () => clearInterval(t);
  }, [isLive, alt, state.kind]);
  const switchTo = () => {
    if (!alt) return;
    const rest = [...(extras?.alternates ?? []).slice(1), { id: target.channel!, name: title }];
    go(livePath(alt.id, alt.name, { alternates: rest }), true);
  };
  // Pre-game: before the real start a non-blocking card explains the holding screen; any key or tap brings it back after its 10 s
  const startsAt = isLive ? extras?.startsAt ?? null : null;
  const [nowS, setNowS] = useState(() => Date.now() / 1000);
  const [cardOn, setCardOn] = useState(true);
  const [others, setOthers] = useState(false);
  const started = useRef(false);
  useEffect(() => {
    if (!startsAt) return;
    const t = setInterval(() => {
      const n = Date.now() / 1000;
      setNowS(n);
      if (n >= startsAt && !started.current) {
        started.current = true;
        flashNote("Started");
      }
    }, 1000);
    return () => clearInterval(t);
  }, [startsAt]);
  const preNow = isPre(startsAt, nowS);
  useEffect(() => {
    if (!preNow) return;
    let timer: number | undefined;
    const show = () => {
      setCardOn(true);
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setCardOn(false), 10_000);
    };
    show();
    document.addEventListener("keydown", show);
    document.addEventListener("pointerdown", show);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("keydown", show);
      document.removeEventListener("pointerdown", show);
    };
  }, [preNow]);
  const [drawer, setDrawer] = useState(false);
  const sportPrefs = usePrefs({ defer: true }).prefs?.sport;
  const spoilers = !!sportPrefs?.spoilers;
  const flipAlerts = sportPrefs ? sportPrefs.flipAlerts : false;
  const { items: remItems, available: remAvailable } = useReminders();
  const remId = extras?.eventKey ? remItems?.find((r) => r.event.key === extras.eventKey)?.id ?? null : null;
  const [remBusy, setRemBusy] = useState(false);
  const toggleRemind = async () => {
    if (!startsAt || !extras?.eventKey || !target.channel) return;
    setRemBusy(true);
    const err =
      remId != null
        ? await removeReminder(remId)
        : await addReminderFor({ key: extras.eventKey, title, start: startsAt, stop: startsAt + 3 * 3600, channelId: target.channel, channelName: title });
    setRemBusy(false);
    flashNote(err ?? (remId != null ? "Reminder removed" : "We'll remind you before it starts"));
  };

  const kickoff = isLive && extras?.kickoff && Date.now() / 1000 < extras.kickoff.stop ? extras.kickoff : null;

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
        onEnded={() => kickoffRun.current && backToLive()}
        onPause={() => setPaused(true)}
        onTimeUpdate={(e) => {
          lastMove.current = Date.now();
          setStalled(false);
          setTime(e.currentTarget.currentTime);
        }}
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
        <BackArrow onClick={exit} />
        <span className="player-title">{title}</span>
        {isLive && state.kind === "playing" && <span className="iptv-live-pill">Live</span>}
        {isLive && <MatchInfoButton id={extras?.matchId} />}
        <FantasyChip matchId={extras?.matchId} active={isLive && !extras?.replay && state.kind === "playing"} />
        {extras?.matchId && state.kind === "playing" && (
          <button className="btn small" data-nav aria-expanded={drawer} onClick={() => setDrawer((v) => !v)}>
            Live
          </button>
        )}
        {kickoff && state.kind === "playing" && (
          <button className="btn small" data-nav onClick={() => go(`${targetPath({ channel: target.channel, start: kickoff.start, stop: kickoff.stop })}&t=${encodeURIComponent(title)}${liveExtrasQuery(extras)}`, true)}>
            From kick-off
          </button>
        )}
        <span className="player-method">via IPTV</span>
      </div>
      {flash && <div className="seek-flash iptv-flash">{flash}</div>}
      <FantasyAlerts matchId={extras?.matchId} active={isLive && !extras?.replay && state.kind === "playing"} />
      <FlipAlerts matchId={extras?.matchId} active={isLive && !extras?.replay && flipAlerts && state.kind === "playing"} drawerOpen={drawer} />
      {drawer && extras?.matchId && <LiveDrawer matchId={extras.matchId} liveNow={isLive && !extras?.replay} spoilers={spoilers} onClose={() => setDrawer(false)} />}
      {preNow && startsAt && cardOn && state.kind !== "error" && (
        <div className="pregame-card" role="status">
          <span>{preGameCard(startsAt, nowS)}</span>
          <div className="buttons">
            {remAvailable && extras?.eventKey && (
              <button className="btn small" data-nav aria-pressed={remId != null} disabled={remBusy} onClick={() => void toggleRemind()}>
                {remId != null ? "Reminder set ✓" : "Remind me"}
              </button>
            )}
            {(extras?.alternates?.length ?? 0) > 0 && (
              <button className="btn small" data-nav aria-expanded={others} onClick={() => setOthers((v) => !v)}>
                Other channels
              </button>
            )}
          </div>
          {others && (
            <div className="buttons">
              {extras!.alternates!.map((a, i) => (
                <button
                  key={a.id}
                  className="btn small"
                  data-nav
                  onClick={() => go(livePath(a.id, a.name, { alternates: [...extras!.alternates!.filter((_, j) => j !== i), { id: target.channel!, name: title }], startsAt, eventKey: extras!.eventKey }), true)}
                >
                  {a.name}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {stalled && alt && state.kind === "playing" && (
        <div className="iptv-stall" role="alert">
          <span>Not loading.</span>
          <button className="btn primary small" data-nav data-autofocus onClick={switchTo}>
            Switch to {alt.name}
          </button>
          <button
            className="btn small"
            data-nav
            onClick={() => {
              quietUntil.current = Date.now() + 30_000;
              setStalled(false);
            }}
          >
            Keep waiting
          </button>
        </div>
      )}
      {!isLive && state.kind === "playing" && (
        <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
          {seekable && (
            <Scrubber time={time} max={length} fmt={fmt} onActivity={poke} onSeek={seekTo_} />
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
function NativeIptvPlayer({ target, title, resume = 0, extras }: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras }) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const alive = useRef(true);
  const renewed = useRef(false);
  const switchTo = useRef<string | null>(null);
  // The user's alert settings go to the native player, which shows its own toasts and notifications
  const userPrefs = usePrefs({ defer: true }).prefs;
  const sportPrefsRef = useRef<{ fantasyAlerts?: boolean; flipAlerts?: boolean; panelStyle?: "docked" | "floating" }>({});
  sportPrefsRef.current = userPrefs ? { fantasyAlerts: userPrefs.fantasy.alerts, flipAlerts: userPrefs.sport.flipAlerts, panelStyle: userPrefs.sport.panelStyle } : {};
  const infoFor = useRef<string | null>(null);
  const isLive = !!target.channel && target.start == null;
  const key = progressKey(target);

  const exit = useCallback(() => back(target.recording ? "/recordings" : "/livetv"), [target.recording]);

  // One native session: open the player, save progress while it plays, then act on how it closed
  const session = useCallback(
    async (a: PlayAnswer, resumeMs: number) => {
      const link = nativeLink(a, streamUrl);
      const live = isLive || a.kind === "live";
      debugEvent("iptv-player", "native attach", { player: "exoplayer", mime: a.mime ?? null, kind: a.kind, direct: link.direct, live }, "info");
      const subs = [
        await NativePlayer.addListener("progress", (e) => {
          if (key && e.durationMs > 0 && e.positionMs > 5000) void saveProgress(key, Math.floor(e.positionMs / 1000), Math.floor(e.durationMs / 1000), target.episode ? target.series : undefined);
        }),
        await NativePlayer.addListener("playerError", (e) => {
          debugEvent("iptv-player", "native player error", { player: "exoplayer", kind: a.kind, direct: link.direct, ...e });
          void sendDebugReport();
        }),
        // The player asks for a link when it fails over, starts over from kick-off or goes back to live; links never travel up front
        await NativePlayer.addListener("needUrl", (e) => {
          void (async () => {
            const t: PlayTarget = e.reason === "kickoff" ? { channel: e.channelId, start: e.start, stop: e.stop } : { channel: e.channelId };
            const r2 = await play(playQuery(t, iptvDevice(), "ts", true));
            if (r2.status === 200 && r2.data?.url) {
              await NativePlayer.provideUrl({ requestId: e.requestId, url: nativeLink(r2.data, streamUrl).url, mime: r2.data.mime });
            } else {
              const full = linkFailure(r2.status, r2.data?.code) === "full";
              await NativePlayer.provideUrl({ requestId: e.requestId, error: full ? "full" : (r2.data?.error ?? `Server said ${r2.status}`) });
            }
          })().catch(() => NativePlayer.provideUrl({ requestId: e.requestId, error: "unreachable" }).catch(() => undefined));
        }),
        // Pre-game card buttons: Remind me sets the reminder here; Other channels asks to reopen on the pick when the player closes
        await NativePlayer.addListener("pregameAction", (e) => {
          if (e.action === "remind" && extras?.eventKey && extras.startsAt) {
            void addReminderFor({ key: extras.eventKey, title, start: extras.startsAt, stop: extras.startsAt + 3 * 3600, channelId: e.channelId ?? target.channel!, channelName: title }).then((err) =>
              flashNote(err ?? "We'll remind you before it starts"),
            );
          } else if (e.action === "channel" && e.channelId) switchTo.current = e.channelId;
        }),
        // The player's ⓘ: it stays open; the match info shows when it closes
        await NativePlayer.addListener("matchInfo", (e) => {
          infoFor.current = e.matchId;
        }),
        // Portrait Info tab: Remind me / Record for the match (the same handlers as the web modal)
        await NativePlayer.addListener("matchAction", (e) => {
          void (async () => {
            const m = await matchDetailCached(e.matchId);
            if (!m || m === "missing") return flashNote("Couldn't find that match.");
            const f = m.fixture;
            const channel = e.channelId ?? target.channel;
            if (!channel || !f.startsAt) return flashNote("Nothing to schedule yet.");
            if (e.action === "remind") {
              const err = await addReminderFor({ key: f.id, title: matchupTitle(f), start: f.startsAt, stop: f.startsAt + 3 * 3600, channelId: channel, channelName: title });
              flashNote(err ?? "We'll remind you before it starts");
            } else {
              const a = await addRecording(channel, f.startsAt);
              if (a.status === 200 || a.status === 201) {
                void refreshRecs(true);
                flashNote("Recording scheduled. It'll appear on Home.");
              } else flashNote(a.data?.error ?? `Server said ${a.status}`);
            }
          })();
        }),
        // The player became the mini player: leave the player screen for the one before it. The session (this open() call and its
        // listeners) stays alive; when it ends later the component is gone (alive is false) and nothing navigates.
        await NativePlayer.addListener("pipChanged", (e) => {
          if (e.active && alive.current) exit();
        }),
        await NativePlayer.addListener("channelSwitch", (e) => debugEvent("iptv-player", "native channel switch", { from: e.from ?? null, to: e.to }, "info")),
        await NativePlayer.addListener("failover", (e) => debugEvent("iptv-player", "native failover", { from: e.from, to: e.to, why: e.why }, "info")),
      ];
      let r: NativePlayerResult;
      try {
        r = await NativePlayer.open({
          url: link.url,
          title,
          mime: a.mime,
          live,
          growing: !live && !!a.growing,
          resumeMs,
          note: link.direct ? DIRECT_NOTE : undefined,
          // The portrait panel for anything that is not a match (description, what's next), and the user's alert settings
          description: extras?.desc ?? undefined,
          upNext: extras?.upNext,
          fantasyAlerts: sportPrefsRef.current?.fantasyAlerts,
          flipAlerts: sportPrefsRef.current?.flipAlerts,
          panelStyle: sportPrefsRef.current?.panelStyle,
          // Live: the channel, its event's other channels and the programme on now (kick-off); the player asks for links itself
          ...(live && target.channel
            ? {
                channelId: target.channel,
                alternates: extras?.alternates?.map((x) => ({ id: x.id, name: x.name })),
                programmeStart: extras?.kickoff?.start,
                programmeStop: extras?.kickoff?.stop,
                // The match behind the listing: ⓘ, and the native Live drawer (which polls the companion itself)
                ...(extras?.matchId && getSession()?.token ? { matchId: extras.matchId, apiBase: COMPANION_BASE, token: getSession()!.token } : {}),
                // Before the real start the native overlay shows the pre-game card
                ...(isPre(extras?.startsAt, Date.now() / 1000)
                  ? { phase: "pre" as const, startsAt: new Date(extras!.startsAt! * 1000).toISOString(), altChannels: extras?.alternates?.map((x) => ({ id: x.id, name: x.name })) }
                  : {}),
              }
            : {}),
        });
      } catch (e) {
        r = { reason: "error", positionMs: 0, durationMs: 0, error: String((e as Error).message ?? e) };
      } finally {
        subs.forEach((s) => void s.remove());
      }
      if (key && r.durationMs > 0 && r.positionMs > 5000) void saveProgress(key, Math.floor(r.positionMs / 1000), Math.floor(r.durationMs / 1000), target.episode ? target.series : undefined);
      // Another open() took over this player (a new match, the mini player): that session carries on, so this one ends quietly
      if (r.reason === "replaced") return;
      if (!alive.current) return;
      // "Other channels" on the pre-game card: reopen on the one picked (the rest of the event's channels, and this one, stay as alternates)
      if (switchTo.current && target.channel) {
        const pick = extras?.alternates?.find((x) => x.id === switchTo.current);
        switchTo.current = null;
        if (pick) {
          go(livePath(pick.id, pick.name, { alternates: [...(extras?.alternates ?? []).filter((x) => x.id !== pick.id), { id: target.channel, name: title }], startsAt: extras?.startsAt, eventKey: extras?.eventKey }), true);
          return;
        }
      }
      if (r.reason !== "error") {
        exit();
        // ⓘ was tapped in the player: show the match now that the web app is back
        const mid = infoFor.current;
        infoFor.current = null;
        if (mid) window.setTimeout(() => openMatchInfo(mid), 350);
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

  // Open the native player once per mount. `target` is a new object on every App render, so `start` changes identity often;
  // re-running it would call open() again and replace (or reopen) a player that is playing, or floating as the mini player.
  const opened = useRef(false);
  useEffect(() => {
    alive.current = true;
    renewed.current = false;
    if (!opened.current) {
      opened.current = true;
      void start();
    }
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
        <BackArrow onClick={exit} />
        <span className="player-title">{title}</span>
        <span className="player-method">via IPTV</span>
      </div>
    </div>
  );
}
