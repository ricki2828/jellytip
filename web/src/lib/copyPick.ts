// "Ways to watch": the copy the person picked for a title is remembered on this device and opened first next time. Pure helpers + localStorage.
import { failedLately, failOf, type LastFail } from "./streamFail.ts";

const PREFIX = "jt.copypick.";

/** One key per title: TMDB id when the companion has it, else the normalised title + year. */
export function pickKey(d: { kind: string; tmdbId?: number | null; title: string; year?: number | null }): string {
  if (d.tmdbId) return `${d.kind}:t${d.tmdbId}`;
  return `${d.kind}:n${d.title.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim()}:${d.year ?? ""}`;
}

type Store = Pick<Storage, "getItem" | "setItem">;
const store = (): Store | null => {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
};

export function rememberPick(key: string, copyId: string, s: Store | null = store()): void {
  try {
    s?.setItem(PREFIX + key, copyId);
  } catch {
    /* storage full / blocked: the pick just isn't remembered */
  }
}

export function recallPick(key: string, s: Store | null = store()): string | null {
  try {
    return s?.getItem(PREFIX + key) ?? null;
  } catch {
    return null;
  }
}

/**
 * Where to open a title: the remembered copy when it is still on offer and has not failed in the last 24 h, else null (stay on the
 * copy that was opened). `currentId` itself never redirects.
 */
export function rememberedTarget(
  remembered: string | null,
  currentId: string,
  copies: readonly { id: string; lastFail?: LastFail | null }[] | undefined,
  nowS: number,
): string | null {
  if (!remembered || remembered === currentId) return null;
  const c = copies?.find((x) => x.id === remembered);
  return c && !failedLately(failOf(c.id, c.lastFail), nowS) ? c.id : null;
}
