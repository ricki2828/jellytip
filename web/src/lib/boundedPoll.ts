// Bounded polling for answers that arrive "partial, ask again" (pure; tested in web/test/boundedPoll.test.ts).
// Stops at maxAttempts or maxMs, backs off between asks, pauses while the page is hidden, and cancel() makes every
// late answer a no-op, so nothing from a previous person / user can land on the next screen.

export type PollState = "checking" | "done" | "timeout" | "unavailable" | "error";

export interface PollEnv {
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
  now(): number;
  hidden(): boolean;
  /** Calls cb when the page becomes visible again; returns an unsubscribe. */
  onVisible(cb: () => void): () => void;
}

export type Outcome<T> = { kind: "ok"; value: T; pending: boolean } | { kind: "missing" } | { kind: "error" };

export interface PollOpts<T> {
  fetch(signal: AbortSignal): Promise<Outcome<T>>;
  onValue(v: T): void;
  onState(s: PollState): void;
  maxAttempts?: number;
  maxMs?: number;
  delaysMs?: number[]; // wait before attempt 2, 3, ... (the last repeats)
  env: PollEnv;
}

export function startBoundedPoll<T>(o: PollOpts<T>): { cancel(): void; retry(): void } {
  const maxAttempts = o.maxAttempts ?? 4;
  const maxMs = o.maxMs ?? 30_000;
  const delays = o.delaysMs?.length ? o.delaysMs : [2000, 5000, 10_000];
  const env = o.env;
  let dead = false;
  let attempts = 0;
  let began = env.now();
  let timer: unknown = null;
  let ctl: AbortController | null = null;
  let unsub: (() => void) | null = null;
  let waiting = false; // a retry is due but the page is hidden

  const finish = (s: PollState) => {
    unsub?.();
    unsub = null;
    waiting = false;
    o.onState(s);
  };

  const schedule = () => {
    if (attempts >= maxAttempts || env.now() - began >= maxMs) return finish("timeout");
    const wait = delays[Math.min(attempts - 1, delays.length - 1)];
    if (env.now() - began + wait > maxMs) return finish("timeout");
    timer = env.setTimer(() => {
      timer = null;
      if (dead) return;
      if (env.hidden()) {
        waiting = true;
        return;
      }
      void attempt();
    }, wait);
  };

  const attempt = async () => {
    attempts += 1;
    ctl = new AbortController();
    const mine = ctl;
    let r: Outcome<T>;
    try {
      r = await o.fetch(mine.signal);
    } catch {
      r = { kind: "error" };
    }
    if (dead || mine.signal.aborted) return;
    if (r.kind === "missing") return finish("unavailable");
    if (r.kind === "error") return attempts >= maxAttempts ? finish("error") : (o.onState("checking"), schedule());
    o.onValue(r.value);
    if (!r.pending) return finish("done");
    o.onState("checking");
    schedule();
  };

  const begin = () => {
    dead = false;
    attempts = 0;
    began = env.now();
    o.onState("checking");
    unsub?.();
    unsub = env.onVisible(() => {
      if (waiting && !dead) {
        waiting = false;
        void attempt();
      }
    });
    void attempt();
  };

  begin();
  return {
    cancel() {
      dead = true;
      ctl?.abort();
      if (timer !== null) env.clearTimer(timer);
      timer = null;
      unsub?.();
      unsub = null;
    },
    retry() {
      this.cancel();
      begin();
    },
  };
}

export const browserEnv: PollEnv = {
  setTimer: (fn, ms) => window.setTimeout(fn, ms),
  clearTimer: (t) => window.clearTimeout(t as number),
  now: () => Date.now(),
  hidden: () => document.visibilityState === "hidden",
  onVisible: (cb) => {
    const h = () => document.visibilityState === "visible" && cb();
    document.addEventListener("visibilitychange", h);
    return () => document.removeEventListener("visibilitychange", h);
  },
};
