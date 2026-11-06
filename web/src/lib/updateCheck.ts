// "A new version is available" logic: version.json parsing, the poll scheduler and the show/skip decision. Pure (timers,
// clock and visibility are injected) so it is unit-tested without a browser.
import { cleanSha } from "./buildInfo.ts";

export interface RemoteVersion {
  sha: string;
  builtAt: string;
  assetsVersion: string;
}

/** Tolerant parse of dist/version.json (object or JSON text). null = unusable, never throws. */
export function parseVersionJson(raw: unknown): RemoteVersion | null {
  let v: unknown = raw;
  if (typeof raw === "string") {
    try {
      v = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!v || typeof v !== "object" || Array.isArray(v)) return null;
  const o = v as Record<string, unknown>;
  const sha = typeof o.sha === "string" ? o.sha.trim() : "";
  if (!/^[0-9a-z]{4,40}$/i.test(sha)) return null; // "dev" and junk are not comparable builds
  return {
    sha,
    builtAt: typeof o.builtAt === "string" ? o.builtAt : "",
    assetsVersion: typeof o.assetsVersion === "string" ? o.assetsVersion : "",
  };
}

export type ToastDecision = "show" | "defer" | "none";

/** Routes where a reload would interrupt playback. */
export const isPlaybackRoute = (name: string): boolean => name === "play" || name === "iptvplay";

/**
 * show  = newer build exists and it is safe to interrupt;
 * defer = newer build exists but the player is open (toast appears once the person leaves it);
 * none  = same build, unknown build (dev), or this sha was dismissed.
 */
export function decideToast(opts: { running: string; latest: RemoteVersion | null; routeName: string; dismissedSha?: string | null }): ToastDecision {
  const { running, latest, routeName, dismissedSha } = opts;
  if (!latest || cleanSha(running) === "dev") return "none";
  if (latest.sha === running || latest.sha === dismissedSha) return "none";
  return isPlaybackRoute(routeName) ? "defer" : "show";
}

export interface CheckerDeps {
  /** Resolves when the check completed (even if nothing changed); rejects/throws on network or parse failure. */
  check: () => Promise<void>;
  now: () => number;
  isVisible: () => boolean;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (h: unknown) => void;
  intervalMs?: number; // default 10 min
  minGapMs?: number; // default 2 min: never check more often than this
  maxBackoffMs?: number; // default 60 min
}

export interface Checker {
  start(): void;
  stop(): void;
  /** Call on document visibilitychange. */
  onVisibility(): void;
}

export function createUpdateChecker(d: CheckerDeps): Checker {
  const interval = d.intervalMs ?? 10 * 60_000;
  const minGap = d.minGapMs ?? 2 * 60_000;
  const maxBackoff = d.maxBackoffMs ?? 60 * 60_000;
  let timer: unknown = null;
  let last = -Infinity;
  let fails = 0;
  let running = false;
  let active = false;

  const clear = () => {
    if (timer != null) d.clearTimer(timer);
    timer = null;
  };
  const delay = () => Math.max(minGap, Math.min(maxBackoff, interval * 2 ** fails));
  const schedule = (ms: number) => {
    clear();
    if (!active) return;
    timer = d.setTimer(tick, Math.max(ms, 0));
  };

  async function tick() {
    timer = null;
    if (!active) return;
    if (!d.isVisible()) return; // paused; onVisibility resumes
    const wait = last + minGap - d.now();
    if (wait > 0) return schedule(wait); // hard floor
    if (running) return;
    running = true;
    last = d.now();
    try {
      await d.check();
      fails = 0;
    } catch {
      fails++;
    } finally {
      running = false;
    }
    schedule(delay());
  }

  return {
    start() {
      active = true;
      schedule(d.isVisible() ? 0 : delay());
    },
    stop() {
      active = false;
      clear();
    },
    onVisibility() {
      if (!active || !d.isVisible()) return;
      // Back in view: check now unless the floor says otherwise (tick re-schedules itself for the remainder)
      schedule(Math.max(0, last + minGap - d.now()));
    },
  };
}
