// Next up over a SPLIT series (part library, part IPTV): one ordered episode list, so "next" can be an IPTV-only episode
// (TV port: jellytip/details/JellytipSplitNext.kt, d2183489 / 501af91f / eeba1b39 / 95bf9b42). Jellyfin's NextUp only knows
// library episodes, so after library S2E2 it says E6 although IPTV has E3-E5 (The Celebrity Traitors, 22-Nov).
// Pure; tested in web/test/splitNext.test.ts. Shared by the series page (button + Next marker), Home Next Up and both players.
//
// Web difference from the TV: an IPTV episode only takes a slot the series page actually draws (lib/seasonEpisodes): in a
// season the library has, it fills a missing placeholder whose title agrees; a season the library lacks is all IPTV. So the
// button never names an episode with no tile. Watched / started still count from either source on the same (season, episode).
import type { VodEpisode } from "./iptvVod.ts";
import { titlesAgree } from "./seasonEpisodes.ts";

/** A library episode as the merge sees it. */
export interface SplitLibEp {
  id: string;
  season: number | null;
  episode: number | null;
  name?: string | null;
  played: boolean;
  positionTicks?: number;
  /** A missing / unaired placeholder (no file): never a slot of its own; its IPTV copy (if any) takes the slot. */
  virtual?: boolean;
  /** A placeholder that hasn't aired (future premiere): the page draws "Airs …" with no IPTV button, so no IPTV slot either. */
  unaired?: boolean;
}

/** One slot of the merged list: the library copy (wins when it has a file) or the IPTV copy. */
export interface SplitEp {
  season: number;
  episode: number;
  /** The library episode with a file that plays this slot. */
  lib: SplitLibEp | null;
  /** The IPTV copy: the one that plays when there is no library file. */
  iptv: VodEpisode | null;
  /** Watched in the library OR on IPTV. */
  played: boolean;
  /** The copy that plays has a resume point. */
  inProgress: boolean;
}

const real = (n: number | null | undefined): n is number => typeof n === "number" && Number.isInteger(n) && n >= 1;
const vPlayed = (v: VodEpisode | undefined) => !!(v && (v.played || v.progress?.played));
/** IPTV resume point worth resuming (as the IPTV page: > 30 s, not finished). */
export const iptvResumeAt = (v: VodEpisode | null | undefined): number => {
  const p = v?.progress;
  return p && !p.played && p.position > 30 ? Math.floor(p.position) : 0;
};

/**
 * Every real episode (season >= 1, known numbers) the series page draws, in (season, episode) order. [libSeasons] = the
 * season numbers the library has (their IPTV episodes only fill title-agreeing placeholders). Duplicate IPTV season blocks:
 * the first copy wins (as mergeSeasonBlocks / the tiles).
 */
export function splitEpisodes(library: readonly SplitLibEp[], iptv: readonly { season: number; episodes: readonly VodEpisode[] }[] | null | undefined, libSeasons: Iterable<number>): SplitEp[] {
  const k = (s: number, e: number) => `${s}x${e}`;
  const present = new Map<string, SplitLibEp>();
  const placeholder = new Map<string, SplitLibEp>();
  const libAny = new Map<string, SplitLibEp>();
  for (const l of library) {
    if (!real(l.season) || !real(l.episode)) continue;
    const key = k(l.season, l.episode);
    if (!l.virtual) {
      if (!present.has(key)) present.set(key, l);
      libAny.set(key, l);
    } else {
      if (!placeholder.has(key)) placeholder.set(key, l);
      if (!libAny.has(key)) libAny.set(key, l);
    }
  }
  const tv = new Map<string, VodEpisode>();
  for (const s of iptv ?? []) {
    if (!real(s.season)) continue;
    for (const e of s.episodes ?? []) if (real(e.episode) && !tv.has(k(s.season, e.episode))) tv.set(k(s.season, e.episode), e);
  }
  const libSeason = new Set(libSeasons);
  const out: SplitEp[] = [];
  const keys = new Set<string>([...present.keys(), ...tv.keys()]);
  for (const key of keys) {
    const [s, e] = key.split("x").map(Number) as [number, number];
    const l = present.get(key) ?? null;
    const v = tv.get(key);
    let iptvCopy: VodEpisode | null = null;
    if (!l && v) {
      if (!libSeason.has(s)) iptvCopy = v;
      else {
        const ph = placeholder.get(key);
        if (ph && !ph.unaired && titlesAgree(ph.name, v.title)) iptvCopy = v;
      }
    }
    if (!l && !iptvCopy) continue; // no tile for it on the page
    const lPlayed = !!libAny.get(key)?.played;
    out.push({
      season: s,
      episode: e,
      lib: l,
      iptv: l ? v ?? null : iptvCopy,
      played: lPlayed || vPlayed(v),
      inProgress: l ? (l.positionTicks ?? 0) > 0 && !l.played : iptvResumeAt(iptvCopy) > 0,
    });
  }
  return out.sort((a, b) => a.season - b.season || a.episode - b.episode);
}

/** The slot that plays from the library (a file) or else IPTV. */
export const playsFromLibrary = (e: SplitEp): boolean => !!e.lib;

/**
 * The episode the series' primary button plays: the first one not yet watched after the LAST watched one (either source);
 * nothing watched = the first episode; everything after the last watched one watched = null.
 */
export function splitNextUp(eps: readonly SplitEp[]): SplitEp | null {
  if (!eps.length) return null;
  let last = -1;
  eps.forEach((e, i) => e.played && (last = i));
  return eps.slice(last + 1).find((e) => !e.played) ?? null;
}

/** The episode after [season]x[episode] in the merged order (library or IPTV), for the players' Up next; null = the last one. */
export function nextAfter(eps: readonly SplitEp[], season: number | null | undefined, episode: number | null | undefined): SplitEp | null {
  if (season == null || episode == null) return null;
  return eps.find((e) => e.season > season || (e.season === season && e.episode > episode)) ?? null;
}

/** The server's merged next for one series (GET /v1/me/next[?seriesId=]): [source] null = the show is finished. */
export interface SeriesNextClaim {
  source: "library" | "iptv" | null;
  id: string | null;
  season: number | null;
  episode: number | null;
  inProgress: boolean;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
export const dashless = (id: string): string => id.replace(/-/g, "").toLowerCase();

function parseClaim(raw: unknown): SeriesNextClaim | null {
  const o = obj(raw);
  // Only an explicit JSON null means "finished"; a claim without the key is malformed (no claim)
  if (!o || !("source" in o)) return null;
  const src = o.source;
  if (src !== null && src !== "library" && src !== "iptv") return null;
  const pos = (n: unknown) => (typeof n === "number" && Number.isInteger(n) && n > 0 ? n : null);
  return {
    source: src,
    id: typeof o.id === "string" && o.id.trim() ? o.id : null,
    season: pos(o.season),
    episode: pos(o.episode),
    inProgress: o.inProgress === true,
  };
}

/** Every series' claim in a `GET /v1/me/next` answer, by dashless lowercase series id; empty = none / malformed. */
export function parseSeriesNext(body: unknown): Map<string, SeriesNextClaim> {
  const all = obj(obj(body)?.seriesNext);
  const out = new Map<string, SeriesNextClaim>();
  if (!all) return out;
  for (const [k, v] of Object.entries(all)) {
    const c = parseClaim(v);
    if (c) out.set(dashless(k), c);
  }
  return out;
}

export type ClaimedNext = { kind: "episode"; ep: SplitEp } | { kind: "finished" };

/**
 * The claim against the page's merged list: the slot with that library / IPTV id, else the same season and episode;
 * null = the page can't place it (use splitNextUp). A finished claim is "finished".
 */
export function claimedNext(claim: SeriesNextClaim, eps: readonly SplitEp[]): ClaimedNext | null {
  if (claim.source == null) return { kind: "finished" };
  const want = claim.id;
  const byId = want ? eps.find((e) => e.iptv?.id === want || (e.lib && dashless(e.lib.id) === dashless(want))) : undefined;
  const hit = byId ?? eps.find((e) => e.season === claim.season && e.episode === claim.episode);
  return hit ? { kind: "episode", ep: hit } : null;
}

/**
 * What the series page's primary button plays: the server's claim when the page can place it, else the order rule;
 * finished (by the claim, or everything after the last watched watched) = the first episode again. Null = no episodes.
 */
export function seriesPrimary(eps: readonly SplitEp[], claim: SeriesNextClaim | null | undefined): { ep: SplitEp; resume: boolean; next: boolean } | null {
  if (!eps.length) return null;
  const c = claim ? claimedNext(claim, eps) : null;
  if (c?.kind === "episode") return { ep: c.ep, resume: c.ep.inProgress || (!!claim?.inProgress && !c.ep.played), next: true };
  if (c?.kind === "finished") return { ep: eps[0]!, resume: false, next: false };
  const n = splitNextUp(eps);
  if (n) return { ep: n, resume: n.inProgress, next: true };
  return { ep: eps[0]!, resume: false, next: false };
}

/**
 * Home Next Up: a library next-up episode (Jellyfin's NextUp, which knows nothing of IPTV) stays only when the server's
 * merged next for its series agrees, or when nothing was claimed for it. A started episode (resume point) is a Continue
 * entry, never hidden here. Claim on IPTV, another library episode, or finished = hidden.
 */
export function keepLibraryNext(seriesId: string | null | undefined, episodeId: string, positionTicks: number, claims: ReadonlyMap<string, SeriesNextClaim>): boolean {
  if (!seriesId || positionTicks > 0) return true;
  const c = claims.get(dashless(seriesId));
  if (!c) return true;
  return c.source === "library" && !!c.id && dashless(c.id) === dashless(episodeId);
}
