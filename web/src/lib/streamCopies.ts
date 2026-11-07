// Which episode of another provider copy of a series is "the same" episode (season + episode number). Pure.
import type { LastFail } from "./streamFail.ts";

export interface EpLoc { season: number; episode: number }
export interface CopyEpisode { id: string; lastFail?: LastFail | null }

/** The episode of `seasons` (a copy's seasons) with the same season and episode number; null when that copy lacks it. */
export function findEpisodeCopy(here: EpLoc, seasons: readonly { season: number; episodes: readonly (CopyEpisode & { episode: number })[] }[] | undefined): CopyEpisode | null {
  const s = seasons?.find((x) => x.season === here.season);
  const e = s?.episodes.find((x) => x.episode === here.episode);
  return e ? { id: e.id, lastFail: e.lastFail } : null;
}

export interface CopyRef { id: string; name: string; lastFail?: LastFail | null }
export interface CopyDetail { seasons?: readonly { season: number; episodes: readonly (CopyEpisode & { episode: number })[] }[] }

/**
 * The same episode (season + episode number) on another copy of the series that has not failed lately, trying copies in order.
 * `fetchDetail` loads a copy's details (one API call each, never a stream). null when no other copy has it.
 */
export async function alternateEpisode(
  here: EpLoc,
  currentSeries: string,
  copies: readonly CopyRef[],
  fetchDetail: (id: string) => Promise<CopyDetail | null | undefined>,
  failed: (id: string, lf?: LastFail | null) => boolean,
): Promise<{ copy: CopyRef; episode: CopyEpisode } | null> {
  for (const c of copies) {
    if (c.id === currentSeries || failed(c.id, c.lastFail)) continue;
    const ep = findEpisodeCopy(here, (await fetchDetail(c.id))?.seasons);
    if (ep && !failed(ep.id, ep.lastFail)) return { copy: c, episode: ep };
  }
  return null;
}
