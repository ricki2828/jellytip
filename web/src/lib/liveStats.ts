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

export interface Game {
  matchId: string;
  home: { name: string; short: string | null; logo: string | null };
  away: { name: string; short: string | null; logo: string | null };
  score: { home: number; away: number } | null;
  clock: string | null;
  period: string | null;
  /** "2nd & 7 · KC 34" when the game has a situation. */
  situation: string | null;
  redZone: boolean;
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
  plays: { clock: string | null; period: string | null; text: string; scoring: boolean; team: "home" | "away" | null; delta: number | null; id: string | null }[];
  /** Sport layout key ("basketball", "nfl", "football", "rugby", "cricket") and its sport-specific `situation`. */
  layout: string | null;
  situation: Record<string, unknown> | null;
  /** Multi (RedZone): every live game of the league, red-zone games first. */
  games: Game[];
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
        return o && text ? [{ clock: str(o.clock), period: str(o.period), text, scoring: o.scoring === true, team: side(o.team), delta: num(o.delta) ?? num(o.points), id: str(o.id) }] : [];
      })
      .slice(0, 15),
    games: arr(r.games).flatMap((x): Game[] => {
      const o = obj(x);
      const sd = (v: unknown) => {
        const so = obj(v);
        const name = typeof v === "string" ? v : str(so?.name) ?? str(so?.short);
        return name ? { name, short: str(so?.short), logo: str(so?.logo) } : null;
      };
      const home = sd(o?.home);
      const away = sd(o?.away);
      const sc = obj(o?.score);
      if (!o || !home || !away) return [];
      const sit = obj(o.situation);
      const dd = str(sit?.down) ? `${str(sit?.down)}${str(sit?.distance) ? ` & ${str(sit?.distance)}` : ""}` : null;
      return [{ matchId: str(o.matchId) ?? "", home, away, score: sc && num(sc.home) != null && num(sc.away) != null ? { home: num(sc.home)!, away: num(sc.away)! } : null, clock: str(o.clock), period: str(o.period), situation: [dd, str(sit?.yardLine) ?? str(sit?.ballOn)].filter(Boolean).join(" · ") || null, redZone: o.redZone === true || sit?.redZone === true }];
    }),
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
  | { kind: "grid"; label: string; head: string[]; rows: { name: string; cells: string[] }[] }
  | { kind: "banner"; lap: string | null; session: string | null; flag: Flag | null; fastest: string | null }
  | { kind: "tower"; rows: TowerRow[] }
  | { kind: "sets"; head: string[]; rows: { name: string; serving: boolean; cells: string[]; game: string }[] }
  | { kind: "leaderboard"; title: string; rows: LeaderRow[]; pinned: LeaderRow[] };

export type Flag = "green" | "yellow" | "sc" | "vsc" | "red" | "chequered";
export const FLAG_LABEL: Record<Flag, string> = { green: "GREEN", yellow: "YELLOW", sc: "SAFETY CAR", vsc: "VIRTUAL SC", red: "RED FLAG", chequered: "CHEQUERED" };

export interface TowerRow {
  pos: string;
  driver: string;
  team: string | null;
  color: string | null;
  gap: string | null;
  tyre: string | null;
  tyreAge: string | null;
  pits: string | null;
  fastest: boolean;
}

export interface LeaderRow {
  pos: string;
  player: string;
  country: string | null;
  toPar: string;
  thru: string | null;
  today: string | null;
}

const FLAGS: Flag[] = ["green", "yellow", "sc", "vsc", "red", "chequered"];
/** S / M / H / I / W from "soft", "MEDIUM", "h"…; null for anything else. */
export const tyreLetter = (v: unknown): string | null => {
  const c = typeof v === "string" ? v.trim().charAt(0).toUpperCase() : "";
  return "SMHIW".includes(c) && c ? c : null;
};
const towerGap = (r: Record<string, unknown>) => text(r.gap) ?? text(r.interval);

const sit = (o: Record<string, unknown> | null, k: string) => o?.[k];
const text = (v: unknown): string | null => (typeof v === "string" && v ? v : typeof v === "number" && isFinite(v) ? String(v) : null);
const pair = (v: unknown): { home: string | null; away: string | null } | null => {
  const o = v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  return o ? { home: text(o.home), away: text(o.away) } : null;
};
const sideOf = (v: unknown): "home" | "away" | null => (v === "home" || v === "away" ? v : null);

/** The sport-specific part of the panel from `layout` + `situation` (pinned shape: plan "/live schema, PINNED 31-Oct"): tolerant of missing
 * keys; any other {home, away} key (fouls, possession, territory, cards…) becomes a split bar; nothing for an unknown layout. */
export function situationBlocks(layout: string | null, s: Record<string, unknown> | null, names: { home: string; away: string }): Block[] {
  if (!layout || !s) return [];
  const out: Block[] = [];
  const used = new Set<string>();
  const bar = (label: string, p: { home: string | null; away: string | null } | null) => {
    const sh = p ? statShare(p.home, p.away) : null;
    if (p && sh != null) out.push({ kind: "bar", label, home: sh, away: 1 - sh, homeText: p.home ?? "", awayText: p.away ?? "" });
  };
  const take = (...keys: string[]) => {
    keys.forEach((k) => used.add(k));
    return keys.map((k) => sit(s, k));
  };
  switch (layout) {
    case "nfl": {
      const [down, distance, yardLine, fieldPosRaw, possessionRaw, redZone, drive] = take("down", "distance", "yardLine", "fieldPos", "possession", "redZone", "drive");
      take("ballOn", "ytg");
      const dist = text(distance) ?? text(sit(s, "ytg"));
      const ball = text(yardLine) ?? text(sit(s, "ballOn"));
      const driveText = typeof drive === "string" ? drive : text((drive as Record<string, unknown> | null)?.summary);
      const rows: [string, string][] = [];
      if (text(down)) rows.push(["Down & distance", dist ? `${text(down)} & ${dist}` : text(down)!]);
      if (ball) rows.push(["Ball on", ball]);
      if (driveText) rows.push(["Drive", driveText]);
      if (rows.length) out.push({ kind: "kv", title: null, rows });
      // fieldPos is 0-100 from the home end zone
      const pos = typeof fieldPosRaw === "number" ? fieldPosRaw : statNumber(text(fieldPosRaw));
      if (pos != null) out.push({ kind: "field", pos: Math.max(0, Math.min(100, pos)), side: sideOf(possessionRaw), redZone: redZone === true, text: ball });
      else if (redZone === true) out.push({ kind: "kv", title: null, rows: [["Red zone", "Yes"]] });
      break;
    }
    case "basketball": {
      const [periodsRaw, quartersRaw, runRaw] = take("periods", "quarters", "run");
      const qs = Array.isArray(periodsRaw) ? (periodsRaw as Record<string, unknown>[]) : Array.isArray(quartersRaw) ? (quartersRaw as Record<string, unknown>[]) : [];
      if (qs.length)
        out.push({
          kind: "grid",
          label: "By quarter",
          head: qs.map((q, i) => text(q.period) ?? (i < 4 ? `Q${i + 1}` : `OT${i - 3}`)),
          rows: [
            { name: names.home, cells: qs.map((q) => text(q.home) ?? "–") },
            { name: names.away, cells: qs.map((q) => text(q.away) ?? "–") },
          ],
        });
      const run = typeof runRaw === "string" ? runRaw : text((runRaw as Record<string, unknown> | null)?.text);
      if (run) out.push({ kind: "kv", title: null, rows: [["Run", run]] });
      break;
    }
    case "cricket": {
      const [battersRaw, bowlerRaw, rr, rrr, lastBallsRaw, equation] = take("batters", "bowler", "rr", "rrr", "lastBalls", "equation");
      const bats = Array.isArray(battersRaw) ? (battersRaw as Record<string, unknown>[]) : [];
      if (bats.length) out.push({ kind: "kv", title: "At the crease", rows: bats.flatMap((b) => (text(b.name) ? [[`${text(b.name)}${b.onStrike === true ? " *" : ""}`, `${text(b.runs) ?? "0"} (${text(b.balls) ?? "0"})`] as [string, string]] : [])) });
      const bo = bowlerRaw && typeof bowlerRaw === "object" ? (bowlerRaw as Record<string, unknown>) : null;
      const bt = typeof bowlerRaw === "string" ? bowlerRaw : text(bo?.name) ? [text(bo!.name), text(bo!.figures), text(bo!.overs) ? `(${text(bo!.overs)} ov)` : null].filter(Boolean).join(" ") : null;
      const kv: [string, string][] = [];
      if (bt) kv.push(["Bowling", bt]);
      if (text(rr)) kv.push(["Run rate", text(rr)!]);
      if (text(rrr)) kv.push(["Required", text(rrr)!]);
      if (text(equation)) kv.push(["Target", text(equation)!]);
      if (kv.length) out.push({ kind: "kv", title: null, rows: kv });
      const lb = Array.isArray(lastBallsRaw) ? (lastBallsRaw as unknown[]).map(text).filter((x): x is string => !!x).slice(-6) : [];
      if (lb.length) out.push({ kind: "chips", label: "Last 6 balls", items: lb });
      break;
    }
    case "f1": {
      const [lap, laps, session, flagRaw, standings, fastestLap] = take("lap", "laps", "session", "flag", "standings", "fastestLap");
      const flag = FLAGS.includes(flagRaw as Flag) ? (flagRaw as Flag) : null;
      const lapText = text(lap) ? (text(laps) ? `LAP ${text(lap)}/${text(laps)}` : `LAP ${text(lap)}`) : null;
      const fl = fastestLap && typeof fastestLap === "object" ? (fastestLap as Record<string, unknown>) : null;
      const fastest = typeof fastestLap === "string" ? fastestLap : fl ? [text(fl.driver), text(fl.time)].filter(Boolean).join(" ") || null : null;
      if (lapText || text(session) || flag) out.push({ kind: "banner", lap: lapText, session: text(session), flag, fastest });
      const rows = Array.isArray(standings)
        ? (standings as Record<string, unknown>[]).flatMap((r): TowerRow[] => {
            const driver = text(r.driver);
            return driver
              ? [{ pos: text(r.pos) ?? "", driver, team: text(r.team), color: typeof r.teamColor === "string" && /^#?[0-9a-f]{6}$/i.test(r.teamColor) ? `#${r.teamColor.replace("#", "").toLowerCase()}` : null, gap: towerGap(r), tyre: tyreLetter(r.tyre), tyreAge: text(r.tyreAge), pits: text(r.pits), fastest: r.fastest === true }]
              : [];
          })
        : [];
      if (rows.length) out.push({ kind: "tower", rows: rows.slice(0, 20) });
      break;
    }
    case "tennis": {
      const [setsRaw, gameRaw, serving, stats] = take("sets", "game", "serving", "stats");
      const sets = Array.isArray(setsRaw) ? (setsRaw as Record<string, unknown>[]) : [];
      const g = pair(gameRaw);
      if (sets.length || g) {
        const cell = (v: unknown, tb: unknown) => `${text(v) ?? "–"}${text(tb) ? `\u207d${text(tb)}\u207e` : ""}`;
        out.push({
          kind: "sets",
          head: [...sets.map((_, i) => `S${i + 1}`), ...(g ? ["GAME"] : [])],
          rows: (["home", "away"] as const).map((sd) => ({ name: names[sd], serving: serving === sd, cells: sets.map((x) => cell(x[sd], x[sd === "home" ? "tbHome" : "tbAway"] ?? (x.tb && typeof x.tb === "object" ? (x.tb as Record<string, unknown>)[sd] : null))), game: g?.[sd] ?? "" })),
        });
      }
      const st = stats && typeof stats === "object" ? (stats as Record<string, unknown>) : null;
      if (st) for (const [k, v] of Object.entries(st)) bar(k.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase()), pair(v));
      break;
    }
    case "golf": {
      const [round, lb, fav] = take("round", "leaderboard", "favourites");
      const rowsOf = (v: unknown): LeaderRow[] =>
        Array.isArray(v)
          ? (v as Record<string, unknown>[]).flatMap((r): LeaderRow[] => (text(r.player) ? [{ pos: text(r.pos) ?? "", player: text(r.player)!, country: text(r.country), toPar: text(r.toPar) ?? "E", thru: text(r.thru), today: text(r.today) }] : []))
          : [];
      const rows = rowsOf(lb).slice(0, 15);
      const pinned = rowsOf(fav);
      if (rows.length || pinned.length) out.push({ kind: "leaderboard", title: text(round) ? `Round ${text(round)}` : "Leaderboard", rows, pinned });
      break;
    }
    case "football":
    case "rugby":
      break;
    default:
      return [];
  }
  // Every other {home, away} key (fouls, possession, territory, shots, xG, penalties, cards…) is a split bar; football cards may be a list of chips
  for (const [k, v] of Object.entries(s)) {
    if (used.has(k)) continue;
    if (k === "cards" && Array.isArray(v)) {
      const cards = (v as unknown[]).map((c) => (typeof c === "string" ? c : text((c as Record<string, unknown>)?.label) ?? [text((c as Record<string, unknown>)?.minute), text((c as Record<string, unknown>)?.kind)].filter(Boolean).join(" "))).filter(Boolean);
      if (cards.length) out.push({ kind: "chips", label: "Cards", items: cards });
      continue;
    }
    const p = pair(v);
    if (p) bar(k.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase()), p);
  }
  return out;
}
