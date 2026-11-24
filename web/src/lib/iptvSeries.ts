// The IPTV details page fed into the shared details scaffold (parity #7): the pure parts. Tested in web/test/iptvSeries.test.ts.
// The series button names the episode through lib/splitNext with an empty library list (an IPTV-only series), as the
// library series page does; IPTV has no server "next" claim (seriesNextFor only takes library ids).
import type { VodEpisode, VodProgress } from "./iptvVod.ts";
import { iptvResumeAt, seriesPrimary, splitEpisodes, type SplitEp } from "./splitNext.ts";

export type IptvSeasons = readonly { season: number; episodes: readonly VodEpisode[] }[] | null | undefined;

export interface IptvPrimary {
  ep: SplitEp;
  /** Label "Resume" (when the episode is also in progress). */
  resume: boolean;
  /** The episode is the next one to watch (the Next pill goes on its tile). */
  next: boolean;
}

/** Watched, as the IPTV page has always counted it. */
export const epPlayed = (e: VodEpisode): boolean => e.played ?? e.progress?.played ?? false;

/** Seconds to resume a film from (none when finished or barely started). */
export const resumeAt = (p?: VodProgress | null): number => (p && !p.played && p.position > 30 ? Math.floor(p.position) : 0);

/** "1h 57m" / "48m"; null for none. */
export const vodMins = (s?: number | null): string | null => (s ? (s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.round((s % 3600) / 60)}m` : `${Math.round(s / 60)}m`) : null);

/**
 * What the series' primary button plays. The merged-order rule (splitNext) over every numbered episode of a real season;
 * when that has nothing (specials only, unnumbered episodes) the old IPTV rule over everything: a started one, else the
 * first unwatched, else the first. Null = no episodes at all.
 */
export function iptvSeriesPrimary(seasons: IptvSeasons): IptvPrimary | null {
  const p = seriesPrimary(splitEpisodes([], seasons, []), null);
  if (p) return p;
  const all = (seasons ?? []).flatMap((s) => (s.episodes ?? []).map((e) => ({ season: s.season, e })));
  const pick = all.find((x) => iptvResumeAt(x.e) > 0) ?? all.find((x) => !epPlayed(x.e)) ?? all[0];
  if (!pick) return null;
  const inProgress = iptvResumeAt(pick.e) > 0;
  return { ep: { season: pick.season, episode: pick.e.episode, lib: null, iptv: pick.e, played: epPlayed(pick.e), inProgress }, resume: inProgress, next: false };
}

/** The primary button's words: "Resume S1 E2" / "Play S1 E1". */
export const primaryLabel = (p: IptvPrimary): string => `${p.resume && p.ep.inProgress ? "Resume" : "Play"} S${p.ep.season} E${p.ep.episode}`;

/** Chip words for a season. */
export const seasonName = (n: number): string => (n === 0 ? "Specials" : `Season ${n}`);

/**
 * The season the page opens on: ?season= when it is one of this series' seasons (a chip picked before Back), else the
 * next episode's, else the first with something unwatched, else the first. Null = no seasons.
 */
export function iptvOpenSeason(seasons: IptvSeasons, asked: string | null, primary: IptvPrimary | null): number | null {
  const list = seasons ?? [];
  if (!list.length) return null;
  const n = asked != null && /^\d+$/.test(asked) ? Number(asked) : null;
  if (n != null && list.some((s) => s.season === n)) return n;
  if (primary?.next && list.some((s) => s.season === primary.ep.season)) return primary.ep.season;
  return (list.find((s) => s.episodes.some((e) => !epPlayed(e))) ?? list[0]!).season;
}

/** Under an episode's name: "48m  ·  Watched" / "48m  ·  15m in". */
export function episodeMeta(e: VodEpisode): string {
  const at = iptvResumeAt(e);
  return [vodMins(e.runtime), epPlayed(e) ? "Watched" : at ? `${vodMins(at)} in` : null].filter(Boolean).join("  ·  ");
}

/** Episode progress bar width in percent (0 = none). */
export function episodePct(e: VodEpisode): number {
  const at = iptvResumeAt(e);
  return at && e.progress?.duration ? Math.min(100, (at / e.progress.duration) * 100) : 0;
}

/**
 * Loading the title again after a failure that isn't a 404 (only a 404 means the title is gone): 1 s, 2 s, 4 s, then the
 * page shows the error with Try again. Null = stop. [status] 0 = no answer (network, timeout).
 */
export function loadRetryDelay(status: number, attempt: number): number | null {
  if (status === 404 || (status >= 400 && status < 500 && status !== 408 && status !== 429)) return null;
  return attempt < 3 ? 1000 * 2 ** attempt : null;
}
