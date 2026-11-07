// Game state for the live drawer (plan: ~/ai/plans/jellytip-sport-companion.md section 3 "Game-state content"). Pure: one selector
// from the /live payload to pre | live-tight | live | blowout | break | final, per sport, plus the per-sport "what decides it" rows,
// the break recap / final rows and the "closer game" pick from the ranked fixtures. Tested in web/test/gameState.test.ts.
//
// Thresholds (all in THRESHOLDS below; margin = absolute score gap):
//   NFL      blowout: Q3 >= 28, Q4 >= 21, Q4 with <= 8:00 left >= 17.          tight: Q4/OT <= 8 (one possession), Q3+ <= 3.
//   NBA      blowout: Q3 >= 25, Q4 >= 20, Q4 with <= 4:00 left >= 15.          tight: Q4+ <= 8, Q3 <= 3, any overtime.
//   NRL      (minute) blowout: 40' >= 30, 55' >= 24, 68' >= 18.                tight: 50' <= 6 (one try), 65' <= 12 (two scores).
//   Rugby    (minute) blowout: 45' >= 35, 60' >= 28, 70' >= 21.                tight: 55' <= 7, 68' <= 14.
//   Football (minute) blowout: 50' >= 4, 70' >= 3.                             tight: 60'+ <= 1.
//   Cricket  limited overs, chasing only (needs rrr): tight |rrr - rr| <= 1.5 (T20) / 1 (ODI); blowout rrr - rr >= 6 / 4 (hopeless)
//            or rr - rrr >= 5 / 3 (cruising). Tests (and a first innings) are plain "live".
//   Win probability (only when the caller allows it, i.e. spoilers on): >= 95% for either side in the late game = blowout;
//   35-65% in the final period = tight. Margin rules win when both fire. Anything else (F1, tennis, golf, unknown) is "live".
// Break is read from the period/clock text (half time, innings break, lunch, tea, drinks, stumps, interval). Pre/final come from `status`.
import type { Fixture } from "./matchInfo.ts";
import { bestChannel, fxTitle, type FxItem } from "./gameCentre.ts";
import { livePath } from "./iptvPlay.ts";
import { pct, statNumber, type LiveStats } from "./liveStats.ts";

export type GameStateKind = "pre" | "live-tight" | "live" | "blowout" | "break" | "final";
export type Sport = "nfl" | "nba" | "nrl" | "rugby" | "football" | "cricket" | "default";
export type CricketFormat = "t20" | "odi" | "test";

export interface GameState {
  kind: GameStateKind;
  sport: Sport;
  /** Absolute score gap, null when there is no score. */
  margin: number | null;
  leader: "home" | "away" | null;
  /** Why this state (for the tests and the debug log); not shown to people. */
  reason: string;
}

export const THRESHOLDS = {
  nfl: { blowout: [{ q: 3, m: 28 }, { q: 4, m: 21 }, { q: 4, m: 17, secsLeft: 480 }], tight: { q4: 8, q3: 3 } },
  nba: { blowout: [{ q: 3, m: 25 }, { q: 4, m: 20 }, { q: 4, m: 15, secsLeft: 240 }], tight: { q4: 8, q3: 3 } },
  nrl: { blowout: [{ min: 40, m: 30 }, { min: 55, m: 24 }, { min: 68, m: 18 }], tight: [{ min: 50, m: 6 }, { min: 65, m: 12 }] },
  rugby: { blowout: [{ min: 45, m: 35 }, { min: 60, m: 28 }, { min: 70, m: 21 }], tight: [{ min: 55, m: 7 }, { min: 68, m: 14 }] },
  football: { blowout: [{ min: 50, m: 4 }, { min: 70, m: 3 }], tight: [{ min: 60, m: 1 }] },
  cricket: { t20: { tight: 1.5, hopeless: 6, cruising: 5 }, odi: { tight: 1, hopeless: 4, cruising: 3 } },
  winProb: { blowout: 0.95, tightLow: 0.35, tightHigh: 0.65 },
} as const;

// ---------- parsing ----------

/** Sport from the /live `layout`, else from the league key. */
export function sportOf(layout: string | null | undefined, leagueKey?: string | null): Sport {
  const lk = (leagueKey ?? "").toLowerCase();
  switch (layout) {
    case "nfl":
      return "nfl";
    case "basketball":
      return "nba";
    case "football":
      return "football";
    case "cricket":
      return "cricket";
    case "rugby":
      return /nrl|league|origin|rlwc|super-?league/.test(lk) ? "nrl" : "rugby";
    case null:
    case undefined:
    case "":
      break;
    default:
      return "default";
  }
  if (/^(nfl|ncaaf|college-football)/.test(lk)) return "nfl";
  if (/^(nba|wnba|ncaab|ncaaw|mens-college-basketball)/.test(lk)) return "nba";
  if (/nrl|origin|rugby-league|super-?league|rlwc/.test(lk)) return "nrl";
  if (/rugby|super-?rugby|six-?nations|premiership|urc|rwc/.test(lk)) return "rugby";
  if (/cricket|ipl|bbl|t20|odi|test/.test(lk)) return "cricket";
  if (/^(eng|esp|ger|ita|fra|usa|aus|uefa|fifa|conmebol|epl|ucl|mls|a-league|soccer)/.test(lk)) return "football";
  return "default";
}

/** Limited-overs format from the league key / period text; Tests (and unknown) are "test", which is never tight or blowout. */
export function cricketFormat(leagueKey: string | null | undefined, period?: string | null): CricketFormat {
  const t = `${leagueKey ?? ""} ${period ?? ""}`.toLowerCase();
  if (/\btest\b|\bday \d|first-class|shield/.test(t)) return "test";
  if (/t20|ipl|bbl|psl|cpl|hundred/.test(t)) return "t20";
  if (/odi|one-?day|\bcwc\b|champions/.test(t)) return "odi";
  return "test";
}

/** Period number: "Q4" / "4th" / "4th Quarter" -> 4, "OT" / "OT2" / "2OT" -> 5+, "2nd Half" / "2H" -> 2. null when unreadable. */
export function periodNo(period: string | null | undefined): number | null {
  const p = (period ?? "").trim().toLowerCase();
  if (!p) return null;
  if (/(^|[^a-z])\d?ot\d?($|[^a-z])|overtime|extra time/.test(p)) {
    const n = /(\d)\s*ot|ot\s*(\d)/.exec(p);
    return 4 + Number(n?.[1] ?? n?.[2] ?? 1);
  }
  const n = /(\d)/.exec(p);
  if (n) return Number(n[1]);
  const words: [RegExp, number][] = [[/first|\b1h\b/, 1], [/second|\b2h\b/, 2], [/third/, 3], [/fourth/, 4]];
  return words.find(([re]) => re.test(p))?.[1] ?? null;
}

/** Seconds left on a "mm:ss" quarter clock; null for anything else (minute clocks like "63'" are not countdowns). */
export function secsLeft(clock: string | null | undefined): number | null {
  const m = /^\s*(\d{1,2}):(\d{2})(?:\.\d+)?\s*$/.exec(clock ?? "");
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

/** Elapsed match minute for football / rugby: "63'", "45+2'", "63:12", or the half's start when the clock is missing. null when unknown. */
export function matchMinute(clock: string | null | undefined, period?: string | null): number | null {
  const c = (clock ?? "").trim();
  const plus = /^(\d{1,3})\s*\+\s*(\d{1,2})/.exec(c);
  if (plus) return Number(plus[1]) + Number(plus[2]);
  const m = /^(\d{1,3})(?:'|:\d{2}|′|\s*min)?/.exec(c);
  if (m && (c.includes("'") || c.includes(":") || c.includes("′") || /^\d{1,3}$/.test(c))) return Number(m[1]);
  const p = periodNo(period);
  return p === 1 ? 1 : p === 2 ? 41 : null;
}

const BREAK_RE = /half.?time|\bht\b|innings break|\binterval\b|\bbreak\b|\blunch\b|\btea\b|\bdrinks\b|\bstumps\b|\bdinner\b|strategic time/i;
const END_HALF_RE = /end of (?:the )?(?:2nd|second)\b/i;

/** True when the clock/period text says play is paused for a break. */
export function isBreak(period: string | null | undefined, clock: string | null | undefined, sport: Sport): boolean {
  const t = `${period ?? ""} ${clock ?? ""}`;
  if (BREAK_RE.test(t)) return true;
  return (sport === "nfl" || sport === "nba") && END_HALF_RE.test(t);
}

const FINAL_RE = /^(post|ft|result)$|final|full.?time|complete|ended|closed/i;
const LIVE_RE = /^(in|live)$|in.?progress|half.?time|break/i;
const PRE_RE = /^(pre|ns)$|scheduled|upcoming|not.?started/i;

// ---------- the selector ----------

export interface StateInput {
  sport: Sport;
  status: string | null;
  score: { home: number; away: number } | null;
  clock: string | null;
  period: string | null;
  /** Home win probability (0..1); pass null when spoilers are off. */
  winProb?: number | null;
  /** The /live `situation` (cricket reads rr / rrr). */
  situation?: Record<string, unknown> | null;
  leagueKey?: string | null;
}

const hit = <T extends { m: number }>(rules: readonly T[], m: number, ok: (r: T) => boolean) => rules.some((r) => m >= r.m && ok(r));
const tightMin = (rules: readonly { min: number; m: number }[], m: number, min: number) => rules.some((r) => min >= r.min && m <= r.m);

export function classify(i: StateInput): GameState {
  const { sport } = i;
  const margin = i.score ? Math.abs(i.score.home - i.score.away) : null;
  const leader = i.score && margin ? (i.score.home > i.score.away ? "home" : "away") : null;
  const out = (kind: GameStateKind, reason: string): GameState => ({ kind, sport, margin, leader, reason });
  const status = (i.status ?? "").trim();
  if (FINAL_RE.test(status)) return out("final", "status final");
  if (PRE_RE.test(status)) return out("pre", "status pre");
  if (!status && !i.score && !i.clock && !i.period) return out("pre", "no status, score or clock");
  if (!LIVE_RE.test(status) && status && !i.score && !i.clock) return out("live", `status ${status}`);
  if (isBreak(`${status} ${i.period ?? ""}`, i.clock, sport)) return out("break", "period text says break");
  if (margin == null) return out("live", "no score");
  const wp = i.winProb != null ? Math.max(i.winProb, 1 - i.winProb) : null;
  const wpTight = i.winProb != null && i.winProb >= THRESHOLDS.winProb.tightLow && i.winProb <= THRESHOLDS.winProb.tightHigh;
  const q = periodNo(i.period);
  const left = secsLeft(i.clock);

  switch (sport) {
    case "nfl": {
      const T = THRESHOLDS.nfl;
      if (q != null && q >= 3 && (hit(T.blowout, margin, (r) => q >= r.q && (!("secsLeft" in r) || (left != null && left <= r.secsLeft))) || (wp != null && wp >= THRESHOLDS.winProb.blowout)))
        return out("blowout", `margin ${margin} in Q${q}`);
      if (q != null && ((q >= 4 && margin <= T.tight.q4) || (q >= 3 && margin <= T.tight.q3) || (q >= 4 && wpTight))) return out("live-tight", `margin ${margin} in Q${q}`);
      return out("live", "default");
    }
    case "nba": {
      const T = THRESHOLDS.nba;
      if (q != null && q >= 3 && q <= 4 && (hit(T.blowout, margin, (r) => q >= r.q && (!("secsLeft" in r) || (left != null && left <= r.secsLeft))) || (wp != null && wp >= THRESHOLDS.winProb.blowout)))
        return out("blowout", `margin ${margin} in Q${q}`);
      if (q != null && (q >= 5 || (q === 4 && margin <= T.tight.q4) || (q === 3 && margin <= T.tight.q3) || (q >= 4 && wpTight))) return out("live-tight", `margin ${margin} in Q${q}`);
      return out("live", "default");
    }
    case "nrl":
    case "rugby":
    case "football": {
      const T: { blowout: readonly { min: number; m: number }[]; tight: readonly { min: number; m: number }[] } = THRESHOLDS[sport];
      const min = matchMinute(i.clock, i.period);
      if (min == null) return out("live", "no minute");
      if (hit(T.blowout, margin, (r) => min >= r.min) || (wp != null && wp >= THRESHOLDS.winProb.blowout && min >= 50)) return out("blowout", `margin ${margin} at ${min}'`);
      if (tightMin(T.tight, margin, min) || (wpTight && min >= 60)) return out("live-tight", `margin ${margin} at ${min}'`);
      return out("live", "default");
    }
    case "cricket": {
      const fmt = cricketFormat(i.leagueKey, i.period);
      if (fmt === "test") return out("live", "test match");
      const T = THRESHOLDS.cricket[fmt];
      const rr = statNumber(textOf(i.situation?.rr));
      const rrr = statNumber(textOf(i.situation?.rrr));
      if (rr == null || rrr == null || rr <= 0) return out("live", "no chase");
      const gap = rrr - rr;
      if (gap >= T.hopeless || -gap >= T.cruising) return out("blowout", `required ${rrr} v current ${rr}`);
      if (Math.abs(gap) <= T.tight) return out("live-tight", `required ${rrr} v current ${rr}`);
      return out("live", "chase");
    }
    default:
      return out("live", "default");
  }
}

const textOf = (v: unknown): string | null => (typeof v === "string" && v ? v : typeof v === "number" && isFinite(v) ? String(v) : null);
const objOf = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);

/** State for a /live answer. `allowWinProb` is false unless spoilers are on (the drawer never lets odds drive anything otherwise). */
export function gameStateOf(d: LiveStats, fixture: Pick<Fixture, "leagueKey" | "status"> | null, allowWinProb: boolean): GameState {
  const sport = sportOf(d.layout, fixture?.leagueKey);
  return classify({ sport, status: d.status ?? (d.hidden ? null : fixture?.status ?? null), score: d.score, clock: d.clock, period: d.period, winProb: allowWinProb ? d.winProb : null, situation: d.situation, leagueKey: fixture?.leagueKey });
}

// ---------- personalisation ----------

const norm = (s: string | null | undefined) => (s ?? "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/** Which side is the viewer's team: an exact (normalised) name or short-name match against the followed team names. null for none / both. */
export function favouriteSide(fixture: Pick<Fixture, "home" | "away" | "kind"> | null, followed: readonly string[]): "home" | "away" | null {
  if (!fixture || fixture.kind !== "match") return null;
  const f = new Set(followed.map(norm).filter(Boolean));
  const is = (s: Fixture["home"]) => f.has(norm(s.name)) || (!!s.short && f.has(norm(s.short)));
  const [h, a] = [is(fixture.home), is(fixture.away)];
  return h && !a ? "home" : a && !h ? "away" : null;
}

// ---------- "what decides it" ----------

export type Row = [label: string, value: string];
export interface Names {
  home: string;
  away: string;
}

const nameOf = (side: "home" | "away" | null, n: Names) => (side ? n[side] : null);
const pairOf = (v: unknown): { home: string; away: string } | null => {
  const o = objOf(v);
  const h = textOf(o?.home);
  const a = textOf(o?.away);
  return h != null && a != null ? { home: h, away: a } : null;
};

/** One-score / two-score wording for a points gap (rugby league and union). */
export function scoreGapText(margin: number, sport: Sport): string {
  const per = sport === "nrl" ? 6 : 7;
  const n = Math.max(1, Math.ceil(margin / per));
  return n === 1 ? "one score" : n === 2 ? "two scores" : `${n} scores`;
}

/** Wickets in hand from `wickets` (down) / `wicketsDown` / `wkts` in the situation; null when absent. */
export function wicketsInHand(s: Record<string, unknown> | null): number | null {
  const v = s?.wicketsInHand ?? s?.wicketsLeft;
  if (typeof v === "number") return v;
  const down = s?.wickets ?? s?.wicketsDown ?? s?.wkts ?? objOf(s?.batting)?.wickets;
  const n = typeof down === "number" ? down : statNumber(textOf(down));
  return n != null && n >= 0 && n <= 10 ? 10 - n : null;
}

/** The rows that lead a tight game, per sport (plan: NFL down & distance + field position, NBA run + fouls, cricket required rate +
 * wickets + equation, football clock + cards, rugby / NRL score + time + last scoring play). Up to 5, plus the win
 * probability when `allowWinProb` (spoilers on only). */
export function deciderRows(d: LiveStats, st: GameState, names: Names, allowWinProb: boolean): Row[] {
  const s = d.situation;
  const rows: Row[] = [];
  const lead = nameOf(st.leader, names);
  const gap = st.margin == null ? null : st.margin === 0 ? "Level" : `${lead ?? "Lead"} by ${st.margin}`;
  switch (st.sport) {
    case "nfl": {
      const down = textOf(s?.down);
      const dist = textOf(s?.distance) ?? textOf(s?.ytg);
      if (down) rows.push(["Down & distance", dist ? `${down} & ${dist}` : down]);
      const ball = textOf(s?.yardLine) ?? textOf(s?.ballOn);
      if (ball) rows.push(["Ball on", s?.redZone === true ? `${ball} · red zone` : ball]);
      const poss = s?.possession === "home" || s?.possession === "away" ? names[s.possession] : null;
      if (poss) rows.push(["Possession", poss]);
      const to = pairOf(s?.timeouts);
      if (to) rows.push(["Timeouts", `${names.home} ${to.home} · ${names.away} ${to.away}`]);
      if (gap) rows.push(["Score", gap]);
      break;
    }
    case "nba": {
      const run = textOf(s?.run) ?? textOf(objOf(s?.run)?.text);
      if (run) rows.push(["Run", run]);
      const fouls = pairOf(s?.fouls);
      if (fouls) rows.push(["Team fouls", `${names.home} ${fouls.home} · ${names.away} ${fouls.away}`]);
      if (gap) rows.push(["Score", gap]);
      break;
    }
    case "cricket": {
      const rrr = textOf(s?.rrr);
      const rr = textOf(s?.rr);
      if (rrr) rows.push(["Required rate", rr ? `${rrr} (now ${rr})` : rrr]);
      const w = wicketsInHand(s);
      if (w != null) rows.push(["Wickets in hand", String(w)]);
      const eq = textOf(s?.equation);
      if (eq) rows.push(["Equation", eq]);
      break;
    }
    case "football": {
      const min = matchMinute(d.clock, d.period);
      rows.push(["Clock", min != null ? `${min}'` : (d.clock ?? "Live")]);
      if (gap) rows.push(["Score", gap]);
      const cards = Array.isArray(s?.cards) ? (s!.cards as unknown[]).map((c) => (typeof c === "string" ? c : textOf(objOf(c)?.label) ?? [textOf(objOf(c)?.minute), textOf(objOf(c)?.kind)].filter(Boolean).join(" "))).filter(Boolean) : [];
      const cp = pairOf(s?.cards);
      if (cards.length) rows.push(["Cards", cards.slice(-4).join(" · ")]);
      else if (cp) rows.push(["Cards", `${names.home} ${cp.home} · ${names.away} ${cp.away}`]);
      break;
    }
    case "nrl":
    case "rugby": {
      if (gap) rows.push(["Score", st.margin ? `${gap} · ${scoreGapText(st.margin, st.sport)}` : gap]);
      const min = matchMinute(d.clock, d.period);
      if (min != null) rows.push(["Time left", `${Math.max(0, 80 - min)}'`]);
      const last = d.plays.find((p) => p.scoring);
      if (last) rows.push(["Last score", [last.clock, last.text].filter(Boolean).join(" ")]);
      const cards = pairOf(s?.cards);
      if (cards && (cards.home !== "0" || cards.away !== "0")) rows.push(["Cards", `${names.home} ${cards.home} · ${names.away} ${cards.away}`]);
      break;
    }
    default:
      if (gap) rows.push(["Score", gap]);
  }
  const lead5 = rows.slice(0, 5);
  if (allowWinProb && d.winProb != null) lead5.push(["Win probability", `${names.home} ${pct(d.winProb)}`]);
  return lead5;
}

/** The one line a blowout collapses to: "Lakers lead 112–78 · Q4 3:10". */
export function blowoutLine(d: LiveStats, st: GameState, names: Names, favourite: "home" | "away" | null): string {
  const sc = d.score;
  if (!sc || !st.leader) return "Decided.";
  const [hi, lo] = st.leader === "home" ? [sc.home, sc.away] : [sc.away, sc.home];
  const who = favourite ? (favourite === st.leader ? "Your team leads" : `${names[st.leader]} lead`) : `${names[st.leader]} lead`;
  const when = [d.period, d.clock].filter(Boolean).join(" ");
  return `${who} ${hi}–${lo}${when ? ` · ${when}` : ""}`;
}

/** Scoring plays for a recap, oldest first (the server sends the plays newest first), the viewer's side flagged. At most `max`. */
export function recapPlays(d: LiveStats, favourite: "home" | "away" | null, max = 6): { when: string; text: string; delta: number | null; team: "home" | "away" | null; mine: boolean }[] {
  return d.plays
    .filter((p) => p.scoring)
    .slice(0, max)
    .reverse()
    .map((p) => ({ when: [p.period, p.clock].filter(Boolean).join(" "), text: p.text, delta: p.delta, team: p.team, mine: !!favourite && p.team === favourite }));
}

/** Result line for a finished game: "Panthers 24–18 Warriors"; null when there is no score. */
export function resultLine(d: LiveStats, names: Names): string | null {
  const sc = d.score;
  if (!sc) return null;
  return `${names.home} ${sc.home}–${sc.away} ${names.away}`;
}

// ---------- the closer game ----------

export interface CloserGame {
  item: FxItem;
  /** Play link for its best channel (a live play with its match info, not the spoiler-free Game Centre path). */
  path: string;
  title: string;
}

/**
 * The live fixture to suggest instead of a blowout: ranked order is kept (live first, favourites, server rank); only live games with
 * a channel and a score whose own state is tight or plain live qualify; tight beats live, a favourite beats both. The game being watched
 * is excluded by fixture id or match id.
 */
export function closerGame(items: readonly FxItem[], current: { fixtureId?: string | null; matchId?: string | null }): CloserGame | null {
  const ranked = items
    .map((item, order) => ({ item, order }))
    .filter(({ item: f }) => f.status === "in" && f.score && f.fid !== current.fixtureId && (!f.matchId || (f.matchId !== current.matchId && f.matchId !== current.fixtureId)) && bestChannel(f))
    .map(({ item, order }) => {
      const st = classify({ sport: sportOf(null, item.leagueKey), status: "in", score: item.score, clock: item.score?.clock ?? null, period: item.score?.period ?? null, leagueKey: item.leagueKey });
      return { item, order, st };
    })
    .filter(({ st }) => st.kind === "live-tight" || st.kind === "live")
    .sort((a, b) => Number(b.item.favourite) - Number(a.item.favourite) || Number(b.st.kind === "live-tight") - Number(a.st.kind === "live-tight") || a.order - b.order);
  const pick = ranked[0]?.item;
  const ch = pick ? bestChannel(pick) : null;
  if (!pick || !ch) return null;
  const title = fxTitle(pick);
  return { item: pick, title, path: livePath(ch.id, title, { matchId: pick.matchId, alternates: pick.channels.filter((c) => c.id !== ch.id).map((c) => ({ id: c.id, name: c.name })) }) };
}
