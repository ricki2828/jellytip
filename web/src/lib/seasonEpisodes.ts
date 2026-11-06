// A series' season as a COMPLETE episode list (pure; tested in web/test/seasonEpisodes.test.ts):
// Jellyfin's real files + its missing/unaired placeholders + episodes the person's own IPTV has.
// Reasons are stated only from data the client holds.
import type { Item } from "../api/jellyfin.ts";
import type { VodEpisode, VodItem } from "./iptvVod.ts";
import { episodeReason, providerOf } from "./availability.ts";

export { providerOf };

export type EpStatus = "present" | "missing" | "unaired";

export interface EpRow {
  key: string;
  status: EpStatus;
  index: number;
  name: string;
  /** The Jellyfin item: present episodes always, placeholders when Jellyfin lists them. */
  item?: Item;
  /** Unaired: the air date from Jellyfin. */
  airDate?: string;
  /** A missing episode this person's IPTV has (their own / shared provider). */
  iptv?: { id: string; title?: string | null };
}

const isVirtual = (e: Item) => e.LocationType === "Virtual";

/** Jellyfin `IsMissing`/`IsUnaired` surface as a Virtual item: future premiere = unaired, otherwise missing. */
export function episodeStatus(e: Item, now: Date): EpStatus {
  if (!isVirtual(e)) return "present";
  const t = e.PremiereDate ? Date.parse(e.PremiereDate) : NaN;
  return isFinite(t) && t > now.getTime() ? "unaired" : "missing";
}

/** Episodes that have a file (what play, download and prev/next may use). */
export const presentEpisodes = (all: Item[]): Item[] =>
  all.filter((e) => !isVirtual(e));

const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/** "Airs 12 Dec" (the year is added when it is not this year); null for a missing/invalid date. */
export function airsLabel(
  date: string | undefined | null,
  now: Date,
): string | null {
  if (!date) return null;
  const d = new Date(date);
  if (!isFinite(d.getTime())) return null;
  const y = d.getUTCFullYear();
  return `Airs ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}${y !== now.getUTCFullYear() ? ` ${y}` : ""}`;
}

/**
 * Merge a season. `seasonNumber` is the season's own number; IPTV episodes only fill gaps (a present or unaired
 * Jellyfin episode is never replaced), matched on episode number.
 */
export function mergeEpisodes(
  jf: Item[],
  iptv: VodEpisode[] | null | undefined,
  now: Date,
): EpRow[] {
  const presentIdx = new Set(
    jf
      .filter((e) => !isVirtual(e) && e.IndexNumber != null)
      .map((e) => e.IndexNumber!),
  );
  const out: EpRow[] = [];
  const seen = new Set<number>();
  for (const e of jf) {
    const status = episodeStatus(e, now);
    // A placeholder for a number that has a file (or a duplicate placeholder) is noise
    if (
      status !== "present" &&
      e.IndexNumber != null &&
      (presentIdx.has(e.IndexNumber) || seen.has(e.IndexNumber))
    )
      continue;
    if (status !== "present" && e.IndexNumber != null) seen.add(e.IndexNumber);
    out.push({
      key: e.Id,
      status,
      index: e.IndexNumber ?? 0,
      name: e.Name,
      item: e,
      ...(status === "unaired" && e.PremiereDate
        ? { airDate: e.PremiereDate }
        : {}),
    });
  }
  // IPTV only attaches to a Jellyfin placeholder (it pins season + episode); never a playable row of its own,
  // as absolute numbering or folded specials would play the wrong episode
  for (const v of iptv ?? []) {
    const have = out.find(
      (r) => r.status === "missing" && r.index === v.episode && !r.iptv,
    );
    if (have && titlesAgree(have.name, v.title))
      have.iptv = { id: v.id, title: v.title };
  }
  return out.sort((a, b) => a.index - b.index);
}

const norm = (t: string) =>
  t
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
const generic = (t: string) => !t || /^(episode|ep)\s*\d+$/.test(t);
/** Rough title match; unknown or generic titles ("Episode 5") can't disagree. */
export function titlesAgree(
  a: string | null | undefined,
  b: string | null | undefined,
): boolean {
  const x = norm(a ?? ""),
    y = norm(b ?? "");
  if (generic(x) || generic(y)) return true;
  return x === y || x.includes(y) || y.includes(x);
}

/** Plain-language state of a row, from the data we hold. Never invents a reason. */
export function reasonLabel(r: EpRow, now: Date): string | null {
  switch (r.status) {
    case "present":
      return null;
    case "unaired":
      return airsLabel(r.airDate, now) ?? "Not released yet";
    case "missing":
      return episodeReason(!!r.iptv);
  }
}

/** Second line under an unaired placeholder that has a date: the date says when, this says why. */
export const reasonDetail = (r: EpRow, now: Date): string | null =>
  r.status === "unaired" && airsLabel(r.airDate, now)
    ? "Not released yet"
    : null;

/** Only titles from the person's own or shared providers (what GET v1/iptv/providers returned) survive. */
export function ownedVod<T extends { id: string }>(
  items: T[],
  allowedProviderIds: Iterable<string | number>,
): T[] {
  const ok = new Set([...allowedProviderIds].map(String));
  return items.filter((i) => ok.has(providerOf(i.id)));
}

/** The IPTV series that is this Jellyfin series: same TMDB id, owned provider. Null when unsure. */
export function pickIptvSeries(
  items: VodItem[],
  tmdbId: string | number | undefined | null,
  allowedProviderIds: Iterable<string | number>,
): VodItem | null {
  const want = Number(tmdbId);
  if (!isFinite(want) || want <= 0) return null;
  return (
    ownedVod(items, allowedProviderIds).find(
      (i) => i.kind === "series" && i.tmdbId === want,
    ) ?? null
  );
}
