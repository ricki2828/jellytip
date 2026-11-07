// Player: direct play when the browser can, else Jellyfin HLS via hls.js (one automatic retry as a plain H.264 transcode).
// Reports start / progress (10 s) / stop so resume works with the TV; text subtitles with a delay control; next episode.
import { Scrubber } from "../components/Scrubber";
import { getSeekMeta, trickplaySource, type SeekMeta } from "../api/seekMeta";
import { advanceSeek, commitDue, SETTLE_MS, type SeekRamp } from "../lib/seekRamp";
import { chapterMarks, nextChapter, prevChapter } from "../lib/chapters";
import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import type Hls from "hls.js";
import { episodes, getItem, getSession, TICKS_PER_SECOND, type Item, type MediaStream } from "../api/jellyfin";
import { fetchSubtitles, negotiate, reportProgress, reportStart, reportStopped, stopEncoding, type PlayPlan } from "../api/playback";
import { activeCues, parseVtt, type Cue } from "../lib/vtt";
import { back, go } from "../lib/router";
import { prefsSoon, usePrefs } from "../api/me";
import { usePlayWatch } from "../api/signals";
import { libraryTarget } from "../lib/signals";
import { chooseStart, getAlwaysSubs, loadStore, memoryKey, recordChoice, saveStore } from "../lib/trackMemory";
import { TrackMenu } from "../components/TrackMenu.tsx";
import { flashNote } from "../components/TasteButtons";
import { autoMatch } from "../api/subSync";
import { clampNudge, doneNote, findNewTrack, loadNudge, progressText, saveNudge, stepNudge } from "../lib/subSync.ts";
import { useSubtitlePrefs } from "../lib/useSubtitlePrefs.ts";
import { jellyfinAudioLabel, playerKeyWhileMenu } from "../lib/trackMenu.ts";
import { replayDone, replayTarget, reportedSubtitle, startReplay, type Replay } from "../lib/captionReplay.ts";
import { isPhone } from "../lib/device";
import { noteNativeFullscreen, useFullscreen } from "../lib/useFullscreen";
import { localPosition, playableDownload, recordProgress, subtitleText, type DownloadRecord } from "../lib/downloads";
import { isNative, localFileUrl, NativeDownloads } from "../lib/native";
import { SubtitleSearchDialog } from "../components/SubtitleSearchDialog.tsx";
import { useCanManageSubtitles } from "../lib/useSubtitleAccess.ts";
import { remapActive } from "../lib/remoteSubtitles.ts";
import { canOfferRotate, nextPlayerOrientation } from "../lib/playerOrientation";
import { chainTarget, channelById, poolOf, schedule, tunePath, type ChanItem } from "../lib/channels.ts";
import { channelItems, rejoinPlan, takeChainHop } from "../api/channels";

const PROGRESS_MS = 10_000;
const HIDE_MS = 3000;
const NEXT_UP_SECONDS = 40;
const NET_RETRIES = 3;
const DOUBLE_TAP_MS = 300;

type LockableOrientation = ScreenOrientation & { lock?: (o: string) => Promise<void>; unlock?: () => void };

/** Rotation is an explicit player action; fullscreen remains a separate choice. */
async function rotatePlayer(box: HTMLElement | null) {
  if (isNative) {
    noteNativeFullscreen(false);
    await NativeDownloads.setPlayerMode({ fullscreen: false, rotate: true }).catch(() => undefined);
    return;
  }
  if (!isPhone() || !box) return;
  const orientation = screen.orientation as LockableOrientation | undefined;
  if (!orientation?.lock) return;
  try { await orientation.lock(nextPlayerOrientation(orientation.type)); } catch { /* Keep natural orientation when the browser requires fullscreen. */ }
}

function leaveLandscape() {
  if (isNative) NativeDownloads.setPlayerMode({ fullscreen: false, reset: true }).catch(() => undefined);
  try {
    (screen.orientation as LockableOrientation | undefined)?.unlock?.();
  } catch {
    /* not supported */
  }
}

// hls.js is ~half the bundle: only fetched when something plays
let hlsModule: Promise<typeof import("hls.js")> | null = null;
const loadHls = () => (hlsModule ??= import("hls.js"));

function clock(s: number) {
  if (!isFinite(s) || s < 0) s = 0;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = Math.floor(s % 60);
  return `${h ? `${h}:` : ""}${h ? String(m).padStart(2, "0") : m}:${String(sec).padStart(2, "0")}`;
}

const subLabel = (s: MediaStream) => s.DisplayTitle || [s.Language, s.Codec].filter(Boolean).join(" ") || `Track ${s.Index}`;

/** @param thenId double feature: play this item when the first one ends (instead of the next episode) */
export function Player({ id, startParam, thenId, chId, offSchedule = false }: { id: string; startParam: string | null; thenId?: string | null; chId?: string | null; offSchedule?: boolean }) {
  // A personal channel (#/channels): follows the clock, so it never reports progress or plays to Jellyfin's history
  const channel = channelById(chId);
  const poolRef = useRef<ChanItem[] | null>(null);
  // Off the clock: this item was started from 0 (or restarted); at its end the channel still goes to whatever the clock says is on
  const [off, setOff] = useState(offSchedule);
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const planRef = useRef<PlayPlan | null>(null);
  const startedRef = useRef(false);
  const stoppedRef = useRef(false);
  const retriedRef = useRef(false);
  const [item, setItem] = useState<Item | null>(null);
  const [plan, setPlan] = useState<PlayPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [paused, setPaused] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffering, setBuffering] = useState(true);
  const [controls, setControls] = useState(true);
  const [menu, setMenu] = useState(false);
  const menuRef = useRef(false);
  menuRef.current = menu;
  const [findSubs, setFindSubs] = useState(false);
  const canFindSubs = useCanManageSubtitles();
  const { prefs } = usePrefs({ defer: true });
  const { prefs: subPrefs, setPrefs: setSubPrefs, vars: subVars } = useSubtitlePrefs();
  const replayRef = useRef<Replay | null>(null);
  const [subIndex, setSubIndex] = useState<number>(-1);
  const [cues, setCues] = useState<Cue[]>([]);
  const [delay, setDelayState] = useState<number>(0); // per title + subtitle track, see applyDelay
  const delayRef = useRef(0);
  const [match, setMatch] = useState<{ status: "idle" | "matching" | "failed"; text?: string }>({ status: "idle" });
  const matchCtl = useRef<AbortController | null>(null);
  const [next, setNext] = useState<Item | null>(null);
  const [audioIndex, setAudioIndex] = useState<number | null>(null);
  const memKeyRef = useRef<string | null>(null);
  const [needsTap, setNeedsTap] = useState(false);
  const [pauseFlash, setPauseFlash] = useState(false);
  const pauseFlashTimer = useRef<number | null>(null);
  const netRetries = useRef(0);
  const hideTimer = useRef<number | null>(null);
  const subIndexRef = useRef(-1);
  subIndexRef.current = subIndex;

  const position = () => (videoRef.current?.currentTime ?? 0) * TICKS_PER_SECOND;
  const report = useCallback(
    () => ({ itemId: id, plan: planRef.current!, positionTicks: position(), paused: videoRef.current?.paused, subtitleStreamIndex: reportedSubtitle(subIndexRef.current, replayRef.current) }),
    [id],
  );

  // Downloaded copy being played: progress goes to the offline queue (synced to Jellyfin when online), no session
  const localRef = useRef<DownloadRecord | null>(null);
  const runtime = () => localRef.current?.item.RunTimeTicks ?? (videoRef.current?.duration ?? 0) * TICKS_PER_SECOND;
  const progress = () => {
    if (channel) return;
    if (localRef.current) recordProgress(id, position(), runtime());
    else reportProgress(report());
  };
  const started = () => {
    if (!localRef.current && !channel) reportStart(report());
  };

  const stop = useCallback(() => {
    const p = planRef.current;
    if (!p || stoppedRef.current) return;
    stoppedRef.current = true;
    if (channel) {
      stopEncoding(p); // free the transcode; nothing goes into the watch history
      return;
    }
    if (localRef.current) {
      if (startedRef.current) recordProgress(id, position(), runtime());
      return;
    }
    if (startedRef.current) reportStopped(report());
    else stopEncoding(p); // never started: no session to stop, but the server may already be transcoding
  }, [report, channel]);

  // Taste signal: real playing time of this title (a personal channel's pick counts as a weaker 'play'; sent when the title ends or the screen leaves)
  const sigTarget = item && item.Id === id ? libraryTarget(item) : null;
  const watch = usePlayWatch(sigTarget ? { ...sigTarget, isFilm: item?.Type === "Movie", channel: !!channel } : null, () => (item?.RunTimeTicks ?? 0) / TICKS_PER_SECOND, id);

  /** Close out the current plan before switching to another (fallback, audio change); the new one reports its own start. */
  const abandonPlan = useCallback(() => {
    stop();
    startedRef.current = false;
    stoppedRef.current = false;
  }, [stop]);

  const tryPlay = (v: HTMLVideoElement) =>
    v.play().then(
      () => setNeedsTap(false),
      // Safari/iOS (and autoplay policies) refuse play() without a gesture: offer a big tap-to-play
      () => {
        setPaused(true);
        setNeedsTap(true);
      },
    );
  const fallbackRef = useRef<() => void>(() => undefined);

  // ---- load + negotiate ----
  const attach = useCallback(async (p: PlayPlan, startSeconds: number) => {
    const Hls = p.hls ? (await loadHls()).default : null;
    const v = videoRef.current;
    if (!v) return;
    hlsRef.current?.destroy();
    hlsRef.current = null;
    planRef.current = p;
    setPlan(p);
    if (Hls && Hls.isSupported()) {
      const u = new URL(p.url, location.origin);
      u.searchParams.delete("StartTimeTicks"); // full-length VOD playlist; seek with startPosition instead
      const hls = new Hls({ startPosition: startSeconds, maxBufferLength: 30, backBufferLength: 60 });
      hlsRef.current = hls;
      netRetries.current = 0;
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal || hlsRef.current !== hls) return;
        // Network hiccups: a few retries with backoff (1, 2, 4 s), then give up on this stream
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && data.details !== Hls.ErrorDetails.MANIFEST_LOAD_ERROR && netRetries.current < NET_RETRIES) {
          const wait = 1000 * 2 ** netRetries.current++;
          setTimeout(() => hlsRef.current === hls && hls.startLoad(), wait);
          return;
        }
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !retriedRef.current) {
          hls.recoverMediaError();
          retriedRef.current = true;
          return;
        }
        fallbackRef.current();
      });
      hls.on(Hls.Events.FRAG_LOADED, () => {
        netRetries.current = 0;
      });
      hls.loadSource(u.origin === location.origin ? u.pathname + u.search : u.href);
      hls.attachMedia(v);
    } else {
      v.src = p.url;
      const seek = () => {
        if (startSeconds > 0) v.currentTime = startSeconds;
        v.removeEventListener("loadedmetadata", seek);
      };
      v.addEventListener("loadedmetadata", seek);
    }
    tryPlay(v);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const fallbackDone = useRef(false);
  const fallback = useCallback(async () => {
    // One retry as a plain H.264 transcode (codec the browser claimed but can't decode, broken remux, ...)
    if (fallbackDone.current) {
      setError("This title won't play in this browser.");
      return;
    }
    const at = videoRef.current?.currentTime || 0;
    if (localRef.current) {
      // The downloaded file won't play: stream it instead (when online)
      abandonPlan();
      localRef.current = null;
      try {
        attach(await negotiate(id, Math.round(at * TICKS_PER_SECOND)), at);
      } catch (e) {
        setError(navigator.onLine ? String((e as Error).message ?? e) : "The downloaded file won't play, and you're offline.");
      }
      return;
    }
    fallbackDone.current = true;
    abandonPlan();
    try {
      const p = await negotiate(id, Math.round(at * TICKS_PER_SECOND), { h264Only: true, audioStreamIndex: audioIndex ?? undefined });
      attach(p, at);
    } catch (e) {
      setError(String((e as Error).message ?? e));
    }
  }, [id, attach, abandonPlan, audioIndex]);
  fallbackRef.current = fallback;

  /** A manual audio / subtitle choice is remembered for this series or movie (re-applied before the global language prefs). */
  const remember = (change: { audio?: number; subtitle?: number }) => {
    const uid = getSession()?.userId;
    const k = memKeyRef.current;
    const streams = planRef.current?.source.MediaStreams;
    if (!uid || !k || !streams) return;
    saveStore(uid, recordChoice(loadStore(uid), k, streams, change, Date.now(), audioIndex ?? planRef.current?.source.DefaultAudioStreamIndex ?? null));
  };

  /** Switch audio track: renegotiate at the current position (no direct play: browsers can't switch tracks in-file). */
  const chooseAudio = async (index: number) => {
    setMenu(false);
    if (!planRef.current || index === audioIndex) return;
    const v = videoRef.current;
    const at = v?.currentTime || 0;
    const def = planRef.current.source.DefaultAudioStreamIndex;
    remember({ audio: index });
    abandonPlan();
    setAudioIndex(index);
    setBuffering(true);
    try {
      const p = await negotiate(id, Math.round(at * TICKS_PER_SECOND), { audioStreamIndex: index, allowDirectPlay: index === def });
      attach(p, at);
    } catch (e) {
      setError(String((e as Error).message ?? e));
    }
  };

  // Channel: the pool is needed at the end of the item to find what is on next (cached, so usually no request)
  useEffect(() => {
    if (!channel) return;
    let alive = true;
    channelItems(channel).then((items) => alive && (poolRef.current = poolOf(items, channel)), () => undefined);
    return () => {
      alive = false;
    };
  }, [channel]);

  useEffect(() => {
    let alive = true;
    startedRef.current = false;
    stoppedRef.current = false;
    retriedRef.current = false;
    fallbackDone.current = false;
    setError(null);
    setNext(null);
    if (thenId && /^[0-9a-f]{32}$/i.test(thenId)) getItem(thenId).then((t) => setNext(t), () => undefined);
    setCues([]);
    replayRef.current = null;
    setBuffering(true);
    localRef.current = null;
    (async () => {
      try {
        const dl = await playableDownload(id);
        if (!alive) return;
        if (dl) {
          // Downloaded: play the local file. Resume from Jellyfin's position when reachable, else the offline one.
          localRef.current = dl;
          const online = navigator.onLine ? await getItem(id).catch(() => null) : null;
          if (!alive) return;
          const it = online ?? dl.item;
          setItem(it);
          memKeyRef.current = memoryKey(it);
          const pending = await localPosition(id);
          const saved = pending ?? (it.UserData?.Played ? 0 : it.UserData?.PlaybackPositionTicks ?? 0);
          const startTicks = startParam != null ? Number(startParam) * TICKS_PER_SECOND : saved;
          const src = dl.item.MediaSources?.[0];
          const subs = dl.subtitles.map((x) => ({ Index: x.index, Type: "Subtitle", DisplayTitle: x.label, IsTextSubtitleStream: true }));
          const plan: PlayPlan = {
            url: localFileUrl(dl.path!),
            hls: false,
            method: "DirectPlay",
            playSessionId: "",
            source: { Id: src?.Id ?? id, MediaStreams: subs, DefaultSubtitleStreamIndex: src?.DefaultSubtitleStreamIndex },
          };
          const def = subs.find((x) => x.Index === src?.DefaultSubtitleStreamIndex);
          setSubIndex(def ? def.Index : -1);
          setAudioIndex(null);
          attach(plan, startTicks / TICKS_PER_SECOND);
          if (it.Type === "Episode" && it.SeriesId && online) {
            episodes(it.SeriesId)
              .then((all) => {
                const i = all.findIndex((e) => e.Id === it.Id);
                if (alive && !thenId) setNext(i >= 0 ? all[i + 1] ?? null : null);
              })
              .catch(() => undefined);
          }
          return;
        }
        const it = await getItem(id);
        if (!alive) return;
        setItem(it);
        memKeyRef.current = memoryKey(it);
        const saved = it.UserData?.Played ? 0 : it.UserData?.PlaybackPositionTicks ?? 0;
        const startTicks = startParam != null ? Number(startParam) * TICKS_PER_SECOND : saved;
        // The person's language prefs pick the first tracks (a choice made in the player menu wins after that)
        const pb = (await prefsSoon())?.playback;
        if (!alive) return;
        const fileSrc = it.MediaSources?.[0];
        const uid = getSession()?.userId ?? "";
        const fileStreams = fileSrc?.MediaStreams ?? [];
        // Precedence: what this person picked on this series/movie before, then the language prefs, then the file's default
        const entry = loadStore(uid)[memoryKey(it)];
        const langPrefs = pb ? { ...pb, alwaysSubs: getAlwaysSubs(uid) } : null;
        const first = chooseStart(fileStreams, entry, langPrefs, { defaultAudio: fileSrc?.DefaultAudioStreamIndex, defaultSub: -1 });
        const wantAudio = first.audio;
        const otherAudio = wantAudio != null && wantAudio !== fileSrc?.DefaultAudioStreamIndex;
        // A non-default audio track can't be switched in-file by browsers: same renegotiation as the audio menu.
        // The chosen track always goes into PlaybackInfo so a transcode carries it.
        const p = await negotiate(id, startTicks, wantAudio != null ? { audioStreamIndex: wantAudio, ...(otherAudio ? { allowDirectPlay: false } : {}) } : {});
        if (!alive) return;
        const src = p.source;
        const subs = (src.MediaStreams ?? []).filter((s) => s.Type === "Subtitle");
        const def = subs.find((s) => s.Index === src.DefaultSubtitleStreamIndex && s.IsTextSubtitleStream) ?? subs.find((s) => s.IsForced && s.IsTextSubtitleStream);
        // Indexes belong to one MediaSource: when the server used another version, the audio we chose doesn't apply to it
        const sameSource = !fileSrc || src.Id === fileSrc.Id;
        const start = chooseStart(src.MediaStreams ?? [], entry, langPrefs, {
          defaultAudio: src.DefaultAudioStreamIndex,
          defaultSub: def ? def.Index : -1,
          forceAudio: sameSource ? wantAudio ?? src.DefaultAudioStreamIndex ?? null : src.DefaultAudioStreamIndex ?? null,
        });
        const audio = start.audio;
        setSubIndex(start.subtitle);
        setAudioIndex(audio);
        attach(p, startTicks / TICKS_PER_SECOND);
        if (it.Type === "Episode" && it.SeriesId) {
          episodes(it.SeriesId).then((all) => {
            const i = all.findIndex((e) => e.Id === it.Id);
            if (alive && !thenId) setNext(i >= 0 ? all[i + 1] ?? null : null);
          });
        }
      } catch (e) {
        if (alive) setError(String((e as Error).message ?? e));
      }
    })();
    return () => {
      alive = false;
      stop();
      hlsRef.current?.destroy();
      hlsRef.current = null;
      const v = videoRef.current;
      if (v) {
        v.removeAttribute("src");
        v.load();
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // ---- reporting ----
  useEffect(() => {
    const t = setInterval(() => {
      if (startedRef.current && !stoppedRef.current) progress();
    }, PROGRESS_MS);
    const onUnload = () => stop();
    // Back from the bfcache: the page was "stopped" on pagehide, so the session has to be reopened
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted || !planRef.current) return;
      stoppedRef.current = false;
      startedRef.current = false;
      if (videoRef.current && !videoRef.current.paused) {
        startedRef.current = true;
        started();
      }
    };
    window.addEventListener("pagehide", onUnload);
    window.addEventListener("pageshow", onShow);
    return () => {
      clearInterval(t);
      window.removeEventListener("pagehide", onUnload);
      window.removeEventListener("pageshow", onShow);
    };
  }, [report, stop]);

  // ---- subtitles ----
  // Keyed on the media source, not the plan object: a plan whose stream list was refreshed (subtitle download) keeps its cues
  const planSourceId = plan?.source.Id;
  useEffect(() => {
    setCues([]);
    if (subIndex < 0 || !planSourceId) return;
    const ctl = new AbortController();
    const offline = localRef.current?.subtitles.find((x) => x.index === subIndex);
    (offline ? subtitleText(offline.key).then((t) => t ?? Promise.reject(new Error("missing"))) : fetchSubtitles(id, planSourceId, subIndex, ctl.signal))
      .then((txt) => !ctl.signal.aborted && setCues(parseVtt(txt)))
      .catch(() => !ctl.signal.aborted && setCues([]));
    return () => ctl.abort();
  }, [subIndex, planSourceId, id]);

  // The shift belongs to one title + one track: load it when either changes, save on every change (never from an effect, so a track
  // switch can't write the old value under the new key)
  useEffect(() => {
    const v = subIndex >= 0 ? loadNudge(id, subIndex) : 0;
    delayRef.current = v;
    setDelayState(v);
    setMatch({ status: "idle" });
  }, [id, subIndex]);
  const applyDelay = (v: number) => {
    const n = clampNudge(v);
    delayRef.current = n;
    setDelayState(n);
    if (subIndexRef.current >= 0) saveNudge(id, subIndexRef.current, n);
  };
  useEffect(() => () => matchCtl.current?.abort(), []);

  // ---- controls visibility ----
  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => {
      if (menuRef.current || videoRef.current?.paused) return;
      setControls(false);
      const ae = document.activeElement as HTMLElement | null;
      if (ae && ae.closest(".player-top, .player-bottom, .player-track-trigger")) ae.blur();
    }, HIDE_MS);
  }, []);

  const exit = useCallback(() => {
    stop();
    leaveLandscape();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
    back(channel ? "/channels" : `/item/${id}`);
  }, [stop, id, channel]);

  const playNext = useCallback(() => {
    if (channel) {
      // Whatever the clock says is on now: the next scheduled item at its scheduled offset, never a saved resume position
      const pool = poolRef.current;
      const sch = pool ? schedule(pool, channel.id, Date.now(), 3) : null;
      const t = sch ? chainTarget(sch.slots, sch.offsetS, id) : next ? { id: next.Id, startS: 0, thenId: null, title: next.Name } : null;
      if (!t || !takeChainHop()) {
        // nothing to show, or too many hops lately (a file shorter than its runtime would loop): back to the channels
        exit();
        return;
      }
      stop();
      go(tunePath(t, channel.id), true);
      return;
    }
    if (!next) return;
    stop();
    go(`/play/${next.Id}`, true);
  }, [next, stop, channel, id, exit]);

  const fromStart = () => {
    const v = videoRef.current;
    if (!v) return;
    seekTarget.current = 0;
    v.currentTime = 0;
    setOff(true);
    if (v.paused) tryPlay(v);
  };
  const backToLive = () => {
    if (!channel) return;
    void rejoinPlan(channel.id, id).then((r) => {
      if (!r) return;
      if (r.kind === "seek") {
        const v = videoRef.current;
        if (!v) return;
        seekTarget.current = r.s;
        v.currentTime = r.s;
        setOff(false);
      } else {
        stop();
        go(r.path, true);
      }
    });
  };

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) tryPlay(v);
    else {
      v.pause();
      setPauseFlash(true);
      if (pauseFlashTimer.current) clearTimeout(pauseFlashTimer.current);
      pauseFlashTimer.current = window.setTimeout(() => setPauseFlash(false), 500);
    }
  };
  // Repeated skips (double-tap, held arrow) add up from where the last one is going, not from where playback still is:
  // a transcode seek takes a moment, and currentTime has not moved yet when the next tap lands
  const seekTarget = useRef<number | null>(null);
  const seekBy = (s: number) => {
    const v = videoRef.current;
    if (!v) return;
    const base = ramp.current?.target ?? seekTarget.current ?? v.currentTime; // a click mid-burst builds on the pending target
    if (ramp.current) {
      ramp.current = null;
      if (settleTimer.current) clearTimeout(settleTimer.current);
      settleTimer.current = null;
      setPendingSeek(null);
    }
    const to = Math.min(Math.max(base + s, 0), (v.duration || duration) - 1);
    seekTarget.current = to;
    v.currentTime = to;
  };
  // Keyboard / remote seeking: presses ramp a pending target (shown on the bar), committed once they settle
  const [pendingSeek, setPendingSeek] = useState<number | null>(null);
  const ramp = useRef<SeekRamp | null>(null);
  const settleTimer = useRef<number | null>(null);
  const heldKey = useRef(false); // an arrow is physically down: don't commit yet
  const commitSeek = () => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = null;
    const r = ramp.current;
    ramp.current = null;
    setPendingSeek(null);
    const v = videoRef.current;
    if (r && v) {
      seekTarget.current = r.target;
      v.currentTime = r.target;
    }
  };
  const keySeek = (dir: -1 | 1, e: KeyboardEvent) => {
    const v = videoRef.current;
    if (!v) return;
    const now = performance.now();
    const r = advanceSeek(ramp.current, { dir, now, repeat: e.repeat, shift: e.shiftKey, base: seekTarget.current ?? v.currentTime, duration: v.duration || duration });
    ramp.current = r;
    setPendingSeek(r.target);
    if (settleTimer.current) clearTimeout(settleTimer.current);
    const wait = () => {
      if (commitDue(ramp.current, performance.now(), heldKey.current)) commitSeek();
      else settleTimer.current = window.setTimeout(wait, 60);
    };
    settleTimer.current = window.setTimeout(wait, SETTLE_MS);
  };
  const [seekMeta, setSeekMeta] = useState<SeekMeta | null>(null);
  useEffect(() => {
    let alive = true;
    setSeekMeta(null);
    // a pending seek belongs to the previous item: drop it
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = null;
    ramp.current = null;
    seekTarget.current = null;
    heldKey.current = false;
    setPendingSeek(null);
    getSeekMeta(id).then((m) => alive && setSeekMeta(m), () => undefined);
    return () => {
      alive = false;
    };
  }, [id]);
  useEffect(() => () => { if (settleTimer.current) clearTimeout(settleTimer.current); }, []);
  const marks = chapterMarks(seekMeta?.chapters, duration || Infinity);
  const thumbs = plan && seekMeta ? trickplaySource(id, plan.source.Id, seekMeta.trickplay) : null;
  const chapterSeek = (dir: -1 | 1) => {
    const v = videoRef.current;
    if (!v) return;
    const from = ramp.current?.target ?? seekTarget.current ?? v.currentTime;
    const to = dir > 0 ? nextChapter(marks, from) : prevChapter(marks, from);
    if (to == null) return;
    ramp.current = { target: to, dir, presses: 1, burstStart: performance.now(), lastAt: performance.now() };
    commitSeek();
  };
  const boxRef = useRef<HTMLDivElement>(null);
  const { toggle: toggleFs } = useFullscreen(boxRef, videoRef);
  const stepDelay = (d: number) => applyDelay(delayRef.current + d);
  const onRotate = () => {
    void rotatePlayer(boxRef.current);
  };

  // Restore the prior player mode when this screen leaves.
  useEffect(() => {
    return () => {
      leaveLandscape();
      if (pauseFlashTimer.current) clearTimeout(pauseFlashTimer.current);
    };
  }, []);

  // ---- touch: tap = show/hide controls, double-tap left/right half = -/+10 s (repeat taps keep seeking) ----
  const lastTap = useRef<{ at: number; side: number } | null>(null);
  const tapTimer = useRef<number | null>(null);
  const touchClick = useRef(false);
  const [seekFlash, setSeekFlash] = useState<{ side: number; n: number } | null>(null);
  const flashTimer = useRef<number | null>(null);
  const onVideoPointerUp = (e: React.PointerEvent<HTMLVideoElement>) => {
    if (e.pointerType === "mouse") return;
    touchClick.current = true; // the click that follows belongs to this touch
    const rect = e.currentTarget.getBoundingClientRect();
    const side = e.clientX - rect.left < rect.width / 2 ? -1 : 1;
    const now = Date.now();
    const prev = lastTap.current;
    if (prev && now - prev.at < DOUBLE_TAP_MS && prev.side === side) {
      if (tapTimer.current) clearTimeout(tapTimer.current);
      tapTimer.current = null;
      seekBy(10 * side);
      setSeekFlash((f) => ({ side, n: f && f.side === side ? f.n + 1 : 1 }));
      if (flashTimer.current) clearTimeout(flashTimer.current);
      flashTimer.current = window.setTimeout(() => setSeekFlash(null), 700);
      lastTap.current = { at: now, side };
      return;
    }
    lastTap.current = { at: now, side };
    if (tapTimer.current) clearTimeout(tapTimer.current);
    tapTimer.current = window.setTimeout(() => {
      tapTimer.current = null;
      togglePlay();
      poke();
    }, DOUBLE_TAP_MS);
  };

  // ---- keyboard (document listener runs before the app's window-level nav) ----
  const repeat = useRef(0);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const tg = e.target as HTMLElement | null;
      const typing = !!tg && (tg.tagName === "TEXTAREA" || tg.tagName === "SELECT" || tg.isContentEditable || (tg.tagName === "INPUT" && (tg as HTMLInputElement).type !== "range"));
      if (typing && (e.key === "ArrowLeft" || e.key === "ArrowRight" || e.key === "PageUp" || e.key === "PageDown")) return;
      if (findSubs) { poke(); return; } // the subtitle dialog owns the keys
      const mk = playerKeyWhileMenu(menu, e.key);
      if (mk === "close") {
        e.preventDefault();
        e.stopPropagation();
        setMenu(false);
        poke();
        return;
      }
      if (mk === "defer") {
        poke();
        return; // the menu handles its own keys
      }
      let handled = true;
      switch (e.key) {
        case " ":
        case "k":
          togglePlay();
          break;
        case "Enter":
          if ((document.activeElement as HTMLElement)?.tagName === "BUTTON" && controls) handled = false;
          else togglePlay();
          break;
        case "ArrowLeft":
          heldKey.current = true;
          keySeek(-1, e);
          break;
        case "ArrowRight":
          heldKey.current = true;
          keySeek(1, e);
          break;
        case "PageUp":
        case "PageDown":
          if (marks.length) chapterSeek(e.key === "PageUp" ? -1 : 1);
          else handled = false;
          break;
        case "ArrowUp":
          if (videoRef.current) videoRef.current.volume = Math.min(1, videoRef.current.volume + 0.1);
          break;
        case "ArrowDown":
          if (videoRef.current) videoRef.current.volume = Math.max(0, videoRef.current.volume - 0.1);
          break;
        case "f":
          toggleFs();
          break;
        case "m":
          if (videoRef.current) videoRef.current.muted = !videoRef.current.muted;
          break;
        case "[":
        case "]": {
          // Hold to accelerate: 0.1 s steps, then 0.5 s once the key has repeated for a while
          repeat.current = e.repeat ? repeat.current + 1 : 0;
          const step = repeat.current > 15 ? 0.5 : 0.1;
          stepDelay(e.key === "]" ? step : -step);
          break;
        }
        case "s":
          setMenu(true);
          break;
        case "n":
          playNext();
          break;
        case "Escape":
        case "Backspace":
          if (menu) setMenu(false);
          else exit();
          break;
        default:
          handled = false;
      }
      if (handled) e.preventDefault();
      poke();
    };
    const onUp = (e: KeyboardEvent) => {
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") heldKey.current = false;
    };
    const onBlur = () => {
      heldKey.current = false;
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("keyup", onUp);
    window.addEventListener("blur", onBlur);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("keyup", onUp);
      window.removeEventListener("blur", onBlur);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [menu, findSubs, controls, duration, exit, playNext, poke, marks.length, seekMeta]);

  const shownCues = subIndex >= 0 ? activeCues(cues, time, delay) : [];
  const subs = (plan?.source.MediaStreams ?? []).filter((s) => s.Type === "Subtitle");
  const audios = (plan?.source.MediaStreams ?? []).filter((s) => s.Type === "Audio");
  /** A downloaded subtitle is now listed on the item: show it in the menu without renegotiating, and keep the active track selected. */
  const addedSubtitles = (fresh: Item) => {
    const p = planRef.current;
    const streams = fresh.MediaSources?.find((m) => m.Id === p?.source.Id)?.MediaStreams;
    if (!p || !streams) return;
    const old = p.source.MediaStreams ?? [];
    setSubIndex((cur) => remapActive(old, streams, cur));
    const np = { ...p, source: { ...p.source, MediaStreams: streams } };
    planRef.current = np;
    setPlan(np);
  };
  /** Auto-match timing: server aligns the selected subtitle to the audio and adds a new track; switch to it when it is listed. */
  const runAutoMatch = () => {
    const idx = subIndexRef.current;
    if (idx < 0 || matchCtl.current) return;
    const ctl = new AbortController();
    matchCtl.current = ctl;
    setMatch({ status: "matching", text: progressText(null) });
    void (async () => {
      const out = await autoMatch(id, idx, (eta) => setMatch({ status: "matching", text: progressText(eta) }), ctl.signal);
      matchCtl.current = null;
      if (ctl.signal.aborted) return;
      if ("error" in out) { setMatch({ status: "failed", text: out.error }); flashNote(out.error); return; }
      setMatch({ status: "idle" });
      const r = out.result;
      flashNote(doneNote(r));
      if (r.alreadySynced) return;
      // The new track: the server gives its index when Jellyfin had listed it; otherwise re-read the item a few times
      for (let i = 0; i < 4; i++) {
        const fresh = await getItem(id).catch(() => null);
        const streams = fresh?.MediaSources?.find((m) => m.Id === planRef.current?.source.Id)?.MediaStreams;
        const at = streams ? findNewTrack(planRef.current?.source.MediaStreams ?? [], streams, r.newStreamIndex) : null;
        if (fresh && at != null) {
          addedSubtitles(fresh);
          replayRef.current = null;
          remember({ subtitle: at });
          setSubIndex(at);
          return;
        }
        await new Promise((res) => setTimeout(res, 2000));
      }
      flashNote("Matched, but the new track isn't listed yet. Check the menu in a moment.");
    })();
  };
  const onMenuOpenChange = useCallback((o: boolean) => { setMenu(o); menuRef.current = o; if (o) setControls(true); else poke(); }, [poke]);
  const textSubs = subs.filter((x) => x.IsTextSubtitleStream);
  /** "What did they say?": back ~10 s with captions on, then the previous subtitle state returns once that span has played */
  const replayCaptions = () => {
    const v = videoRef.current;
    if (!v) return;
    const pick = textSubs.find((x) => x.Index === plan?.source.DefaultSubtitleStreamIndex) ?? textSubs[0];
    const r = startReplay(v.currentTime, subIndex, pick ? pick.Index : null, 0, replayRef.current);
    if (!r) return;
    replayRef.current = r.replay;
    if (r.show != null) setSubIndex(r.show);
    seekTarget.current = r.seekTo;
    v.currentTime = r.seekTo;
    setMenu(false);
  };
  const remaining = duration - time;
  const title = item ? (item.Type === "Episode" ? `${item.SeriesName} · S${item.ParentIndexNumber} E${item.IndexNumber} · ${item.Name}` : item.Name) : "";

  return (
    <div ref={boxRef} className={`player${controls || paused ? " show" : ""}`} style={subVars as CSSProperties} data-sub-bg={subPrefs.background} onMouseMove={poke} onClick={() => !touchClick.current && poke()}>
      <video
        ref={videoRef}
        className="player-video"
        playsInline
        onClick={() => {
          // Touch taps are handled in onPointerUp (show/hide controls, double-tap seek); the mouse click plays/pauses
          if (touchClick.current) touchClick.current = false;
          else togglePlay();
        }}
        onPointerUp={onVideoPointerUp}
        onDoubleClick={() => !isPhone() && toggleFs()}
        onPlay={() => setPaused(false)}
        onPause={() => {
          setPaused(true);
          if (videoRef.current?.readyState) setBuffering(false);
          setControls(true);
          if (startedRef.current) progress();
        }}
        onPlaying={() => {
          setBuffering(false);
          setNeedsTap(false);
          netRetries.current = 0;
          if (!startedRef.current && planRef.current) {
            startedRef.current = true;
            started();
          }
        }}
        onWaiting={() => setBuffering(true)}
        onSeeked={() => {
          watch.seeked();
          seekTarget.current = null;
          if (startedRef.current) progress();
        }}
        onTimeUpdate={(e) => {
          const t = e.currentTarget.currentTime;
          setTime(t);
          watch.tick(t);
          const r = replayRef.current;
          if (r && replayDone(r, t)) {
            replayRef.current = null;
            setSubIndex(r.restore);
          }
        }}
        onDurationChange={(e) => setDuration(isFinite(e.currentTarget.duration) ? e.currentTarget.duration : (item?.RunTimeTicks ?? 0) / TICKS_PER_SECOND)}
        onEnded={() => {
          watch.end(true);
          stop();
          if (next || channel) playNext();
          else exit();
        }}
        onError={() => {
          // Direct play, or HLS played natively (Safari): hls.js reports its own errors, the element covers the rest
          if (!hlsRef.current && videoRef.current?.getAttribute("src")) fallbackRef.current();
        }}
      />
      {shownCues.length > 0 && (
        <div className="subs">
          {shownCues.map((c, i) => (
            <div key={i} dangerouslySetInnerHTML={{ __html: c.text.replace(/&/g, "&amp;").replace(/<(?!\/?i>)/g, "&lt;").replace(/\n/g, "<br>") }} />
          ))}
        </div>
      )}
      {buffering && !error && !needsTap && <div className="spinner" />}
      {pauseFlash && <div className="pause-flash" role="status" aria-label="Paused">Ⅱ</div>}
      {seekFlash && (
        <div className={`seek-flash ${seekFlash.side < 0 ? "left" : "right"}`} aria-live="polite">
          {seekFlash.side < 0 ? "−" : "+"}
          {10 * seekFlash.n} s
        </div>
      )}
      {paused && !pauseFlash && (needsTap || !buffering) && !error && (
        <button
          className="tap-play"
          data-nav
          data-autofocus
          aria-label="Play"
          onClick={(e) => {
            e.stopPropagation();
            if (videoRef.current) tryPlay(videoRef.current);
          }}
        >
          ▶
        </button>
      )}
      {error && (
        <div className="player-error">
          <p>{error}</p>
          <button className="btn" data-nav data-autofocus onClick={exit}>
            Back
          </button>
        </div>
      )}

      <div className="player-top">
        <button className="btn ghost" data-nav onClick={exit} aria-label="Back">
          ‹ Back
        </button>
        <span className="player-title">{title}</span>
        {channel && off && <span className="player-method">Off schedule</span>}
        {plan && <span className="player-method">{plan.method === "DirectPlay" ? "Direct" : plan.method === "DirectStream" ? "Remux" : "Transcode"}</span>}
      </div>

      <TrackMenu
        open={menu}
        onOpenChange={onMenuOpenChange}
        audio={audios.map((a) => ({ id: String(a.Index), label: jellyfinAudioLabel(a), active: a.Index === audioIndex }))}
        subtitles={subs.map((x) => ({ id: String(x.Index), label: subLabel(x) + (x.IsTextSubtitleStream ? "" : " (image, not supported yet)"), active: x.Index === subIndex, disabled: !x.IsTextSubtitleStream }))}
        subtitlesOff={subIndex < 0}
        onPickAudio={(v) => chooseAudio(Number(v))}
        onPickSubtitle={(v) => { replayRef.current = null; const n = v == null ? -1 : Number(v); remember({ subtitle: n }); setSubIndex(n); }}
        prefs={subPrefs}
        onPrefs={setSubPrefs}
        replay={textSubs.length ? { disabled: replayTarget(time, 0) == null, run: replayCaptions } : undefined}
        barClassName="player-track-trigger"
        menuClassName="sub-menu"
        onActivity={poke}
        findSubtitles={canFindSubs && item && !localRef.current ? () => setFindSubs(true) : undefined}
        timing={item && !localRef.current ? {
          canMatch: textSubs.some((x) => x.Index === subIndex),
          status: match.status,
          text: match.text,
          onMatch: runAutoMatch,
          nudge: delay,
          onNudge: (dir) => applyDelay(stepNudge(delayRef.current, dir)),
        } : undefined}
        extra={subIndex >= 0 ? (
          <button type="button" role="menuitem" data-track-item data-nav className="btn small" onClick={() => applyDelay(0)}>
            Reset delay ({delay.toFixed(1)} s)
          </button>
        ) : undefined}
      />

      {findSubs && item && plan && (
        <SubtitleSearchDialog
          itemId={id}
          title={title}
          sourceId={plan.source.Id}
          subtitleLangs={prefs?.playback.subtitleLangs}
          where="player"
          onClose={() => setFindSubs(false)}
          onAdded={addedSubtitles}
        />
      )}

      {next && remaining > 0 && remaining < NEXT_UP_SECONDS && (
        <button className="btn primary next-up" data-nav onClick={playNext}>
          {thenId ? `Up next · ${next.Name}` : `Next episode · E${next.IndexNumber} ${next.Name}`}
        </button>
      )}

      <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
        <Scrubber time={time} max={duration || 0} fmt={clock} onActivity={poke} thumbs={thumbs} chapters={marks} pending={pendingSeek} onSeek={(t) => videoRef.current && (videoRef.current.currentTime = t)} />
        <div className="player-row">
          <button className="btn ghost" data-nav onClick={togglePlay}>
            {paused ? "Play" : "Pause"}
          </button>
          <button className="btn ghost" data-nav onClick={() => seekBy(-10)}>
            −10s
          </button>
          <button className="btn ghost" data-nav onClick={() => seekBy(30)}>
            +30s
          </button>
          <span className="clock">
            {clock(time)} / {clock(duration)}
          </span>
          <span className="spacer" />
          {subIndex >= 0 && (
            <span className="delay">
              <HoldButton label="−" onStep={(s) => stepDelay(-s)} />
              <span className="delay-val">
                Subs {delay > 0 ? "+" : ""}
                {delay.toFixed(1)} s
              </span>
              <HoldButton label="+" onStep={(s) => stepDelay(s)} />
            </span>
          )}
          {channel && (off ? (
            <button className="btn ghost" data-nav onClick={backToLive}>
              Back to live
            </button>
          ) : (
            <button className="btn ghost" data-nav onClick={fromStart}>
              From start
            </button>
          ))}
          {next && (
            <button className="btn ghost" data-nav onClick={playNext}>
              Next ›
            </button>
          )}
          <button className="btn ghost" data-nav onClick={toggleFs}>
            Fullscreen
          </button>
          {canOfferRotate(isNative, isPhone(), typeof (screen.orientation as LockableOrientation | undefined)?.lock === "function") && (
            <button className="btn ghost" data-nav aria-label="Rotate player" onClick={onRotate}>Rotate</button>
          )}
        </div>
      </div>

    </div>
  );
}

/** ±0.1 s per press; held, it repeats and speeds up (0.1 → 0.5 s steps). */
function HoldButton({ label, onStep }: { label: string; onStep: (step: number) => void }) {
  const timer = useRef<number | null>(null);
  const count = useRef(0);
  const stopHold = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  const tick = () => {
    count.current++;
    onStep(count.current > 12 ? 0.5 : 0.1);
    timer.current = window.setTimeout(tick, count.current > 4 ? 80 : 220);
  };
  useEffect(() => stopHold, []);
  return (
    <button
      className="btn ghost small"
      data-nav
      onPointerDown={(e) => {
        e.preventDefault();
        count.current = 0;
        tick();
      }}
      onPointerUp={stopHold}
      onPointerLeave={stopHold}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          e.stopPropagation();
          onStep(e.repeat ? 0.5 : 0.1);
        }
      }}
      aria-label={label === "+" ? "Subtitles later" : "Subtitles earlier"}
    >
      {label}
    </button>
  );
}
