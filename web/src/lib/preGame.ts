// Pre-game state (pure; tested in web/test/iptv.test.ts). Contract: ~/ai/plans/jellytip-live-sport.md "Pre-game state".
// The server sends `phase` and `startsAt` (ISO UTC); countdowns are worked out here from the clock.

export type Phase = "pre" | "live" | "post";

const PHASES: Phase[] = ["pre", "live", "post"];
export const parsePhase = (v: unknown): Phase | null => (PHASES.includes(v as Phase) ? (v as Phase) : null);

/** ISO UTC (or epoch seconds) → epoch seconds; null when it isn't a time. */
export function parseStartsAt(v: unknown): number | null {
  if (typeof v === "number" && isFinite(v) && v > 0) return v > 1e11 ? Math.floor(v / 1000) : v;
  if (typeof v !== "string" || !v) return null;
  const t = Date.parse(v);
  return isFinite(t) ? Math.floor(t / 1000) : null;
}

/** Before the real start: the server's phase says so, and the clock agrees (a stale "pre" turns live by itself at startsAt). */
export const isPre = (startsAt: number | null | undefined, now: number, phase?: Phase | null): boolean =>
  startsAt != null && now < startsAt && phase !== "live" && phase !== "post";

const hm = (s: number) => new Date(s * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

/** "in 35 min" / "in 2h 10m" / "any moment". */
export function inText(startsAt: number, now: number): string {
  const m = Math.ceil((startsAt - now) / 60);
  if (m <= 0) return "any moment";
  return m >= 60 ? `in ${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `in ${m} min`;
}

/** "Starts 18:00 · in 35 min". */
export const startsLine = (startsAt: number, now: number) => `Starts ${hm(startsAt)} · ${inText(startsAt, now)}`;

/** "Pre-game — the event starts at 18:00 (in 35 min)". */
export const preGameCard = (startsAt: number, now: number) => `Pre-game — the event starts at ${hm(startsAt)} (${inText(startsAt, now)})`;
