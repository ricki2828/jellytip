// Player: direct play when the browser can, else Jellyfin HLS via hls.js (one automatic retry as a plain H.264 transcode).
// Reports start / progress (10 s) / stop so resume works with the TV; text subtitles with a delay control; next episode.
import { useCallback, useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import { episodes, getItem, TICKS_PER_SECOND, type Item, type MediaStream } from "../api/jellyfin";
import { fetchSubtitles, negotiate, reportProgress, reportStart, reportStopped, stopEncoding, type PlayPlan } from "../api/playback";
import { activeCues, parseVtt, type Cue } from "../lib/vtt";
import { back, go } from "../lib/router";
import { isPhone } from "../lib/device";

const PROGRESS_MS = 10_000;
const HIDE_MS = 3000;
const NEXT_UP_SECONDS = 40;
const DELAY_KEY = "jellytip.subDelay";
const NET_RETRIES = 3;
const DOUBLE_TAP_MS = 300;

type LockableOrientation = ScreenOrientation & { lock?: (o: string) => Promise<void>; unlock?: () => void };

/** Phone: go fullscreen and turn to landscape (Android allows the lock only while fullscreen, and only from a tap). */
async function enterLandscape(box: HTMLElement | null) {
  if (!isPhone() || !box) return;
  try {
    if (!document.fullscreenElement && box.requestFullscreen) await box.requestFullscreen({ navigationUI: "hide" });
    await (screen.orientation as LockableOrientation | undefined)?.lock?.("landscape");
  } catch {
    /* no gesture, or not supported (iOS): stay as is */
  }
}

function leaveLandscape() {
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

export function Player({ id, startParam }: { id: string; startParam: string | null }) {
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
  const [subIndex, setSubIndex] = useState<number>(-1);
  const [cues, setCues] = useState<Cue[]>([]);
  const [delay, setDelay] = useState<number>(() => Number(localStorage.getItem(DELAY_KEY) ?? 0) || 0);
  const [next, setNext] = useState<Item | null>(null);
  const [audioIndex, setAudioIndex] = useState<number | null>(null);
  const [needsTap, setNeedsTap] = useState(false);
  const netRetries = useRef(0);
  const hideTimer = useRef<number | null>(null);
  const subIndexRef = useRef(-1);
  subIndexRef.current = subIndex;

  const position = () => (videoRef.current?.currentTime ?? 0) * TICKS_PER_SECOND;
  const report = useCallback(
    () => ({ itemId: id, plan: planRef.current!, positionTicks: position(), paused: videoRef.current?.paused, subtitleStreamIndex: subIndexRef.current }),
    [id],
  );

  const stop = useCallback(() => {
    const p = planRef.current;
    if (!p || stoppedRef.current) return;
    stoppedRef.current = true;
    if (startedRef.current) reportStopped(report());
    else stopEncoding(p); // never started: no session to stop, but the server may already be transcoding
  }, [report]);

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
      hls.loadSource(u.pathname + u.search);
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
    fallbackDone.current = true;
    const at = videoRef.current?.currentTime || 0;
    abandonPlan();
    try {
      const p = await negotiate(id, Math.round(at * TICKS_PER_SECOND), { h264Only: true, audioStreamIndex: audioIndex ?? undefined });
      attach(p, at);
    } catch (e) {
      setError(String((e as Error).message ?? e));
    }
  }, [id, attach, abandonPlan, audioIndex]);
  fallbackRef.current = fallback;

  /** Switch audio track: renegotiate at the current position (no direct play: browsers can't switch tracks in-file). */
  const chooseAudio = async (index: number) => {
    setMenu(false);
    if (!planRef.current || index === audioIndex) return;
    const v = videoRef.current;
    const at = v?.currentTime || 0;
    const def = planRef.current.source.DefaultAudioStreamIndex;
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

  useEffect(() => {
    let alive = true;
    startedRef.current = false;
    stoppedRef.current = false;
    retriedRef.current = false;
    fallbackDone.current = false;
    setError(null);
    setNext(null);
    setCues([]);
    setBuffering(true);
    (async () => {
      try {
        const it = await getItem(id);
        if (!alive) return;
        setItem(it);
        const saved = it.UserData?.Played ? 0 : it.UserData?.PlaybackPositionTicks ?? 0;
        const startTicks = startParam != null ? Number(startParam) * TICKS_PER_SECOND : saved;
        const p = await negotiate(id, startTicks);
        if (!alive) return;
        const src = p.source;
        const subs = (src.MediaStreams ?? []).filter((s) => s.Type === "Subtitle");
        const def = subs.find((s) => s.Index === src.DefaultSubtitleStreamIndex && s.IsTextSubtitleStream) ?? subs.find((s) => s.IsForced && s.IsTextSubtitleStream);
        setSubIndex(def ? def.Index : -1);
        setAudioIndex(src.DefaultAudioStreamIndex ?? null);
        attach(p, startTicks / TICKS_PER_SECOND);
        if (it.Type === "Episode" && it.SeriesId) {
          episodes(it.SeriesId).then((all) => {
            const i = all.findIndex((e) => e.Id === it.Id);
            if (alive) setNext(i >= 0 ? all[i + 1] ?? null : null);
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
      if (startedRef.current && !stoppedRef.current) reportProgress(report());
    }, PROGRESS_MS);
    const onUnload = () => stop();
    // Back from the bfcache: the page was "stopped" on pagehide, so the session has to be reopened
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted || !planRef.current) return;
      stoppedRef.current = false;
      startedRef.current = false;
      if (videoRef.current && !videoRef.current.paused) {
        startedRef.current = true;
        reportStart(report());
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
  useEffect(() => {
    setCues([]);
    if (subIndex < 0 || !plan) return;
    const ctl = new AbortController();
    fetchSubtitles(id, plan.source.Id, subIndex, ctl.signal)
      .then((txt) => setCues(parseVtt(txt)))
      .catch(() => !ctl.signal.aborted && setCues([]));
    return () => ctl.abort();
  }, [subIndex, plan, id]);

  useEffect(() => {
    localStorage.setItem(DELAY_KEY, String(delay));
  }, [delay]);

  // ---- controls visibility ----
  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => {
      if (!videoRef.current?.paused) setControls(false);
    }, HIDE_MS);
  }, []);

  const exit = useCallback(() => {
    stop();
    leaveLandscape();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
    back(`/item/${id}`);
  }, [stop, id]);

  const playNext = useCallback(() => {
    if (!next) return;
    stop();
    go(`/play/${next.Id}`, true);
  }, [next, stop]);

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) tryPlay(v);
    else v.pause();
  };
  const seekBy = (s: number) => {
    const v = videoRef.current;
    if (!v) return;
    v.currentTime = Math.min(Math.max(v.currentTime + s, 0), (v.duration || duration) - 1);
  };
  const toggleFs = () => {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => undefined);
      return;
    }
    const box = document.querySelector<HTMLElement>(".player");
    if (box?.requestFullscreen) box.requestFullscreen().catch(() => undefined);
    else {
      // iPhone Safari has no element fullscreen: only the video itself can go fullscreen (native controls)
      const v = videoRef.current as (HTMLVideoElement & { webkitEnterFullscreen?: () => void }) | null;
      v?.webkitEnterFullscreen?.();
    }
  };
  const stepDelay = (d: number) => setDelay((x) => Math.round((x + d) * 10) / 10);
  const boxRef = useRef<HTMLDivElement>(null);

  // Phone: try landscape at once (works when the Play tap's activation is still fresh), else on the first tap
  useEffect(() => {
    enterLandscape(boxRef.current);
    return leaveLandscape;
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
    enterLandscape(boxRef.current);
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
      if (controls && !videoRef.current?.paused) setControls(false);
      else poke();
    }, DOUBLE_TAP_MS);
  };

  // ---- keyboard (document listener runs before the app's window-level nav) ----
  const repeat = useRef(0);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (menu && e.key !== "Escape") {
        poke();
        return; // let spatial nav move in the menu
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
          seekBy(-10);
          break;
        case "ArrowRight":
          seekBy(10);
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
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [menu, controls, duration, exit, playNext, poke]);

  const v = videoRef.current;
  const shownCues = subIndex >= 0 ? activeCues(cues, time, delay) : [];
  const subs = (plan?.source.MediaStreams ?? []).filter((s) => s.Type === "Subtitle");
  const audios = (plan?.source.MediaStreams ?? []).filter((s) => s.Type === "Audio");
  const remaining = duration - time;
  const title = item ? (item.Type === "Episode" ? `${item.SeriesName} · S${item.ParentIndexNumber} E${item.IndexNumber} · ${item.Name}` : item.Name) : "";

  return (
    <div ref={boxRef} className={`player${controls || paused ? " show" : ""}`} onMouseMove={poke} onClick={() => !touchClick.current && poke()}>
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
          setControls(true);
          if (startedRef.current) reportProgress(report());
        }}
        onPlaying={() => {
          setBuffering(false);
          setNeedsTap(false);
          netRetries.current = 0;
          if (!startedRef.current && planRef.current) {
            startedRef.current = true;
            reportStart(report());
          }
        }}
        onWaiting={() => setBuffering(true)}
        onSeeked={() => startedRef.current && reportProgress(report())}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onDurationChange={(e) => setDuration(isFinite(e.currentTarget.duration) ? e.currentTarget.duration : (item?.RunTimeTicks ?? 0) / TICKS_PER_SECOND)}
        onEnded={() => {
          stop();
          if (next) playNext();
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
      {seekFlash && (
        <div className={`seek-flash ${seekFlash.side < 0 ? "left" : "right"}`} aria-live="polite">
          {seekFlash.side < 0 ? "−" : "+"}
          {10 * seekFlash.n} s
        </div>
      )}
      {needsTap && !error && (
        <button
          className="tap-play"
          data-nav
          data-autofocus
          aria-label="Play"
          onClick={(e) => {
            e.stopPropagation();
            enterLandscape(boxRef.current);
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
        {plan && <span className="player-method">{plan.method === "DirectPlay" ? "Direct" : plan.method === "DirectStream" ? "Remux" : "Transcode"}</span>}
      </div>

      {next && remaining > 0 && remaining < NEXT_UP_SECONDS && (
        <button className="btn primary next-up" data-nav onClick={playNext}>
          Next episode · E{next.IndexNumber} {next.Name}
        </button>
      )}

      <div className="player-bottom" onClick={(e) => e.stopPropagation()}>
        <input
          className="seek"
          type="range"
          min={0}
          max={duration || 0}
          step={1}
          value={Math.min(time, duration || 0)}
          onChange={(e) => {
            if (v) v.currentTime = Number(e.target.value);
          }}
          style={{ ["--p" as string]: `${duration ? (time / duration) * 100 : 0}%` }}
          aria-label="Seek"
        />
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
          <button className="btn ghost" data-nav onClick={() => setMenu((m) => !m)}>
            {audios.length > 1 ? "Audio & subtitles" : "Subtitles"}
          </button>
          {next && (
            <button className="btn ghost" data-nav onClick={playNext}>
              Next ›
            </button>
          )}
          <button className="btn ghost" data-nav onClick={toggleFs}>
            Fullscreen
          </button>
        </div>
      </div>

      {menu && <div className="sheet-scrim" onClick={() => setMenu(false)} />}
      {menu && (
        <div className="sub-menu" onClick={(e) => e.stopPropagation()}>
          {audios.length > 1 && (
            <>
              <div className="menu-head">Audio</div>
              {audios.map((a) => (
                <button key={a.Index} data-nav className={a.Index === audioIndex ? "on" : ""} onClick={() => chooseAudio(a.Index)}>
                  {subLabel(a)}
                </button>
              ))}
              <div className="menu-head">Subtitles</div>
            </>
          )}
          <button
            data-nav
            data-autofocus
            className={subIndex < 0 ? "on" : ""}
            onClick={() => {
              setSubIndex(-1);
              setMenu(false);
            }}
          >
            Off
          </button>
          {subs.map((s) => (
            <button
              key={s.Index}
              data-nav
              disabled={!s.IsTextSubtitleStream}
              className={s.Index === subIndex ? "on" : ""}
              onClick={() => {
                setSubIndex(s.Index);
                setMenu(false);
              }}
            >
              {subLabel(s)}
              {!s.IsTextSubtitleStream && " (image, not supported yet)"}
            </button>
          ))}
          {subIndex >= 0 && (
            <button data-nav onClick={() => setDelay(0)}>
              Reset delay ({delay.toFixed(1)} s)
            </button>
          )}
        </div>
      )}
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
