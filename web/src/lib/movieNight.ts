// Shared movie night (#/movienight): pure logic for the vote tally, the winner and tie-break, join-code parsing, picking the
// shortlist from Tonight's candidates and reading the server's session view. Tested in web/test/movieNight.test.ts.
// The server (/v1/me/movienight*) stores ids and votes only; everything shown is derived here.
import type { Cand } from "./tonight.ts";
import { ranked } from "./tonight.ts";

export type Vote = "yes" | "maybe" | "no";
export const VOTES: Vote[] = ["yes", "maybe", "no"];
export const MIN_FILMS = 2;
export const MAX_FILMS = 12;
/** The shortlist offered when starting one (the host can untick). */
export const SHORTLIST = 10;
export const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export interface Counts {
  yes: number;
  maybe: number;
  no: number;
}
export interface Member {
  name: string;
  voted: number;
  me: boolean;
  host: boolean;
}
export interface Session {
  code: string;
  status: "open" | "closed";
  host: boolean;
  items: string[];
  /** Unix seconds. */
  expiresAt: number;
  mine: Record<string, Vote>;
  counts: Record<string, Counts>;
  members: Member[];
}

/**
 * A typed code or a pasted link → the 6-character code, or null. Case, spaces and dashes don't matter; a link needs
 * "join=CODE" (the share link) and nothing else is guessed from it.
 */
export function parseCode(input: string): string | null {
  const s = input.trim();
  const link = /[?&]join=([^&#\s]+)/i.exec(s);
  const raw = (link ? safeDecode(link[1]) : s).replace(/[\s-]/g, "").toUpperCase();
  return new RegExp(`^[${CODE_ALPHABET}]{6}$`).test(raw) ? raw : null;
}

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return "";
  }
}

/** "ABC DEF" for reading aloud. */
export const showCode = (code: string): string => `${code.slice(0, 3)} ${code.slice(3)}`;

/**
 * The films to offer: highest-ranked unstarted library films from Tonight's candidates (no episodes, no IPTV, nothing begun),
 * at most `n`. Order is Tonight's ranking, so the same library gives the same shortlist.
 */
export function shortlist(cands: Cand[], n = SHORTLIST): Cand[] {
  return ranked(cands)
    .filter((c) => !c.started && c.source !== "iptv" && c.probe.Type === "Movie" && /^[0-9a-f]{32}$/.test(c.key))
    .slice(0, Math.min(n, MAX_FILMS));
}

// ---------- tally ----------

export const POINTS: Record<Vote, number> = { yes: 2, maybe: 1, no: -2 };

export interface Row {
  id: string;
  counts: Counts;
  score: number;
  /** Position in the host's list (the last tie-break). */
  order: number;
}

export const scoreOf = (c: Counts): number => c.yes * POINTS.yes + c.maybe * POINTS.maybe + c.no * POINTS.no;

/**
 * Every film with its score, best first. Score: yes +2, maybe +1, no −2. Ties: fewer "no", then more "yes", then the
 * host's own order. A missing count is zero.
 */
export function tally(items: string[], counts: Record<string, Counts>): Row[] {
  return items
    .map((id, order) => {
      const c = { yes: counts[id]?.yes ?? 0, maybe: counts[id]?.maybe ?? 0, no: counts[id]?.no ?? 0 };
      return { id, counts: c, score: scoreOf(c), order };
    })
    .sort((a, b) => b.score - a.score || a.counts.no - b.counts.no || b.counts.yes - a.counts.yes || a.order - b.order);
}

/** The top match, or null when nobody said yes or maybe to anything (or nobody has voted). */
export function winner(rows: Row[]): Row | null {
  const top = rows[0];
  return top && top.counts.yes + top.counts.maybe > 0 ? top : null;
}

/** Everyone who joined (at least two people) has voted on every film. */
export function allVoted(s: Pick<Session, "items" | "members">): boolean {
  return s.members.length >= 2 && s.members.every((m) => m.voted >= s.items.length);
}

/** The result is shown once the host closed it or all joined people have voted. */
export const finished = (s: Session): boolean => s.status === "closed" || allVoted(s);

/** The first film this person has not voted on yet, or null. */
export const nextToVote = (s: Pick<Session, "items" | "mine">): string | null => s.items.find((i) => !s.mine[i]) ?? null;

/** Minutes left before the session expires (never negative). */
export const minutesLeft = (s: Pick<Session, "expiresAt">, nowS: number): number => Math.max(0, Math.ceil((s.expiresAt - nowS) / 60));

// ---------- server reply ----------

const isCounts = (c: unknown): c is Counts => {
  const o = c as Record<string, unknown> | null;
  return !!o && typeof o === "object" && VOTES.every((v) => Number.isInteger(o[v]) && (o[v] as number) >= 0);
};

/** The server's session view, or null when it isn't one (an old server, an error body). */
export function parseSession(raw: unknown): Session | null {
  const o = raw as Record<string, unknown> | null;
  if (!o || typeof o !== "object") return null;
  const items = o.items;
  if (typeof o.code !== "string" || !Array.isArray(items) || !items.every((i) => typeof i === "string")) return null;
  if (o.status !== "open" && o.status !== "closed") return null;
  const counts: Record<string, Counts> = {};
  const rc = (o.counts ?? {}) as Record<string, unknown>;
  for (const i of items as string[]) if (isCounts(rc[i])) counts[i] = rc[i] as Counts;
  const mine: Record<string, Vote> = {};
  const rm = (o.mine ?? {}) as Record<string, unknown>;
  for (const i of items as string[]) if (VOTES.includes(rm[i] as Vote)) mine[i] = rm[i] as Vote;
  const members: Member[] = (Array.isArray(o.members) ? o.members : [])
    .filter((m): m is Record<string, unknown> => !!m && typeof m === "object")
    .map((m) => ({ name: typeof m.name === "string" ? m.name : "", voted: Number.isInteger(m.voted) ? (m.voted as number) : 0, me: m.me === true, host: m.host === true }));
  return { code: o.code, status: o.status, host: o.host === true, items: items as string[], expiresAt: Number.isFinite(o.expiresAt) ? (o.expiresAt as number) : 0, mine, counts, members };
}

/** Join link for sharing: the app's own address plus the hash route. */
export const shareLink = (base: string, code: string): string => `${base.replace(/#.*$/, "")}#/movienight?join=${code}`;
