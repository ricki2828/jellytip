// In-event "Live" panel (plan: ~/ai/plans/jellytip-live-sport.md "In-event panel"). GET /v1/sport/match/{id}/live. Every block is
// optional: a source that failed is simply missing. Pure; tested in web/test/iptv.test.ts.
import { parseStartsAt } from "./preGame.ts";

/** The Polymarket favourite changed (server: hysteresis at 53%, at least $10k traded, 5 min cool-down; kept on the answer for 2 min). */
export interface Flip {
  at: number;
  from: "home" | "away" | null;
  to: "home" | "away";
  prob: number;
}

export interface Moment {
  at: number;
  kind: string;
  team: "home" | "away" | null;
  label: string;
}

export interface LiveStats {
  status: string | null;
  clock: string | null;
  period: string | null;
  score: { home: number; away: number } | null;
  stats: { label: string; home: string; away: string }[];
  leaders: { team: string | null; name: string; line: string; headshot: string | null }[];
  plays: { clock: string | null; period: string | null; text: string; scoring: boolean; team: "home" | "away" | null; delta: number | null }[];
  /** Sport layout key ("basketball", "nfl", "football", "rugby", "cricket") and its sport-specific `situation`. */
  layout: string | null;
  situation: Record<string, unknown> | null;
  /** Big events since the last poll: score change, red card, wicket, TD, lead change, clutch time. */
  moments: Moment[];
  winProb: number | null;
  market: { source: string; question: string | null; home: number; away: number; draw: number | null; volume: number | null; updatedAt: number | null; series: { t: number; p: number }[]; favourite: "home" | "away" | null; flip: Flip | null } | null;
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

function series(raw: unknown): { t: number; p: number }[] {
  return arr(raw).flatMap((x) => {
    const o = obj(x);
    const t = parseStartsAt(o?.t);
    const p = prob(o?.p);
    return o && t != null && p != null ? [{ t, p }] : [];
  });
}

const side = (v: unknown): "home" | "away" | null => (v === "home" || v === "away" ? v : null);

function parseFlip(raw: unknown): Flip | null {
  const o = obj(raw);
  const at = parseStartsAt(o?.at);
  const to = side(o?.to);
  const prob = prob_(o?.prob);
  return o && at != null && to && prob != null ? { at, from: side(o.from), to, prob } : null;
}
const prob_ = (v: unknown) => prob(v);

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
      return o && name ? [{ team: str(o.team), name, line: str(o.line) ?? "", headshot: str(o.headshot) }] : [];
    }),
    plays: arr(r.plays)
      .flatMap((x) => {
        const o = obj(x);
        const text = str(o?.text);
        return o && text ? [{ clock: str(o.clock), period: str(o.period), text, scoring: o.scoring === true, team: side(o.team), delta: num(o.delta) ?? num(o.points) }] : [];
      })
      .slice(0, 15),
    layout: str(r.layout)?.toLowerCase() ?? null,
    situation: obj(r.situation),
    moments: arr(r.moments).flatMap((x) => {
      const o = obj(x);
      const at = parseStartsAt(o?.at);
      const label = str(o?.label) ?? str(o?.kind);
      return o && at != null && label ? [{ at, kind: str(o.kind) ?? label, team: side(o.team), label } as Moment] : [];
    }),
    winProb: prob(wp ? wp.home : r.winProb),
    market: m && mh != null && ma != null ? { source: str(m.source) ?? "market", question: str(m.question), home: mh, away: ma, draw: prob(m.draw), volume: num(m.volume), updatedAt: parseStartsAt(m.updatedAt), series: series(m.series), favourite: side(m.favourite), flip: parseFlip(m.flip) } : null,
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

/** "📈 Polymarket flip: 76ers now favourites · 54%". */
export const flipText = (f: Flip, names: { home: string; away: string }) => `📈 Polymarket flip: ${names[f.to]} now favourites · ${pct(f.prob)}`;

/** Tabs for the latest answer: Chat only when it has a `chat` block (Reddit may be off), Fantasy only when there are leagues. */
export function liveTabs(hasChat: boolean, fantasy: boolean): [string, string][] {
  return [["stats", "Stats"], ["plays", "Plays"], ["odds", "Odds"], ...(hasChat ? ([["chat", "Chat"]] as [string, string][]) : []), ...(fantasy ? ([["fantasy", "Fantasy"]] as [string, string][]) : [])];
}

// ---------- redesigned panel: pure helpers (plan "Live panel: pop, context, performance") ----------

/** The first number in a stat cell ("47%", "12-20", "1,204") or null. */
export function statNumber(v: string | null | undefined): number | null {
  const m = /-?\d[\d,]*\.?\d*/.exec(v ?? "");
  if (!m) return null;
  const n = Number(m[0].replace(/,/g, ""));
  return isFinite(n) ? n : null;
}

/** Home's share (0..1) of a stat row for the split bar; null when either side isn't a number or both are zero. */
export function statShare(home: string | null | undefined, away: string | null | undefined): number | null {
  const h = statNumber(home);
  const a = statNumber(away);
  if (h == null || a == null || h < 0 || a < 0 || h + a === 0) return null;
  return h / (h + a);
}

/** SVG polyline points for a 0..1 series in a w x h box (higher probability = higher on screen); "" for fewer than two points. */
export function sparkPoints(series: { t: number; p: number }[], w: number, h: number): string {
  if (series.length < 2) return "";
  const t0 = series[0].t;
  const span = Math.max(1, series[series.length - 1].t - t0);
  return series.map((s) => `${Math.round(((s.t - t0) / span) * w * 10) / 10},${Math.round((1 - s.p) * h * 10) / 10}`).join(" ");
}

/** Moments newer than `seen` (epoch s of the latest one already announced), oldest first. */
export const newMoments = (moments: Moment[], seen: number): Moment[] => moments.filter((m) => m.at > seen).sort((a, b) => a.at - b.at);

export type Block =
  | { kind: "kv"; title: string | null; rows: [string, string][] }
  | { kind: "bar"; label: string; home: number; away: number; homeText: string; awayText: string }
  | { kind: "field"; pos: number; side: "home" | "away" | null; redZone: boolean; text: string | null }
  | { kind: "chips"; label: string; items: string[] }
  | { kind: "grid"; label: string; head: string[]; rows: { name: string; cells: string[] }[] };

const sit = (o: Record<string, unknown> | null, k: string) => o?.[k];
const text = (v: unknown): string | null => (typeof v === "string" && v ? v : typeof v === "number" && isFinite(v) ? String(v) : null);
const pair = (v: unknown): { home: string | null; away: string | null } | null => {
  const o = v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  return o ? { home: text(o.home), away: text(o.away) } : null;
};
const sideOf = (v: unknown): "home" | "away" | null => (v === "home" || v === "away" ? v : null);

/** The sport-specific part of the panel from `layout` + `situation`: tolerant of missing keys; nothing for an unknown layout. */
export function situationBlocks(layout: string | null, s: Record<string, unknown> | null, names: { home: string; away: string }): Block[] {
  if (!layout || !s) return [];
  const out: Block[] = [];
  const bar = (label: string, p: { home: string | null; away: string | null } | null) => {
    const sh = p ? statShare(p.home, p.away) : null;
    if (p && sh != null) out.push({ kind: "bar", label, home: sh, away: 1 - sh, homeText: p.home ?? "", awayText: p.away ?? "" });
  };
  switch (layout) {
    case "nfl": {
      const down = text(sit(s, "down"));
      const dist = text(sit(s, "distance")) ?? text(sit(s, "ytg"));
      const rows: [string, string][] = [];
      if (down) rows.push(["Down & distance", dist ? `${down} & ${dist}` : down]);
      const fp = sit(s, "fieldPos");
      if (text(sit(s, "ballOn"))) rows.push(["Ball on", text(sit(s, "ballOn"))!]);
      if (text(sit(s, "drive"))) rows.push(["Drive", text(sit(s, "drive"))!]);
      if (rows.length) out.push({ kind: "kv", title: null, rows });
      const pos = typeof fp === "number" ? fp : statNumber(text(fp));
      if (pos != null) out.push({ kind: "field", pos: Math.max(0, Math.min(100, pos)), side: sideOf(sit(s, "possession")), redZone: sit(s, "redZone") === true, text: text(sit(s, "ballOn")) });
      else if (sit(s, "redZone") === true) out.push({ kind: "kv", title: null, rows: [["Red zone", "Yes"]] });
      break;
    }
    case "basketball": {
      const qs = Array.isArray(sit(s, "quarters")) ? (sit(s, "quarters") as Record<string, unknown>[]) : [];
      if (qs.length)
        out.push({
          kind: "grid",
          label: "By quarter",
          head: qs.map((q, i) => text(q.period) ?? `Q${i + 1}`),
          rows: [
            { name: names.home, cells: qs.map((q) => text(q.home) ?? "–") },
            { name: names.away, cells: qs.map((q) => text(q.away) ?? "–") },
          ],
        });
      bar("Team fouls", pair(sit(s, "fouls")));
      if (text(sit(s, "run"))) out.push({ kind: "kv", title: null, rows: [["Run", text(sit(s, "run"))!]] });
      break;
    }
    case "football": {
      bar("Possession", pair(sit(s, "possession")));
      bar("Shots", pair(sit(s, "shots")));
      bar("xG", pair(sit(s, "xg")));
      const cards = Array.isArray(sit(s, "cards")) ? (sit(s, "cards") as unknown[]).map((c) => (typeof c === "string" ? c : text((c as Record<string, unknown>)?.label) ?? [text((c as Record<string, unknown>)?.minute), text((c as Record<string, unknown>)?.kind)].filter(Boolean).join(" "))).filter(Boolean) : [];
      if (cards.length) out.push({ kind: "chips", label: "Cards", items: cards });
      break;
    }
    case "rugby": {
      bar("Territory", pair(sit(s, "territory")));
      bar("Possession", pair(sit(s, "possession")));
      bar("Penalties", pair(sit(s, "penalties")));
      break;
    }
    case "cricket": {
      const bats = Array.isArray(sit(s, "batters")) ? (sit(s, "batters") as Record<string, unknown>[]) : [];
      if (bats.length) out.push({ kind: "kv", title: "At the crease", rows: bats.flatMap((b) => (text(b.name) ? [[`${text(b.name)}${b.onStrike === true ? " *" : ""}`, `${text(b.runs) ?? "0"} (${text(b.balls) ?? "0"})`] as [string, string]] : [])) });
      const bowler = sit(s, "bowler");
      const bt = typeof bowler === "string" ? bowler : text((bowler as Record<string, unknown> | null)?.name) ? `${text((bowler as Record<string, unknown>).name)} ${text((bowler as Record<string, unknown>).figures) ?? ""}`.trim() : null;
      const kv: [string, string][] = [];
      if (bt) kv.push(["Bowling", bt]);
      if (text(sit(s, "rr"))) kv.push(["Run rate", text(sit(s, "rr"))!]);
      if (text(sit(s, "rrr"))) kv.push(["Required", text(sit(s, "rrr"))!]);
      if (kv.length) out.push({ kind: "kv", title: null, rows: kv });
      const lb = Array.isArray(sit(s, "lastBalls")) ? (sit(s, "lastBalls") as unknown[]).map(text).filter((x): x is string => !!x).slice(-6) : [];
      if (lb.length) out.push({ kind: "chips", label: "Last 6 balls", items: lb });
      break;
    }
    default:
      break;
  }
  return out;
}
