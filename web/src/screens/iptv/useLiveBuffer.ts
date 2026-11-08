// Live buffer for the web IPTV player: applies lib/liveBuffer.ts (profiles + Auto policy) to the running mpegts / hls.js engine.
// Nothing here reopens a stream, takes a provider connection or touches lease / recovery / teardown:
//   hls.js  - hls.config is mutated live (+ one seek back inside the existing window when the target moves further from the edge)
//   mpegts  - the library chaser is off; this hook does the same in-range seek, and builds runway by pausing the <video> briefly
//             (never the transport) after an attach and after a rebuffer. Only the IO stash size needs a re-attach (next natural one).
import { useCallback, useEffect, useRef, useState, type MutableRefObject } from "react";
import type Hls from "hls.js";
import { debugEvent } from "../../lib/debugLog";
import {
  HOLD_MAX_MS, HOLD_NO_GROWTH_MS, chaseTarget, effectiveProfile, holdAllowed, hlsRuntime, holdTarget, initAuto, loadChannelLevel, loadDefaultMode, onStall, onTick,
  saveChannelLevel, toastDue, type AutoState, type Mode, type Profile,
} from "../../lib/liveBuffer";

const store = (): Storage | null => { try { return localStorage; } catch { return null; } };

interface Hold { phase: "start" | "stall"; target: number; at: number; lastEnd: number; lastGrowth: number }

export interface LiveBufferOpts {
  videoRef: MutableRefObject<HTMLVideoElement | null>;
  hlsRef: MutableRefObject<Hls | null>;
  engineRef: MutableRefObject<string>;
  /** the url of the current attach: a change means a (re)attach, which gets a fresh start hold */
  linkRef: MutableRefObject<string | null>;
  /** live, not replay, and the picture is up */
  active: boolean;
  /** only mpegts live (not catch-up / growing recordings) is held and chased */
  kindIsLive: () => boolean;
  channelKey: string | null;
  /** called every tick of a deliberate hold, so the stall-watch does not read the hold as a frozen picture */
  touch: () => void;
}

export function useLiveBuffer(o: LiveBufferOpts) {
  const [mode, setModeState] = useState<Mode>(() => loadDefaultMode(store()));
  const [, rerender] = useState(0);
  const [toast, setToast] = useState<string | null>(null);
  const [holding, setHolding] = useState(false);
  const modeRef = useRef(mode);
  modeRef.current = mode;
  const auto = useRef<AutoState>(initAuto(null, Date.now()));
  const keyRef = useRef<string | null>(null);
  const hold = useRef<Hold | null>(null);
  const holdingRef = useRef(false);
  const pendingStall = useRef(false);
  /** The user just picked a buffer mode: build the new profile's cushion now instead of waiting for the next stall (mpegts). */
  const pendingPick = useRef(false);
  /** our own v.pause() has been issued and its `pause` event is still to come */
  const expectPause = useRef(false);
  const startDoneFor = useRef<string | null>(null);
  const lastToast = useRef(0);
  const lastBadHold = useRef(0);
  const stallsThisSession = useRef(0);
  const appliedHls = useRef<{ hls: Hls | null; p: Profile | null; key: string }>({ hls: null, p: null, key: "" });
  const optsRef = useRef(o);
  optsRef.current = o;

  // New channel: seed Auto from what this channel needed last time
  if (keyRef.current !== o.channelKey) {
    keyRef.current = o.channelKey;
    auto.current = initAuto(loadChannelLevel(store(), o.channelKey), Date.now());
    hold.current = null;
    holdingRef.current = false;
    expectPause.current = false;
    pendingStall.current = false;
    pendingPick.current = false;
  }

  const profile = (): Profile => effectiveProfile(modeRef.current, auto.current);
  const profileRef = useRef<Profile>("normal");
  profileRef.current = profile();

  const release = useCallback((why: string, resume: boolean) => {
    const h = hold.current;
    if (!h) return;
    hold.current = null;
    holdingRef.current = false;
    expectPause.current = false;
    setHolding(false);
    if (why === "no new media" || why === "max hold") lastBadHold.current = Date.now();
    const v = optsRef.current.videoRef.current;
    if (resume && v && v.paused) void v.play().catch(() => { /* the <video> onPause handler shows the paused UI */ });
    debugEvent("iptv-player", "live buffer hold end", { why, phase: h.phase, heldMs: Date.now() - h.at, profile: profile(), player: optsRef.current.engineRef.current }, "info");
  }, []);

  const setMode = useCallback((m: Mode) => {
    setModeState(m);
    modeRef.current = m;
    pendingPick.current = true;
    lastToast.current = Date.now();
    setToast(m === "auto" ? "Live buffer: Auto" : `Live buffer: ${m[0].toUpperCase()}${m.slice(1)}`);
    debugEvent("iptv-player", "live buffer mode", { mode: m }, "info");
  }, []);

  /** The <video> `pause` event. True = it was our own hold pause (swallow it). A pause during a hold is the user's: the hold is cancelled, never auto-resumed. */
  const handlePause = useCallback((): boolean => {
    if (expectPause.current) { expectPause.current = false; return true; }
    if (hold.current) release("user paused", false);
    return false;
  }, [release]);

  /** The user hit pause/play while a hold is active: cancel it without resuming. True if there was one. */
  const cancelHold = useCallback((): boolean => {
    if (!hold.current) return false;
    release("user paused", false);
    return true;
  }, [release]);

  /** The <video> fired `waiting` after the first frame (the caller already de-duplicates per rebuffer). */
  const noteRebuffer = useCallback(() => {
    const v = optsRef.current.videoRef.current;
    if (!optsRef.current.active || !v || v.seeking || document.hidden || holdingRef.current) return;
    const now = Date.now();
    const r = onStall(auto.current, now);
    if (!r.counted) return;
    const before = profile();
    auto.current = r.state;
    stallsThisSession.current++;
    debugEvent("iptv-player", "live buffer stall", { stalls: stallsThisSession.current, profile: profile(), mode: modeRef.current, player: optsRef.current.engineRef.current, stepped: r.stepped }, "info");
    if (modeRef.current === "auto") {
      if (r.stepped) {
        saveChannelLevel(store(), keyRef.current, auto.current.level);
        debugEvent("iptv-player", "live buffer step", { from: before, to: auto.current.level, why: "stall" }, "info");
        if (toastDue(lastToast.current, now)) { lastToast.current = now; setToast("Buffering more to avoid freezes"); }
      }
    }
    pendingStall.current = true;
    rerender((n) => n + 1);
  }, []);

  // Auto step-down, hls apply and mpegts chase / hold
  useEffect(() => {
    if (!o.active) { if (hold.current) release("inactive", false); return; }
    const t = window.setInterval(() => {
      const { videoRef, hlsRef, engineRef, linkRef, kindIsLive, touch } = optsRef.current;
      const v = videoRef.current;
      const now = Date.now();
      if (modeRef.current === "auto") {
        const d = onTick(auto.current, now);
        if (d.stepped) {
          const was = auto.current.level;
          auto.current = d.state;
          saveChannelLevel(store(), keyRef.current, d.state.level);
          debugEvent("iptv-player", "live buffer step", { from: was, to: d.state.level, why: "quiet 10 min" }, "info");
          rerender((n) => n + 1);
        }
      }
      const p = profile();
      profileRef.current = p;
      if (!v) return;
      const engine = engineRef.current;

      if (engine === "hls") {
        const h = hlsRef.current;
        if (!h || !kindIsLive()) return;
        const ap = appliedHls.current;
        const det = h.latestLevelDetails;
        const td = det?.targetduration ?? 0;
        const cfg = hlsRuntime(p, det?.fragments?.length ?? 0, td);
        const key = `${p}:${cfg.liveSyncDurationCount}:${cfg.liveMaxLatencyDuration ?? ""}`;
        if (ap.hls === h && ap.key === key) return;
        Object.assign(h.config, cfg);
        // Profile moved further from the edge: step back inside the window we already hold (no reload; same playlist).
        // Skipped when the window has less than one target duration to go back into.
        const sync = h.liveSyncPosition;
        const s = v.seekable;
        if (ap.hls === h && ap.p !== p && sync != null && s.length && v.currentTime > sync + 2 && sync - s.start(0) >= (td || 1)) {
          debugEvent("iptv-player", "live buffer seek", { jumpSec: Math.round((v.currentTime - sync) * 10) / 10, profile: p }, "info");
          v.currentTime = sync;
        }
        appliedHls.current = { hls: h, p, key };
        return;
      }
      if (engine !== "mpegts" || !kindIsLive()) { pendingPick.current = false; return; }

      const b = v.buffered;
      const end = b.length ? b.end(b.length - 1) : 0;
      const runway = Math.max(0, end - v.currentTime);

      const hd = hold.current;
      if (hd) {
        if (end > hd.lastEnd + 0.05) { hd.lastEnd = end; hd.lastGrowth = now; }
        if (!v.paused) return release("resumed elsewhere", false); // the user pressed play
        if (runway >= hd.target) return release("runway built", true);
        if (now - hd.lastGrowth > HOLD_NO_GROWTH_MS) return release("no new media", true);
        if (now - hd.at > HOLD_MAX_MS) return release("max hold", true);
        if (document.hidden) return release("hidden", true);
        touch();
        return;
      }

      if (v.paused || v.seeking || !b.length) return;
      const lk = linkRef.current;
      let phase: "start" | "stall" | null = null;
      const picked = pendingPick.current;
      pendingPick.current = false;
      if (pendingStall.current || picked) phase = "stall";
      else if (lk && startDoneFor.current !== lk && v.currentTime > 0) phase = "start";
      if (phase) {
        pendingStall.current = false;
        if (phase === "start") startDoneFor.current = lk;
        const target = picked || holdAllowed(lastBadHold.current, now) ? holdTarget(p, phase, runway) : 0;
        if (target > 0) {
          hold.current = { phase, target, at: now, lastEnd: end, lastGrowth: now };
          holdingRef.current = true;
          expectPause.current = true;
          setHolding(true);
          v.pause();
          touch();
          debugEvent("iptv-player", "live buffer hold start", { phase, target, runway: Math.round(runway * 10) / 10, profile: p }, "info");
          return;
        }
      }
      const to = chaseTarget(p, end, v.currentTime);
      if (to != null && to > v.currentTime && b.start(b.length - 1) <= to) v.currentTime = to;
    }, 250);
    return () => { window.clearInterval(t); if (hold.current) release("teardown", false); };
  }, [o.active, release]);

  // A hold never survives the stream going away
  useEffect(() => () => { hold.current = null; holdingRef.current = false; }, []);

  // Toast self-clears
  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 4500);
    return () => window.clearTimeout(t);
  }, [toast]);

  return { mode, setMode, effective: profile(), profileRef, handlePause, cancelHold, noteRebuffer, toast, holding };
}
