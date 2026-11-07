// Spoiler delay for the live companion UI. The player mounts `useRevealClock` once and hands `reveal` to everything that shows
// live information (LiveDrawer, FlipAlerts, FantasyAlerts, FantasyChip). Maths and policy live in delayMath.ts (pure, tested).
import { useCallback, useEffect, useRef, useState } from "react";
import { getChannelDelay, putChannelDelay, usePrefs } from "../api/me";
import { calibratedDelay, clampDelay, noteEvent, DEFAULT_DELAY_SEC, isRevealed, lagSec, liveEdge, parseChannelKey, replayState } from "./delayMath.ts";

export * from "./delayMath.ts";

export interface RevealClock {
  /** Whether an event at `atSec` (epoch seconds) may be shown now. No argument: is live reveal on at all (false in replay). Asking never moves the calibration anchor. */
  reveal: (atSec?: number) => boolean;
  /** A key moment (goal, try, wicket...) at `atSec` exists: the only thing "that just happened" calibrates against. */
  noteMoment: (atSec: number) => void;
  delaySec: number;
  replay: boolean;
  /** "That just happened": sets the delay so the latest event this clock was asked about is revealed right now. */
  calibrate: () => void;
}

export interface RevealOpts {
  video: HTMLVideoElement | null;
  /** "provider:stream" of the channel being watched; null = no per-channel memory. */
  channelKey: string | null;
  isLive: boolean;
  /** Catch-up, start-over or a recording (the caller knows): nothing is revealed. */
  replay?: boolean;
}

const LOCAL = "jellytip.channelDelay.";
const readLocal = (k: string): number | null => {
  try {
    const v = localStorage.getItem(LOCAL + k);
    const n = v == null ? NaN : Number(v);
    return isFinite(n) ? clampDelay(n) : null;
  } catch {
    return null;
  }
};
const writeLocal = (k: string, sec: number) => {
  try {
    localStorage.setItem(LOCAL + k, String(sec));
  } catch {
    /* private mode: the delay still applies for this session */
  }
};

export function useRevealClock(opts: RevealOpts): RevealClock {
  const { video, channelKey, isLive } = opts;
  const { prefs } = usePrefs({ defer: true });
  const prefDelay = prefs ? prefs.sport.delaySec : DEFAULT_DELAY_SEC;
  const [channelDelay, setChannelDelay] = useState<number | null>(null);
  const delaySec = clampDelay(channelDelay ?? prefDelay);
  const [replay, setReplay] = useState(false);

  // Everything the callbacks read lives in refs, so `reveal` keeps one identity and always sees the live playhead.
  const ref = useRef({ video, delaySec, forced: !isLive || !!opts.replay, replay: false, latest: null as number | null });
  ref.current.video = video;
  ref.current.delaySec = delaySec;
  ref.current.forced = !isLive || !!opts.replay;

  // The channel's saved delay: this browser's copy at once, then the server's (which wins)
  useEffect(() => {
    setChannelDelay(channelKey ? readLocal(channelKey) : null);
    ref.current.latest = null;
    if (!channelKey) return;
    let alive = true;
    const { pid, sid } = parseChannelKey(channelKey);
    void getChannelDelay(pid, sid).then((s) => {
      if (alive && s != null) {
        setChannelDelay(clampDelay(s));
        writeLocal(channelKey, clampDelay(s));
      }
    });
    return () => {
      alive = false;
    };
  }, [channelKey]);

  const lag = () => {
    const v = ref.current.video;
    return v ? lagSec(v.currentTime, liveEdge(v)) : 0;
  };
  const inReplay = () => {
    ref.current.replay = replayState(ref.current.replay, lag(), ref.current.forced);
    return ref.current.replay;
  };

  // Keep the exposed `replay` flag current (the playhead moves without React knowing)
  useEffect(() => {
    const t = window.setInterval(() => {
      const r = inReplay();
      setReplay((p) => (p === r ? p : r));
    }, 1000);
    setReplay(inReplay());
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [video, isLive, opts.replay]);

  const reveal = useCallback((atSec?: number) => {
    const rp = inReplay();
    return isRevealed(atSec, Date.now() / 1000, ref.current.delaySec, lag(), rp);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const noteMoment = useCallback((atSec: number) => {
    ref.current.latest = noteEvent(ref.current.latest, atSec, Date.now() / 1000);
  }, []);

  const calibrate = useCallback(() => {
    if (inReplay()) return;
    const d = calibratedDelay(ref.current.latest, Date.now() / 1000, lag());
    if (d == null) return;
    setChannelDelay(d);
    if (!channelKey) return;
    writeLocal(channelKey, d);
    const { pid, sid } = parseChannelKey(channelKey);
    void putChannelDelay(pid, sid, d); // a server without the route keeps the browser copy
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channelKey]);

  return { reveal, noteMoment, delaySec, replay, calibrate };
}
