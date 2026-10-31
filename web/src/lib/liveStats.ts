// In-event "Live" panel (plan: ~/ai/plans/jellytip-live-sport.md "In-event panel"). GET /v1/sport/match/{id}/live. Every block is
// optional: a source that failed is simply missing. Pure; tested in web/test/iptv.test.ts.
import { parseStartsAt } from "./preGame.ts";

export interface LiveStats {
  status: string | null;
  clock: string | null;
  period: string | null;
  score: { home: number; away: number } | null;
  stats: { label: string; home: string; away: string }[];
  leaders: { team: string | null; name: string; line: string }[];
  plays: { clock: string | null; period: string | null; text: string; scoring: boolean }[];
  winProb: number | null;
  market: { source: string; question: string | null; home: number; away: number; draw: number | null; volume: number | null; updatedAt: number | null } | null;
  chat: { source: string; thread: string | null; comments: { author: string; text: string; score: number | null; at: number | null }[] } | null;
  updatedAt: number | null;
  pending: boolean;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : typeof v === "number" ? String(v) : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const prob = (v: unknown) => {
  const n = num(v);
  return n != null && n >= 0 && n <= 1 ? n : null;
};

export function parseLive(raw: unknown): LiveStats | null {
  const r = obj(raw);
  if (!r) return null;
  const sc = obj(r.score);
  const m = obj(r.market);
  const mh = prob(m?.home);
  const ma = prob(m?.away);
  const ch = obj(r.chat);
  const wp = obj(r.winProb);
  return {
    status: str(r.status),
    clock: str(r.clock),
    period: str(r.period),
    score: sc && num(sc.home) != null && num(sc.away) != null ? { home: num(sc.home)!, away: num(sc.away)! } : null,
    stats: arr(r.stats).flatMap((x) => {
      const o = obj(x);
      const label = str(o?.label);
      return o && label ? [{ label, home: str(o.home) ?? "–", away: str(o.away) ?? "–" }] : [];
    }),
    leaders: arr(r.leaders).flatMap((x) => {
      const o = obj(x);
      const name = str(o?.name);
      return o && name ? [{ team: str(o.team), name, line: str(o.line) ?? "" }] : [];
    }),
    plays: arr(r.plays)
      .flatMap((x) => {
        const o = obj(x);
        const text = str(o?.text);
        return o && text ? [{ clock: str(o.clock), period: str(o.period), text, scoring: o.scoring === true }] : [];
      })
      .slice(0, 15),
    winProb: prob(wp ? wp.home : r.winProb),
    market: m && mh != null && ma != null ? { source: str(m.source) ?? "market", question: str(m.question), home: mh, away: ma, draw: prob(m.draw), volume: num(m.volume), updatedAt: parseStartsAt(m.updatedAt) } : null,
    chat: ch
      ? {
          source: str(ch.source) ?? "chat",
          thread: str(ch.thread),
          comments: arr(ch.comments)
            .flatMap((x) => {
              const o = obj(x);
              const text = str(o?.text);
              return o && text ? [{ author: str(o.author) ?? "someone", text, score: num(o.score), at: parseStartsAt(o.at) }] : [];
            })
            .slice(0, 25),
        }
      : null,
    updatedAt: parseStartsAt(r.updatedAt),
    pending: r.pending === true,
  };
}

/** 0.583 → "58%". */
export const pct = (p: number) => `${Math.round(p * 100)}%`;

/** 1_234_567 → "$1.2M traded"; 48_000 → "$48k traded". */
export function volumeText(v: number | null): string | null {
  if (v == null || v <= 0) return null;
  const s = v >= 1e6 ? `${(v / 1e6).toFixed(1).replace(/\.0$/, "")}M` : v >= 1e3 ? `${Math.round(v / 1e3)}k` : String(Math.round(v));
  return `$${s} traded`;
}

/** "30 s ago" / "4 min ago". */
export function agoText(at: number | null, now: number): string | null {
  if (!at) return null;
  const s = Math.max(0, now - at);
  return s < 90 ? `${Math.round(s)} s ago` : `${Math.round(s / 60)} min ago`;
}

const BAD = /\b(fuck\w*|shit\w*|cunt\w*|bitch\w*|asshole\w*|bastard\w*|dick\w*|piss\w*)\b/gi;
/** A basic profanity mask for chat: "f***". */
export const maskText = (t: string) => t.replace(BAD, (w) => `${w[0]}${"*".repeat(w.length - 1)}`);

/** Whether the panel may show live detail for what is playing: live, or a recording / replay only when spoilers are on. */
export const mayShow = (liveNow: boolean, spoilers: boolean) => liveNow || spoilers;
