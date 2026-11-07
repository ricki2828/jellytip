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
import { DelayChip } from "../../components/DelayChip";
import { Scorebug } from "../../components/Companion/Scorebug";
import { Panel, useDropWatch, usePhonePortrait } from "../../components/Companion/Panel";
import { useLiveFeed } from "../../components/Companion/useLiveFeed";
import { calibrateMessage, useRevealClock } from "../../lib/delay";
import { autoCloseDecision, autoOpenDecision, DROP_KEY, panelKeyAction, panelModeFor, stepTab, type OpenReason } from "../../lib/companion";
import { usePrefs } from "../../api/me";
import { usePlayWatch } from "../../api/signals";
import type { WatchMeta } from "../../lib/signals";
import { MatchInfoButton } from "../../components/MatchInfo";
import { TimeBarClock, TimeBarScrubber } from "../../components/TimeBar";
import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import { TrackMenu } from "../../components/TrackMenu";
import { useSubtitlePrefs } from "../../lib/useSubtitlePrefs";
import { audioOptionLabel } from "../../lib/trackMenu";
import { replayDone, replayTarget, startReplay, type Replay } from "../../lib/captionReplay";
import type Hls from "hls.js";
import type Mpegts from "mpegts.js";
import { deviceId, getSession } from "../../api/jellyfin";
import { COMPANION_BASE } from "../../api/companion";
import { openMatchInfo } from "../../components/MatchInfo";
import { matchDetailCached } from "../../api/matchInfo";
import { refreshRecs } from "../../api/recStore";
import { matchupTitle } from "../../lib/matchInfo";
import { nextChannelPath, rejoinPlan } from "../../api/channels";
import { addRecording, play, recordingById, saveProgress, streamUrl, type Connection, type PlayAnswer } from "../../api/iptv";
import { clampSeek, clockText, recordingLength, type RecMeta } from "../../lib/recPlay";
import { engineFor, failureText, fallbackFormat, linkFailure, nativeLink, playbackFailureText, playQuery, livePath, liveExtrasQuery, shouldShowLiveExtras, targetPath, type LiveExtras, type Engine, type PlayTarget } from "../../lib/iptvPlay";
import { NativePlayer, nativeIptvPlayer, type NativePlayerResult } from "../../lib/native";
import { StreamBusy } from "../../components/StreamBusy";
import { canStop, parseBlocker, type Blocker } from "../../lib/holders";
import { debugEvent, sendDebugReport } from "../../lib/debugLog";
import { switchExtras } from "../../lib/companion";
import { initialRecovery, isRetryable, permanentReason, recoveryCopy, shouldSurfaceLiveStall, step as recoveryStep, HIDDEN_STOP_MS, QUIET_MS, STABLE_MS, VOD_IDLE_MS, beginExclusive, claimEngine, type RecoveryEvent } from "../../lib/streamRecovery";
import { back, go } from "../../lib/router";
import { hlsChoices, nativeChoices, nativeSubtitlesOff, makePick, matchPick, pickIptvTracks, type SubtitlePick, type TrackChoice, type TrackPick } from "../../lib/iptvTracks";
import { getAlwaysSubs } from "../../lib/trackMemory";
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

/** Taste signal target: an IPTV film or episode (the show, not the episode). Live, recordings and sport send nothing. */
function signalMeta(t: PlayTarget, channelId?: string | null): WatchMeta | null {
  if (t.recording || (t.channel && t.start == null)) return null;
  if (t.vod) return { kind: "movie", iptvId: t.vod, isFilm: true, channel: !!channelId };
  if (t.episode && t.series) return { kind: "series", iptvId: t.series, isFilm: false, channel: !!channelId };
  return null;
}

const isLiveTarget = (t: PlayTarget) => !!t.channel && t.start == null;

function progressKey(t: PlayTarget): string | null {
  if (t.recording) return `rec:${t.recording}`;
  if (t.episode) return t.episode;
  if (t.vod) return t.vod;
  return null;
}

/** `?worker=1` (query or hash query): mpegts demux in a worker plus SourceBuffer cleanup. Default off until A/B'd on the TV. */
const WORKER_FLAG = /[?&]worker=1(&|$)/.test(location.search + location.hash);

/**
 * @param channelId a personal scheduled channel (#/channels) is playing this film: it follows the clock, so no progress is
 * reported (nothing lands in the watch history) and, when it ends, the player moves to what is on next. Nothing else about
 * playback, the connection lease or recovery differs.
 */
export function IptvPlayer(props: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras; channelId?: string | null; offSchedule?: boolean }) {
  // Dev/test counter: root renders (plan budget <= 1/s while live). Not a React state; costs one add.
  if (import.meta.env.DEV) (window as unknown as { __jtRenders?: number }).__jtRenders = ((window as unknown as { __jtRenders?: number }).__jtRenders ?? 0) + 1;
  return nativeIptvPlayer ? <NativeIptvPlayer {...props} /> : <WebIptvPlayer {...props} />;
}

function destroyTs(ts: Mpegts.Player) {
  try {
    ts.pause();
    ts.unload();
    ts.detachMediaElement();
    ts.destroy();
  } catch {
    // already gone
  }
}

const STALL_MS = 10_000;
const VOD_STALL_MS = 15_000;
/** Still no movement this long after a stall is shown: reconnect on our own. */
const RECONNECT_AFTER_MS = 6_000;

function WebIptvPlayer({ target, title, resume = 0, extras, channelId, offSchedule = false }: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras; channelId?: string | null; offSchedule?: boolean }) {
  const curId = target.vod ?? target.episode; // the channel item playing: a film, or an IPTV episode
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const tsRef = useRef<Mpegts.Player | null>(null);
  const linkRef = useRef<string | null>(null);
  const answerRef = useRef<PlayAnswer | null>(null);
  const engineRef = useRef<Engine>("native");
  const [playerEngine, setPlayerEngine] = useState<Engine>("native");
  const fmtRef = useRef<"ts" | "m3u8">("ts");
  const watchRef = useRef<number | undefined>(undefined);
  const renewed = useRef(false);
  const attemptStarted = useRef(Date.now());
  const firstFrame = useRef(false);
  const lastTime = useRef(0);
  const failingUrl = useRef<string | null>(null);
  const rebufferStarted = useRef<number | null>(null);
  const rebufferCount = useRef(0);
  const stallReported = useRef(false);
  const alive = useRef(true);
  const [state, setState] = useState<State>({ kind: "loading" });
  const [answer, setAnswer] = useState<PlayAnswer | null>(null);
  const [paused, setPaused] = useState(false);
  const [duration, setDuration] = useState(0);
  const isLive = !!target.channel && target.start == null;
  const [meta, setMeta] = useState<RecMeta | null>(null);
  const [controls, setControls] = useState(true);
  const [tracksOpen, setTracksOpen] = useState(false);
  const { prefs: subPrefs, setPrefs: setSubPrefs, vars: subVars } = useSubtitlePrefs();
  const replayRef = useRef<Replay | null>(null);
  const tracksOpenRef = useRef(false);
  tracksOpenRef.current = tracksOpen;
  const [audioChoices, setAudioChoices] = useState<TrackChoice[]>([]);
  const [subtitleChoices, setSubtitleChoices] = useState<TrackChoice[]>([]);
  const [subtitleOff, setSubtitleOff] = useState(true);
  // Start-of-playback language prefs: applied once per start, and never over a pick made in the track menu
  const autoTracks = useRef({ done: false, manual: false });
  // Manual picks outlive a recovery reconnect (matched by lang/name on the new engine's lists); cleared with a new target
  const manualPick = useRef<{ audio?: TrackPick; subtitle?: SubtitlePick }>({});
  const reapply = useRef({ audio: false, subtitle: false });
  const audioChoicesRef = useRef<TrackChoice[]>([]);
  const subtitleChoicesRef = useRef<TrackChoice[]>([]);
  audioChoicesRef.current = audioChoices;
  subtitleChoicesRef.current = subtitleChoices;
  const langPrefs = usePrefs({ defer: true }).prefs?.playback;
  const hideTimer = useRef<number | null>(null);
  const lenRef = useRef(0);
  const watch = usePlayWatch(signalMeta(target, channelId), () => lenRef.current, curId ?? "");
  const growRef = useRef(false);
  // Live stall: no picture moving for 10 s offers the same event on another channel
  const lastMove = useRef(Date.now());
  const quietUntil = useRef(0);
  /** Last buffering / seek / network activity on the element (VOD stalls are not escalated while it is still loading). */
  const lastNet = useRef(0);
  const playingSince = useRef<number | null>(null);
  const recRef = useRef(initialRecovery(!!target.channel && target.start == null));
  const [rec, setRec] = useState(recRef.current);
  const openTimer = useRef<number | undefined>(undefined);
  const hiddenTimer = useRef<number | undefined>(undefined);
  const gen = useRef(0);
  const dispatchRef = useRef<(e: RecoveryEvent) => void>(() => undefined);
  const startRef = useRef<(retry?: boolean, recovering?: boolean) => Promise<boolean>>(async () => false);
  const exitRef = useRef<() => void>(() => undefined);
  const retrying = rec.phase === "reconnecting" || rec.phase === "switched";
  // A "From kick-off" catch-up (the programme was still on air when it started) ends at the moment it was pressed: then back to live
  const kickoffRun = useRef(!isLiveTarget(target) && !!target.channel && target.stop != null && target.stop * 1000 > Date.now());
  const loadDone = useRef(false);
  const returned = useRef(false);
  // Where to start once the media is known: the resume point, then (after a renewed link) where the old one stopped
  const seekTo = useRef(resume);

  const refreshTracks = useCallback(() => {
    const h = hlsRef.current;
    if (h) {
      setAudioChoices(hlsChoices(h.audioTracks, h.audioTrack));
      setSubtitleChoices(hlsChoices(h.subtitleTracks, h.subtitleTrack));
      setSubtitleOff(h.subtitleTrack < 0);
      return;
    }
    if (engineRef.current === "mpegts") { setAudioChoices([]); setSubtitleChoices([]); return; }
    const v = videoRef.current as (HTMLVideoElement & { audioTracks?: ArrayLike<{ label?: string; language?: string; enabled: boolean }> }) | null;
    setAudioChoices(nativeChoices(v?.audioTracks ?? [], false));
    setSubtitleChoices(nativeChoices(v?.textTracks ?? [], true));
    setSubtitleOff(nativeSubtitlesOff(v?.textTracks ?? []));
  }, []);

  /** Subtitle selection by track index (-1 = off): hls.js track, else the native text track. */
  const selectSubtitle = (index: number) => {
    const h = hlsRef.current;
    if (h) h.subtitleTrack = index;
    else Array.from(videoRef.current?.textTracks ?? []).forEach((track, i) => { if (track.kind === "subtitles" || track.kind === "captions") track.mode = i === index ? "showing" : "disabled"; });
    refreshTracks();
  };
  const selectAudio = (index: number) => {
    const h = hlsRef.current;
    if (h) h.audioTrack = index;
    else { const v = videoRef.current as (HTMLVideoElement & { audioTracks?: ArrayLike<{ enabled: boolean }> }) | null; if (v?.audioTracks) Array.from(v.audioTracks).forEach((track, i) => { track.enabled = i === index; }); }
    refreshTracks();
  };
  const currentSubtitle = subtitleOff ? -1 : subtitleChoices.find((c) => c.active)?.index ?? -1;
  const seekableStart = () => { const sk = videoRef.current?.seekable; return sk && sk.length ? sk.start(0) : 0; };
  /** "What did they say?": back ~10 s with captions on, previous subtitle state restored once that span has played */
  const replayCaptions = () => {
    const v = videoRef.current;
    if (!v) return;
    const r = startReplay(v.currentTime, currentSubtitle, subtitleChoices[0]?.index ?? null, seekableStart(), replayRef.current);
    if (!r) return;
    replayRef.current = r.replay;
    if (r.show != null) selectSubtitle(r.show);
    v.currentTime = r.seekTo;
    setTracksOpen(false);
    poke();
  };

  const onTracksOpenChange = useCallback((o: boolean) => {
    setTracksOpen(o);
    if (o) { setControls(true); if (hideTimer.current) window.clearTimeout(hideTimer.current); refreshTracks(); }
  }, [refreshTracks]);

  const teardown = useCallback(() => {
    setTracksOpen(false);
    replayRef.current = null;
    setAudioChoices([]); setSubtitleChoices([]);
    setPlayerEngine("native");
    window.clearTimeout(watchRef.current);
    hlsRef.current?.destroy();
    hlsRef.current = null;
    const ts = tsRef.current;
    tsRef.current = null;
    if (ts) destroyTs(ts);
    const v = videoRef.current;
    if (v) {
      v.removeAttribute("src");
      v.load();
    }
    linkRef.current = null;
  }, []);

  const finishBuffer = useCallback(() => {
    if (rebufferStarted.current == null) return;
    debugEvent("iptv-player", "rebuffer ended", { durationMs: Date.now() - rebufferStarted.current, count: rebufferCount.current, player: engineRef.current, format: fmtRef.current }, "info");
    rebufferStarted.current = null;
  }, []);

  const markMoving = useCallback(() => {
    if (recRef.current.phase !== "playing") dispatchRef.current({ t: "recovered" });
  }, []);

  // Recovery: the pure machine decides, this runs its effects. `release` always completes (teardown) before `open` runs.
  const dispatchRec = (e: RecoveryEvent) => {
    const prev = recRef.current;
    const { state: next, effects } = recoveryStep(prev, e);
    recRef.current = next;
    setRec(next);
    if (prev.phase !== next.phase || e.t !== "recovered")
      debugEvent("iptv-player", "recovery", { event: e.t, from: prev.phase, to: next.phase, attempt: next.attempt, max: next.max, conn: next.conn, format: next.format }, "info");
    if (prev.switched && prev.phase !== "playing" && next.phase === "playing") flashNote("Switched to a backup stream");
    for (const f of effects) {
      if (f.do === "cancelTimers") window.clearTimeout(openTimer.current);
      else if (f.do === "release") {
        const mine = ++gen.current;
        window.clearTimeout(openTimer.current);
        finishBuffer();
        teardown();
        queueMicrotask(() => { if (alive.current && gen.current === mine) dispatchRef.current({ t: "released" }); });
      } else if (f.do === "open") {
        const my = gen.current;
        window.clearTimeout(openTimer.current);
        openTimer.current = window.setTimeout(() => {
          if (!alive.current || gen.current !== my) return;
          fmtRef.current = f.format;
          seekTo.current = f.resumeAt ?? 0;
          startRef.current(false, true).then(
            (ok) => { if (ok && alive.current && gen.current === my) dispatchRef.current({ t: "opened" }); },
            () => { if (alive.current && gen.current === my) dispatchRef.current({ t: "fault", now: Date.now(), pos: 0, canHls: false }); },
          );
        }, f.delayMs);
      } else if (f.do === "exit") exitRef.current();
    }
  };
  dispatchRef.current = dispatchRec;
  const copy = recoveryCopy(rec);

  // Network and tab awareness for recovery
  useEffect(() => {
    const on = () => dispatchRef.current({ t: "online" });
    const off = () => dispatchRef.current({ t: "offline" });
    const vis = () => {
      window.clearTimeout(hiddenTimer.current);
      if (document.hidden) {
        dispatchRef.current({ t: "hidden", now: Date.now() });
        hiddenTimer.current = window.setTimeout(() => dispatchRef.current({ t: "hiddenTimeout" }), HIDDEN_STOP_MS);
      } else dispatchRef.current({ t: "visible", now: Date.now() });
    };
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    document.addEventListener("visibilitychange", vis);
    if (!navigator.onLine) off();
    return () => {
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
      document.removeEventListener("visibilitychange", vis);
      window.clearTimeout(hiddenTimer.current);
    };
  }, []);

  const report = useCallback(() => {
    const key = progressKey(target);
    const v = videoRef.current;
    const dur = isFinite(v?.duration ?? NaN) && (v?.duration ?? 0) > 0 ? v!.duration : lenRef.current;
    // An in-progress recording has no final length yet: don't save a half-length as if it were the whole
    if (!key || !v || !(dur > 0) || v.currentTime < 5 || growRef.current || channelId) return; // a channel never writes watch history
    void saveProgress(key, Math.floor(v.currentTime), Math.floor(dur), target.episode ? target.series : undefined);
  }, [target, channelId]);

  // A stream link failed: work out why, then renew once, show who holds the connection, or give up
  const onLinkError = useCallback(
    async (url: string, detail = "") => {
      if (!alive.current || linkRef.current !== url) return;
      if (failingUrl.current === url) return;
      failingUrl.current = url;
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
        errorClass: what,
        mediaErrorCode: v?.error?.code ?? null,
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
      if (what === "fatal" && isRetryable(status, code) && recRef.current.phase !== "blocked" && recRef.current.phase !== "ended") {
        // Recovery owns the retry: it releases this stream before opening another
        dispatchRef.current({ t: "fault", now: Date.now(), pos: v?.currentTime ?? 0, canHls: isLive && fmtRef.current === "ts" });
        return;
      }
      const perm = what !== "full" ? permanentReason(status, code) : null;
      if (perm && recRef.current.phase !== "blocked" && recRef.current.phase !== "ended") {
        dispatchRef.current({ t: "fatal", reason: perm });
        return;
      }
      if (what === "full") {
        dispatchRef.current({ t: "busy" });
        if (recRef.current.phase !== "blocked" && recRef.current.phase !== "playing") return;
        dispatchRef.current({ t: "blocked" });
      }
      teardown();
      finishBuffer();
      debugEvent("iptv-player", "terminal failure", { errorClass: what, player: engine, format: fmtRef.current, status });
      if (what === "full") setState({ kind: "full", text: "Every connection to this provider is in use." });
      else setState({ kind: "error", text: failureText(code, playbackFailureText(engine, detail, status)) });
    },
    // start is defined below and stable for the target
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [teardown, finishBuffer, isLive],
  );

  const attach = useCallback(
    async (a: PlayAnswer) => {
      const v = videoRef.current;
      if (!v) return;
      if (hlsRef.current || tsRef.current) teardown();
      const url = streamUrl(a.url);
      linkRef.current = url;
      const engine = engineFor(a.url, a.mime);
      engineRef.current = engine;
      setPlayerEngine(engine);
      refreshTracks();
      answerRef.current = a;
      failingUrl.current = null;
      attemptStarted.current = Date.now();
      firstFrame.current = false;
      lastTime.current = 0;
      rebufferStarted.current = null;
      lastMove.current = Date.now();
      lastNet.current = Date.now();
      stallReported.current = false;
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
          // Worker + buffer cleanup only behind ?worker=1 (default off); chase the live edge so a zap never drifts minutes behind
          { enableWorker: WORKER_FLAG, ...(WORKER_FLAG ? { autoCleanupSourceBuffer: true, autoCleanupMaxBackwardDuration: 30, autoCleanupMinBackwardDuration: 15 } : {}), lazyLoad: false, liveBufferLatencyChasing: a.kind === "live", liveBufferLatencyMaxLatency: 6, liveBufferLatencyMinRemain: 1.5 },
        );
        claimEngine(tsRef, destroyTs, p);
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
        claimEngine(hlsRef, (old) => old.destroy(), h);
        h.on(H.Events.ERROR, (_e, data) => {
          if (data.fatal && hlsRef.current === h)
            void onLinkError(url, [data.type, data.details, data.response?.code, data.error?.message].filter((x) => x != null && x !== "").join(" "));
        });
        for (const event of [H.Events.AUDIO_TRACKS_UPDATED, H.Events.AUDIO_TRACK_SWITCHED, H.Events.SUBTITLE_TRACKS_UPDATED, H.Events.SUBTITLE_TRACK_SWITCH]) h.on(event, refreshTracks);
        refreshTracks();
        h.loadSource(url);
        h.attachMedia(v);
      } else { v.src = url; refreshTracks(); }
      v.play().catch(() => setPaused(true));
    },
    [onLinkError, refreshTracks],
  );

  /** `retry`: the second go after stopping a recording; keeps the pop-up up and turns a second refusal into the error. */
  const start = useCallback(async (retry = false, recovering = false): Promise<boolean> => {
    // A player chunk that failed to load (offline, stale deploy) is a retryable fault when recovering, else an error screen
    const loadFailed = (): boolean => {
      if (!alive.current || g !== gen.current) return false;
      debugEvent("iptv-player", "player load failed", { player: engineRef.current, format: fmtRef.current });
      if (recovering) dispatchRef.current({ t: "fault", now: Date.now(), pos: 0, canHls: isLive && fmtRef.current === "ts" });
      else setState({ kind: "error", text: "The player couldn't load. Check your connection and try again." });
      return false;
    };
    const g = recovering ? gen.current : beginExclusive(gen, () => window.clearTimeout(openTimer.current), teardown);
    if (!recovering) {
      // not a recovery: the machine starts over (nothing held until this start succeeds)
      recRef.current = { ...initialRecovery(isLive, fmtRef.current), conn: "closed", offline: recRef.current.offline };
      setRec(recRef.current);
    }
    if (!retry && !recovering) setState({ kind: "loading" });
    if (!retry) attemptStarted.current = Date.now();
    try {
      if (isLive && fmtRef.current === "ts" && !(await loadMpegts()).default.getFeatureList().mseLivePlayback) fmtRef.current = "m3u8";
    } catch {
      return loadFailed();
    }
    const a = await play(playQuery(target, iptvDevice(), fmtRef.current));
    if (!alive.current || g !== gen.current) return false;
    if (a.status === 200 && a.data?.url) {
      if (!recovering) {
        recRef.current = { ...initialRecovery(isLive, fmtRef.current), offline: recRef.current.offline };
        setRec(recRef.current);
      }
      setAnswer(a.data);
      setState({ kind: "playing" });
      // Every (re)attach can be a new engine with default tracks: let the language prefs pick again on its fresh lists
      autoTracks.current.done = false;
      reapply.current = { audio: !!manualPick.current.audio, subtitle: !!manualPick.current.subtitle };
      setAudioChoices([]);
      setSubtitleChoices([]);
      try {
        await attach(a.data);
      } catch {
        return loadFailed();
      }
      return true;
    }
    const what = linkFailure(a.status, a.data?.code);
    debugEvent("iptv-player", "terminal failure", { errorClass: what, status: a.status, player: engineRef.current, format: fmtRef.current });
    if (recovering) {
      if (what !== "full" && isRetryable(a.status, a.data?.code)) {
        dispatchRef.current({ t: "fault", now: Date.now(), pos: 0, canHls: isLive && fmtRef.current === "ts" });
        return false;
      }
      const perm = what === "full" ? null : permanentReason(a.status, a.data?.code);
      if (perm) {
        dispatchRef.current({ t: "fatal", reason: perm });
        return false;
      }
      if (what === "full") {
        dispatchRef.current({ t: "busy" });
        if (recRef.current.phase !== "blocked") return false; // one delayed retry scheduled
      } else dispatchRef.current({ t: "blocked" });
    }
    if (retry) setState({ kind: "error", text: a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`) });
    else if (what === "full") {
      const b = parseBlocker(a.data);
      // Only a blocker we can say something useful about gets the pop-up; older servers keep today's message
      const popup = b && (canStop(b) || (b.kind === "viewer" && (b.title || b.channel))) ? b : undefined;
      setState({ kind: "full", text: a.data?.error ?? "Every connection to this provider is in use.", connection: a.data?.connection, blocker: popup });
    } else setState({ kind: "error", text: failureText(a.data?.code, a.data?.error ?? (a.status === 0 ? "The server can't be reached." : `Server said ${a.status}`)) });
    return false;
  }, [target, isLive, attach]);

  // After a recovery reconnect: put the user's manual pick back once the new engine reports its tracks (state-only, no I/O)
  useEffect(() => {
    const r = reapply.current;
    const p = manualPick.current;
    if (r.audio && p.audio && audioChoices.length) {
      r.audio = false;
      const i = matchPick(audioChoices, p.audio);
      if (i != null && !audioChoices.find((t) => t.index === i)?.active) selectAudio(i);
    }
    if (r.subtitle && p.subtitle) {
      // gated on the new engine's lists (audio-less streams only report subtitles) so the pre-attach empty state never consumes the re-apply
      if (p.subtitle.off) { if (!audioChoices.length && !subtitleChoices.length) return; r.subtitle = false; if (!subtitleOff) selectSubtitle(-1); }
      else if (subtitleChoices.length) {
        r.subtitle = false;
        const i = matchPick(subtitleChoices, p.subtitle);
        if (i != null && !(subtitleChoices.find((t) => t.index === i)?.active && !subtitleOff)) selectSubtitle(i);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioChoices, subtitleChoices, subtitleOff]);

  useEffect(() => {
    const a = autoTracks.current;
    if (a.done || a.manual || !langPrefs || !audioChoices.length) return;
    const apply = () => {
      if (a.done || a.manual) return;
      a.done = true;
      const alwaysSubs = getAlwaysSubs(getSession()?.userId ?? "");
      if (!langPrefs.audioLangs.length && !alwaysSubs) return;
      const r = pickIptvTracks(audioChoicesRef.current, subtitleChoicesRef.current, { ...langPrefs, alwaysSubs });
      if (r.audio != null) selectAudio(r.audio);
      if (r.subtitle != null) selectSubtitle(r.subtitle);
    };
    // Latch once audio and text track lists are both there; text tracks can appear late, so wait up to 3 s for them
    if (subtitleChoices.length) { apply(); return; }
    const t = window.setTimeout(apply, 3000);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioChoices, subtitleChoices, langPrefs]);

  useEffect(() => {
    alive.current = true;
    gen.current++;
    quietUntil.current = 0;
    playingSince.current = null;
    lastMove.current = Date.now();
    renewed.current = false;
    autoTracks.current = { done: false, manual: false };
    manualPick.current = {};
    reapply.current = { audio: false, subtitle: false };
    startRef.current = start;
    recRef.current = initialRecovery(isLive);
    setRec(recRef.current);
    void start();
    const onHide = () => { finishBuffer(); teardown(); };
    window.addEventListener("pagehide", onHide);
    return () => {
      alive.current = false;
      gen.current++;
      window.clearTimeout(openTimer.current);
      window.clearTimeout(hiddenTimer.current);
      window.removeEventListener("pagehide", onHide);
      report();
      finishBuffer();
      teardown();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start]);

  // Native media track lists can be populated after metadata and change during playback.
  useEffect(() => {
    const v = videoRef.current as (HTMLVideoElement & { audioTracks?: EventTarget }) | null;
    if (!v) return;
    const update = () => { if (!hlsRef.current && engineRef.current !== "mpegts") refreshTracks(); };
    for (const list of [v.audioTracks, v.textTracks]) {
      list?.addEventListener("addtrack", update);
      list?.addEventListener("removetrack", update);
      list?.addEventListener("change", update);
    }
    return () => { for (const list of [v.audioTracks, v.textTracks]) {
      list?.removeEventListener("addtrack", update);
      list?.removeEventListener("removetrack", update);
      list?.removeEventListener("change", update);
    } };
  }, [refreshTracks]);

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

  // A renewed link may be needed later in a long live session (4 h expiry or server restart).
  useEffect(() => {
    if (state.kind !== "playing") return;
    const t = window.setTimeout(() => { renewed.current = false; }, 60_000);
    return () => window.clearTimeout(t);
  }, [state.kind, answer]);

  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) window.clearTimeout(hideTimer.current);
    if (!tracksOpenRef.current) hideTimer.current = window.setTimeout(() => setControls(false), 3500);
  }, []);
  // The menu closing (any route: pick, Escape, replay, teardown) restarts the auto-hide timer
  useEffect(() => { if (!tracksOpen) poke(); }, [tracksOpen, poke]);
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
  useEffect(() => {
    if (state.kind !== "playing") return;
    lastMove.current = Date.now();
    const v = videoRef.current;
    let frameId = 0;
    let active = true;
    const onVisible = () => {
      if (document.hidden) return;
      lastMove.current = Date.now();
      stallReported.current = false;
    };
    document.addEventListener("visibilitychange", onVisible);
    if (v?.requestVideoFrameCallback) {
      let lastMedia = -1;
      const frame = (_now?: number, meta?: { mediaTime?: number }) => {
        if (!active) return;
        // Bookkeeping at most every ~250 ms of media time (the first frame always passes)
        const mt = meta?.mediaTime;
        if (mt != null && lastMedia >= 0 && Math.abs(mt - lastMedia) < 0.25 && Date.now() - lastMove.current < 1000) {
          frameId = v.requestVideoFrameCallback(frame);
          return;
        }
        if (mt != null) lastMedia = mt;
        lastMove.current = Date.now();
        stallReported.current = false;
        markMoving();
        if (!firstFrame.current) {
          firstFrame.current = true;
          debugEvent("iptv-player", "first frame", { elapsedMs: Date.now() - attemptStarted.current, player: engineRef.current, format: fmtRef.current }, "info");
        }
        finishBuffer();
        frameId = v.requestVideoFrameCallback(frame);
      };
      frameId = v.requestVideoFrameCallback(frame);
    }
    const t = setInterval(() => {
      const v = videoRef.current;
      const now = Date.now();
      const thresh = isLive ? STALL_MS : VOD_STALL_MS;
      const r = recRef.current;
      // 20 s of continuous playback forgives earlier attempts
      if (r.phase !== "playing" || !v || v.paused || now - lastMove.current > 3000) playingSince.current = null;
      else if (playingSince.current == null) playingSince.current = now;
      else if (r.unstable && now - playingSince.current >= STABLE_MS) dispatchRef.current({ t: "stable" });
      if (v && !v.seeking && !v.ended && shouldSurfaceLiveStall(now, lastMove.current, thresh, v.paused, document.hidden, quietUntil.current)) {
        const canHls = isLive && fmtRef.current === "ts";
        if (!stallReported.current) {
          stallReported.current = true;
          dispatchRef.current({ t: "stall", pos: v.currentTime, canHls });
          debugEvent("iptv-player", "stall detected", {
            player: engineRef.current,
            format: fmtRef.current,
            firstFrame: firstFrame.current,
            rebufferCount: rebufferCount.current,
            droppedFrames: v.getVideoPlaybackQuality?.().droppedVideoFrames ?? null,
          });
        }
        // Escalate in any recovery phase. VOD / catch-up: only when the element is not loading and nothing happened for VOD_IDLE_MS.
        const idleFor = isLive ? now - lastMove.current - thresh : Math.min(now - lastMove.current, now - lastNet.current);
        const need = isLive ? RECONNECT_AFTER_MS : v.readyState < 3 ? VOD_IDLE_MS : Infinity;
        if (r.phase !== "playing" && r.conn === "held" && idleFor >= need) dispatchRef.current({ t: "fault", now: Date.now(), pos: v.currentTime, canHls });
      }
    }, 1000);
    return () => { active = false; clearInterval(t); document.removeEventListener("visibilitychange", onVisible); if (frameId) v?.cancelVideoFrameCallback?.(frameId); };
  }, [isLive, state.kind, finishBuffer, markMoving]);
  const retryNow = () => {
    const v = videoRef.current;
    debugEvent("iptv-player", "manual retry", { player: engineRef.current, format: fmtRef.current, phase: recRef.current.phase }, "info");
    dispatchRef.current({ t: "retryNow", pos: v?.currentTime ?? 0, canHls: isLive && fmtRef.current === "ts", now: Date.now() });
  };
  const reportStall = () => {
    debugEvent("iptv-player", "user reported stall", { player: engineRef.current, format: fmtRef.current, firstFrame: firstFrame.current, rebufferCount: rebufferCount.current }, "info");
    void sendDebugReport();
    flashNote("Diagnostic report queued");
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
      if (n >= startsAt) {
        // kicked off: one last render flips the pre-game state, then no more per-second root renders
        clearInterval(t);
        if (!started.current) {
          started.current = true;
          flashNote("Started");
        }
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
  const sportPrefs = usePrefs({ defer: true }).prefs?.sport;
  const spoilers = !!sportPrefs?.spoilers;
  const live_ok = shouldShowLiveExtras(extras);
  // ---- Companion (T2.1): the Live drawer as a side panel, the scorebug pill, the spoiler delay. `drawer` = panel open.
  const [drawer, setDrawer] = useState(false);
  const [videoEl, setVideoEl] = useState<HTMLVideoElement | null>(null);
  useEffect(() => setVideoEl(videoRef.current), []);
  const revealClock = useRevealClock({ video: videoEl, channelKey: isLive ? target.channel ?? null : null, isLive, replay: !!extras?.replay });
  const panelAvail = live_ok && !!extras?.matchId;
  const liveNow = isLive && !extras?.replay;
  const { view: feed, source: feedSource } = useLiveFeed({ matchId: extras?.matchId, enabled: panelAvail && liveNow && state.kind === "playing", liveNow, spoilers, reveal: revealClock.reveal, noteMoment: revealClock.noteMoment });
  const blowout = feed.kind === "blowout";
  const openedBy = useRef<{ by: OpenReason; at: number; touched: boolean }>({ by: "user", at: 0, touched: false });
  const userClosedAt = useRef<number | null>(null);
  const openPanel = useCallback((by: OpenReason) => {
    openedBy.current = { by, at: Date.now(), touched: by === "user" };
    setDrawer(true);
  }, []);
  const closePanel = useCallback((byUser = true) => {
    if (byUser) userClosedAt.current = Date.now();
    setDrawer(false);
  }, []);
  const closeDrawer = closePanel;
  const touchPanel = useCallback(() => { openedBy.current.touched = true; }, []);
  const phonePortrait = usePhonePortrait();
  const [rememberedMode, setRememberedMode] = useState<string | null>(() => { try { return localStorage.getItem(DROP_KEY); } catch { return null; } });
  const panelMode = panelModeFor(phonePortrait, rememberedMode);
  useDropWatch(videoEl, drawer && panelMode === "squeeze", () => setRememberedMode("overlay"));
  // Auto-open at breaks and for 6 s after a revealed key moment; tuck away again (never in replay, never just after the viewer closed it)
  const lastMomentN = useRef(0);
  useEffect(() => {
    if (!panelAvail || !liveNow) return;
    const fresh = feed.momentN > lastMomentN.current; // a feed reset drops the count to 0: that is not news
    lastMomentN.current = feed.momentN;
    const by = autoOpenDecision({ nowMs: Date.now(), replay: revealClock.replay, panelOpen: drawer, userClosedAtMs: userClosedAt.current, kind: feed.kind, freshMoment: fresh });
    if (by) openPanel(by);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feed, panelAvail, liveNow, revealClock.replay]);
  const feedKind = useRef(feed.kind);
  feedKind.current = feed.kind;
  useEffect(() => {
    if (!drawer || openedBy.current.by === "user") return;
    const t = window.setInterval(() => {
      const o = openedBy.current;
      if (autoCloseDecision({ nowMs: Date.now(), openedBy: o.by, openedAtMs: o.at, kind: feedKind.current, touched: o.touched, replay: revealClock.replay })) closePanel(false);
    }, 1000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drawer]);
  const keyState = useRef({ avail: false, open: false, replay: false });
  keyState.current = { avail: panelAvail && isLive, open: drawer, replay: revealClock.replay };
  const navAt = useRef(0);
  useEffect(() => {
    const t = () => { navAt.current = Date.now(); };
    document.addEventListener("focusin", t);
    return () => document.removeEventListener("focusin", t);
  }, []);
  // Down with the controls idle: this match's "Other channels" (the same sheet and switch route as the pre-game card); nothing without alternates
  const nAlt = extras?.alternates?.length ?? 0;
  const othersRef = useRef({ open: false, n: 0 });
  othersRef.current = { open: others, n: nAlt };
  const switchAlt = (i: number) => {
    const alts = extras!.alternates!;
    go(livePath(alts[i].id, alts[i].name, switchExtras(extras!, alts[i].id, { id: target.channel!, name: title })), true);
  };
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
  // The clock no longer re-renders this tree: a growing recording's length (it is derived from now) and the
  // "From kick-off" button's expiry each get one timed render instead
  const [, bump] = useState(0);
  const kickoffStop = isLive ? extras?.kickoff?.stop : undefined;
  useEffect(() => {
    if (kickoffStop == null) return;
    const ms = kickoffStop * 1000 - Date.now();
    if (ms <= 0) return;
    const t = window.setTimeout(() => bump((n) => n + 1), ms + 50);
    return () => window.clearTimeout(t);
  }, [kickoffStop]);

  const growing = !!answer?.growing || meta?.state === "recording";
  growRef.current = growing;
  const length = isLive ? 0 : recordingLength(target.recording ? meta : null, Date.now() / 1000, duration);
  lenRef.current = length;
  const growingVod = growing && !isLive;
  useEffect(() => {
    if (!growingVod) return;
    const t = window.setInterval(() => bump((n) => n + 1), 1000);
    return () => window.clearInterval(t);
  }, [growingVod]);

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
    back(channelId ? "/channels" : target.recording ? "/recordings" : "/livetv");
  };
  exitRef.current = exit;
  // A channel's film ended: whatever the clock says is on now (at its scheduled offset), through the same player route. Not when it
  // is the same film again, ended after seconds of a long one, or after too many hops lately: then back to the channels.
  const chained = useRef(false);
  const mountedAt = useRef(Date.now());
  // Off the clock (started from 0): `playedFrom` is where the "played enough" check counts from
  const [off, setOff] = useState(offSchedule);
  const playedFrom = useRef(resume);
  // "From start" on the engine already open: a seek to 0, no new connection. A film that cannot seek (no seekable range) is left alone.
  const fromStart = () => {
    const v = videoRef.current;
    if (!v || !channelId || !(length > 0)) return;
    const sk = v.seekable;
    if (!sk.length) return flashNote("This film can't be restarted here.");
    v.currentTime = sk.start(0);
    mountedAt.current = Date.now();
    playedFrom.current = 0;
    chained.current = false;
    setOff(true);
    if (v.paused) void v.play().catch(() => undefined);
  };
  const rejoinClock = () => {
    if (!channelId || !curId) return;
    void rejoinPlan(channelId, curId).then((r) => {
      if (!r || !alive.current) return;
      if (r.kind === "seek") {
        seekTo_(r.s);
        mountedAt.current = Date.now();
        playedFrom.current = r.s;
        setOff(false);
      } else {
        teardown(); // the existing teardown-then-open route: this player closes before the next one mounts
        go(r.path, true);
      }
    });
  };
  const nextOnChannel = () => {
    if (!channelId || chained.current) return;
    chained.current = true;
    void nextChannelPath(channelId, curId, (Date.now() - mountedAt.current) / 1000, playedFrom.current).then((path) => {
      if (!alive.current) return;
      if (path) {
        teardown();
        go(path, true);
      } else {
        flashNote("This channel can't carry on right now.");
        exit();
      }
    });
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
      if ((e.key === "Escape" || e.key === "Backspace") && tracksOpen) { e.preventDefault(); e.stopPropagation(); setTracksOpen(false); return; }
      if ((e.key === "Escape" || e.key === "Backspace") && othersRef.current.open && !keyState.current.open) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); setOthers(false); return; }
      // Companion keys (T2.1): Back closes the panel first; with the controls idle Up = panel, Down = channels, Left/Right = panel tabs
      {
        const ae = document.activeElement as HTMLElement | null;
        const btn = ae?.tagName === "BUTTON";
        const inUse = tracksOpen || (btn && Date.now() - navAt.current < 4000);
        const act = panelKeyAction({ key: e.key, panelOpen: keyState.current.open, controlsInUse: inUse, available: keyState.current.avail, alternates: othersRef.current.n });
        if (act) {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          if (btn) ae!.blur();
          if (act === "close-panel") closePanel(true);
          else if (act === "toggle-panel") (keyState.current.open ? closePanel(true) : openPanel("user"));
          else if (act === "channels") {
            if (othersRef.current.n > 0) {
              if (othersRef.current.open) document.querySelector<HTMLElement>(".cp-others [data-nav]")?.focus();
              else setOthers(true);
            }
          }
          else {
            const tabs = Array.from(document.querySelectorAll<HTMLElement>(".cp-panel .bc-tab"));
            const at = tabs.findIndex((t) => t.getAttribute("aria-selected") === "true");
            tabs[stepTab(at < 0 ? 0 : at, tabs.length, act === "tab-next" ? 1 : -1)]?.click();
            touchPanel();
          }
          return;
        }
        // OK on the picture with the controls idle brings them up: focus moves to the Live button, then arrows navigate as usual
        if (keyState.current.avail && !inUse && e.key === "Enter" && !btn) {
          const first = document.querySelector<HTMLElement>(".player-top [data-live-toggle]") ?? document.querySelector<HTMLElement>(".player-top [data-nav]");
          if (first) { e.preventDefault(); e.stopPropagation(); first.focus(); navAt.current = Date.now(); return; }
        }
      }
      if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        exit();
        return;
      }
      const onButton = (document.activeElement as HTMLElement | null)?.tagName === "BUTTON";
      if (tracksOpen && onButton) return;
      if (tracksOpen && [" ", "k", "Enter", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "j", "l", "PageDown", "PageUp"].includes(e.key)) {
        e.preventDefault();
        return;
      }
      if (isLive) return;
      poke();
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
  }, [isLive, controls, length, growing, tracksOpen]);

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
      style={subVars as CSSProperties} data-sub-bg={subPrefs.background} className={`player iptv-player${isLive || controls || paused || state.kind !== "playing" || tracksOpen ? " show" : ""}`}
      onMouseMove={isLive ? undefined : poke}
      onClick={() => {
        if (touchClick.current) touchClick.current = false;
        else if (!isLive) poke();
      }}
    >
      <div className={`cp-stage ${panelMode}${drawer ? " open" : ""}`}>
      <div className="cp-video">
      <video
        ref={videoRef}
        className="player-video"
        playsInline
        onClick={() => !isLive && !touchClick.current && togglePlay()}
        onPointerUp={onVideoPointerUp}
        onPlay={() => setPaused(false)}
        onEnded={() => { watch.end(true); if (kickoffRun.current) backToLive(); else nextOnChannel(); }}
        onPause={() => setPaused(true)}
        onTimeUpdate={(e) => {
          watch.tick(e.currentTarget.currentTime);
          if (Math.abs(e.currentTarget.currentTime - lastTime.current) > 0.05) {
            stallReported.current = false;
            if (!isLive || !e.currentTarget.requestVideoFrameCallback) {
              lastMove.current = Date.now();
              markMoving();
            }
            if (!firstFrame.current && e.currentTarget.videoWidth > 0 && (!isLive || !e.currentTarget.requestVideoFrameCallback)) {
              firstFrame.current = true;
              debugEvent("iptv-player", "first frame", { elapsedMs: Date.now() - attemptStarted.current, player: engineRef.current, format: fmtRef.current }, "info");
            }
            finishBuffer();
          }
          lastTime.current = e.currentTarget.currentTime;
          const r = replayRef.current;
          if (r && replayDone(r, e.currentTarget.currentTime)) { replayRef.current = null; selectSubtitle(r.restore); }
        }}
        onDurationChange={(e) => setDuration(isFinite(e.currentTarget.duration) ? e.currentTarget.duration : 0)}
        onLoadedMetadata={(e) => { applyResume(e.currentTarget); refreshTracks(); }}
        onCanPlay={(e) => applyResume(e.currentTarget)}
        onWaiting={() => { if (firstFrame.current && rebufferStarted.current == null) { rebufferStarted.current = Date.now(); rebufferCount.current++; debugEvent("iptv-player", "rebuffer started", { count: rebufferCount.current, player: engineRef.current, format: fmtRef.current }, "info"); } }}
        onPlaying={finishBuffer}
        onProgress={() => { lastNet.current = Date.now(); }}
        onSeeking={() => { lastNet.current = Date.now(); }}
        onSeeked={() => { watch.seeked(); lastNet.current = Date.now(); }}
        onLoadedData={() => { lastNet.current = Date.now(); }}
        onError={(e) => linkRef.current && !tsRef.current && !hlsRef.current && void onLinkError(linkRef.current, `MediaError ${e.currentTarget.error?.code ?? ""} ${e.currentTarget.error?.message ?? ""}`.trim())}
      />
      {panelAvail && liveNow && !revealClock.replay && <Scorebug view={feed} panelOpen={drawer} />}
      </div>
      {panelAvail && extras?.matchId && (
        <Panel open={drawer} mode={panelMode} matchId={extras.matchId} liveNow={liveNow} spoilers={spoilers} reveal={revealClock.reveal} source={liveNow ? feedSource : undefined} onClose={closeDrawer} onTouch={touchPanel} />
      )}
      </div>
      {state.kind === "loading" && <div className="spinner" role="status" aria-label={retrying ? "Retrying stream" : "Connecting to stream"} />}
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
        {isLive && state.kind === "playing" && (extras?.replay ? <span className="iptv-live-pill replay">Replay</span> : <span className="iptv-live-pill">Live</span>)}
        {live_ok && isLive && <MatchInfoButton id={extras?.matchId} />}
        {live_ok && <FantasyChip matchId={extras?.matchId} active={isLive && !extras?.replay && state.kind === "playing"} reveal={revealClock.reveal} replay={revealClock.replay} />}
        {live_ok && isLive && extras?.matchId && state.kind === "playing" && <DelayChip delaySec={revealClock.delaySec} replay={revealClock.replay} onAdjust={() => flashNote(calibrateMessage(revealClock.calibrate()))} />}
        {live_ok && extras?.matchId && state.kind === "playing" && (
          <button className="btn small" data-nav data-live-toggle aria-expanded={drawer} onClick={() => (drawer ? closePanel(true) : openPanel("user"))}>
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
      {state.kind === "playing" && !copy && <TrackMenu
        open={tracksOpen}
        onOpenChange={onTracksOpenChange}
        audio={audioChoices.map((c) => ({ id: String(c.index), label: audioOptionLabel(c.label), active: c.active }))}
        subtitles={subtitleChoices.map((c) => ({ id: String(c.index), label: c.label, active: c.active }))}
        subtitlesOff={subtitleOff}
        audioEmptyNote="No selectable audio tracks exposed."
        onPickAudio={(v) => { autoTracks.current.manual = true; manualPick.current.audio = makePick(audioChoices, Number(v)); selectAudio(Number(v)); }}
        onPickSubtitle={(v) => { autoTracks.current.manual = true; manualPick.current.subtitle = v == null ? { off: true } : makePick(subtitleChoices, Number(v)); replayRef.current = null; selectSubtitle(v == null ? -1 : Number(v)); }}
        unavailable={playerEngine === "mpegts" ? {
          note: "This stream format can’t expose track controls.",
          action: isLive ? { label: "Switch to HLS and choose tracks", run: () => { fmtRef.current = "m3u8"; setTracksOpen(false); setState({ kind: "loading" }); teardown(); void start(); } } : undefined,
        } : undefined}
        prefs={subPrefs}
        onPrefs={setSubPrefs}
        replay={playerEngine !== "mpegts" && subtitleChoices.length ? { disabled: () => replayTarget(videoRef.current?.currentTime ?? 0, seekableStart()) == null, run: replayCaptions } : undefined}
        barClassName="iptv-tracks-button"
        menuClassName="iptv-track-menu"
        onActivity={poke}
      />}
      {flash && <div className="seek-flash iptv-flash">{flash}</div>}
      {live_ok && <FantasyAlerts matchId={extras?.matchId} active={isLive && !extras?.replay && state.kind === "playing"} reveal={revealClock.reveal} replay={revealClock.replay} blowout={blowout} />}
      {live_ok && <FlipAlerts matchId={extras?.matchId} active={isLive && !extras?.replay && flipAlerts && state.kind === "playing"} drawerOpen={drawer} reveal={revealClock.reveal} replay={revealClock.replay} blowout={blowout} source={liveNow ? feedSource : undefined} />}
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
              {extras!.alternates!.map((a) => (
                <button
                  key={a.id}
                  className="btn small"
                  data-nav
                  onClick={() => go(livePath(a.id, a.name, switchExtras(extras!, a.id, { id: target.channel!, name: title })), true)}
                >
                  {a.name}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {others && !(preNow && startsAt && cardOn) && nAlt > 0 && (
        <div className="pregame-card cp-others" role="group" aria-label="Other channels">
          <span>Other channels</span>
          <div className="buttons">
            {extras!.alternates!.map((a, i) => (
              <button key={a.id} className="btn small" data-nav onClick={() => switchAlt(i)}>
                {a.name}
              </button>
            ))}
            <button className="btn small ghost" data-nav onClick={() => setOthers(false)}>
              Close
            </button>
          </div>
        </div>
      )}
      {copy && state.kind === "playing" && (
        <div className="iptv-stall" role="alert" aria-live="polite">
          <span>{copy}</span>
          <button className="btn primary small" data-nav data-autofocus onClick={retryNow}>Retry now</button>
          {rec.phase === "stalled" && <button className="btn small" data-nav onClick={() => { quietUntil.current = Date.now() + QUIET_MS; }}>Keep waiting</button>}
          {(rec.phase === "failed" || rec.phase === "denied" || rec.phase === "stalled") && (extras?.alternates ?? []).map((a) => <button key={a.id} className="btn small" data-nav onClick={() => go(livePath(a.id, a.name, switchExtras(extras ?? {}, a.id, { id: target.channel!, name: title })), true)}>Switch to {a.name}</button>)}
          <button className="btn small" data-nav onClick={() => dispatchRef.current({ t: "cancel" })}>Back</button>
          <button className="btn small" data-nav onClick={reportStall}>Send diagnostics</button>
        </div>
      )}
      {!isLive && state.kind === "playing" && (
        <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
          {seekable && (
            <TimeBarScrubber videoRef={videoRef} max={length} fmt={fmt} onActivity={poke} onSeek={seekTo_} />
          )}
          <div className="player-row iptv-controls">
            <button className="btn ghost" data-nav onClick={() => seekBy(-30)} aria-label="Back 30 seconds">
              −30s
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(-10)} aria-label="Back 10 seconds">
              −10s
            </button>
            <button className="btn ghost" data-nav data-autofocus={!copy || undefined} onClick={togglePlay}>
              {paused ? "Play" : "Pause"}
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(10)} aria-label="Forward 10 seconds">
              +10s
            </button>
            <button className="btn ghost" data-nav onClick={() => seekBy(30)} aria-label="Forward 30 seconds">
              +30s
            </button>
            <TimeBarClock videoRef={videoRef} fmt={fmt} length={length} seekable={seekable} growing={growing} />
            {channelId && seekable && (
              <>
                <span className="spacer" />
                {off && <span className="player-method">Off schedule</span>}
                <button className="btn ghost" data-nav onClick={off ? rejoinClock : fromStart}>
                  {off ? "Back to live" : "From start"}
                </button>
              </>
            )}
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
function NativeIptvPlayer({ target, title, resume = 0, extras, channelId }: { target: PlayTarget; title: string; resume?: number; extras?: LiveExtras; channelId?: string | null; offSchedule?: boolean }) {
  const curId = target.vod ?? target.episode;
  const nativeLen = useRef(0);
  const watch = usePlayWatch(signalMeta(target, channelId), () => nativeLen.current, curId ?? "");
  const [state, setState] = useState<State>({ kind: "loading" });
  const alive = useRef(true);
  const renewed = useRef(false);
  const switchTo = useRef<string | null>(null);
  const openedAt = useRef(Date.now());
  // The user's alert settings go to the native player, which shows its own toasts and notifications
  const userPrefs = usePrefs({ defer: true }).prefs;
  const sportPrefsRef = useRef<{ fantasyAlerts?: boolean; flipAlerts?: boolean; panelStyle?: "docked" | "floating" }>({});
  sportPrefsRef.current = userPrefs ? { fantasyAlerts: userPrefs.fantasy.alerts, flipAlerts: userPrefs.sport.flipAlerts, panelStyle: userPrefs.sport.panelStyle } : {};
  const infoFor = useRef<string | null>(null);
  const isLive = !!target.channel && target.start == null;
  const key = progressKey(target);

  const exit = useCallback(() => back(channelId ? "/channels" : target.recording ? "/recordings" : "/livetv"), [target.recording, channelId]);

  // One native session: open the player, save progress while it plays, then act on how it closed
  const session = useCallback(
    async (a: PlayAnswer, resumeMs: number) => {
      const link = nativeLink(a, streamUrl);
      const live = isLive || a.kind === "live";
      debugEvent("iptv-player", "native attach", { player: "exoplayer", mime: a.mime ?? null, kind: a.kind, direct: link.direct, live }, "info");
      const subs = [
        await NativePlayer.addListener("progress", (e) => {
          if (e.durationMs > 0) nativeLen.current = e.durationMs / 1000;
          watch.tick(e.positionMs / 1000, 40); // the app reports every 30 s
          if (key && !channelId && e.durationMs > 0 && e.positionMs > 5000) void saveProgress(key, Math.floor(e.positionMs / 1000), Math.floor(e.durationMs / 1000), target.episode ? target.series : undefined);
        }),
        await NativePlayer.addListener("playerError", (e) => {
          debugEvent("iptv-player", "native player error", { player: "exoplayer", kind: a.kind, direct: link.direct, errorCode: e.errorCode ?? null });
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
      openedAt.current = Date.now();
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
          description: extras?.nospoil ? undefined : extras?.desc ?? undefined,
          upNext: extras?.nospoil ? undefined : extras?.upNext,
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
                // A replay of an earlier game: no match id, or the phone would poll live stats and alert for the old game
                ...(extras?.matchId && !extras.replay && !extras.nospoil && getSession()?.token ? { matchId: extras.matchId, apiBase: COMPANION_BASE, token: getSession()!.token } : {}),
                // Before the real start the native overlay shows the pre-game card
                ...(isPre(extras?.startsAt, Date.now() / 1000) && !extras?.replay
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
      if (key && !channelId && r.durationMs > 0 && r.positionMs > 5000) void saveProgress(key, Math.floor(r.positionMs / 1000), Math.floor(r.durationMs / 1000), target.episode ? target.series : undefined);
      // Another open() took over this player (a new match, the mini player): that session carries on, so this one ends quietly
      if (r.reason === "replaced") return;
      if (!alive.current) return;
      // "Other channels" on the pre-game card: reopen on the one picked (the rest of the event's channels, and this one, stay as alternates)
      if (switchTo.current && target.channel) {
        const pick = extras?.alternates?.find((x) => x.id === switchTo.current);
        switchTo.current = null;
        if (pick) {
          go(livePath(pick.id, pick.name, switchExtras(extras ?? {}, pick.id, { id: target.channel, name: title })), true);
          return;
        }
      }
      if (r.reason === "ended") watch.end(true);
      if (r.reason === "ended" && channelId) {
        // The film finished: on to what the clock says is next on the channel
        void nextChannelPath(channelId, curId, (Date.now() - openedAt.current) / 1000, resume).then((path) => {
          if (path) go(path, true);
          else {
            flashNote("This channel can't carry on right now.");
            exit();
          }
        });
        return;
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
        errorClass: what,
        status: r.httpStatus ?? 0,
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
    [isLive, key, title, exit, channelId, curId, resume],
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
