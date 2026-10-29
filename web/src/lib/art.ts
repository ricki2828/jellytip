// Image choice rules, ported from the TV app (cards/JellytipCardArt.kt, cache/JellytipBackdrop.kt):
// tiles prefer titled Thumb art; the home hero uses the companion's heroIndex and details its detailsIndex,
// and neither may repeat the backdrop the tile is drawing.

import { imageUrl, type Item } from "../api/jellyfin";
import { peekBackdropInfo, peekEpisodeArt, type Focal } from "../api/companion";

export const HERO_MAX_WIDTH = 1920;

const usesSeriesBackdrop = (item: Item) => !!item.SeriesId && (item.Type === "Episode" || item.Type === "Season");

/** Item whose backdrops the hero shows (and the companion ranks): the series for episodes and seasons. */
export function heroItemId(item: Item): string {
  return usesSeriesBackdrop(item) ? item.SeriesId! : item.Id;
}

function backdropTags(item: Item): string[] {
  return (usesSeriesBackdrop(item) ? item.ParentBackdropImageTags : item.BackdropImageTags) ?? [];
}

/** Companion index mapped BY TAG onto the item's current backdrops, always within range (else 0). */
export function resolveBackdropIndex(currentTags: string[], itemId: string, index: number | null | undefined): number {
  if (currentTags.length === 0 || index == null) return 0;
  const scored = peekBackdropInfo(itemId)?.tags.get(index);
  if (scored) {
    const i = currentTags.indexOf(scored);
    return i >= 0 ? i : 0;
  }
  return index >= 0 && index < currentTags.length ? index : 0;
}

const isThumbGood = (id: string) => peekBackdropInfo(id)?.thumbGood ?? true;

function isEpisodeStillUsable(item: Item): boolean {
  const map = item.SeriesId ? peekEpisodeArt(item.SeriesId) : undefined;
  return map?.get(item.Id.replace(/-/g, "")) ?? true;
}

function bestBackdropIndex(id: string): number | null {
  const b = peekBackdropInfo(id)?.best;
  return b != null && b >= 0 ? b : null;
}

/** True when the tile draws a backdrop (not a good titled Thumb). */
function cardDrawsBackdrop(item: Item): boolean {
  if (item.Type === "Episode" && item.SeriesId) {
    const thumb = item.ParentThumbItemId;
    const titled = !!item.ParentThumbImageTag && !!thumb && isThumbGood(thumb);
    return !titled && (!item.ImageTags?.Primary || !isEpisodeStillUsable(item));
  }
  return !item.ImageTags?.Thumb || !isThumbGood(item.Id);
}

export interface CardArt {
  baseUrl: string | null;
  /** Base image already carries the title (Thumb). */
  titled: boolean;
  logoUrl: string | null;
}

/**
 * 1. Thumb (title baked in) · 2. episodes: own still + series logo, else series Thumb / backdrop
 * 3. backdrop + clearlogo · 4. backdrop/primary + title text (the caller draws it)
 */
export function cardArt(item: Item, w: number, h: number): CardArt {
  const tags = item.ImageTags ?? {};
  const logo = (id?: string, tag?: string) =>
    id && tag ? imageUrl(id, "Logo", { tag, maxWidth: Math.round(w * 0.4), maxHeight: Math.round(h * 0.35) }) : null;
  const fill = (id: string, type: string, tag?: string, index?: number) => imageUrl(id, type, { tag, index, fillWidth: w, fillHeight: h, quality: 85 });

  if (item.Type === "Episode" && item.SeriesId) {
    const seriesLogo = logo(item.ParentLogoItemId ?? item.SeriesId, item.ParentLogoImageTag);
    if (tags.Primary && isEpisodeStillUsable(item)) return { baseUrl: fill(item.Id, "Primary", tags.Primary), titled: false, logoUrl: seriesLogo };
    if (item.ParentThumbItemId && item.ParentThumbImageTag && isThumbGood(item.ParentThumbItemId))
      return { baseUrl: fill(item.ParentThumbItemId, "Thumb", item.ParentThumbImageTag), titled: true, logoUrl: null };
    const bt = item.ParentBackdropImageTags ?? [];
    if (item.ParentBackdropItemId && bt.length) {
      const idx = resolveBackdropIndex(bt, item.ParentBackdropItemId, bestBackdropIndex(item.ParentBackdropItemId));
      return { baseUrl: fill(item.ParentBackdropItemId, "Backdrop", bt[idx], idx), titled: false, logoUrl: seriesLogo };
    }
    return { baseUrl: null, titled: false, logoUrl: seriesLogo };
  }
  if (item.Type === "Season" && item.SeriesId) {
    if (item.ParentThumbItemId && item.ParentThumbImageTag)
      return { baseUrl: fill(item.ParentThumbItemId, "Thumb", item.ParentThumbImageTag), titled: true, logoUrl: null };
  }
  if (tags.Thumb && isThumbGood(item.Id)) return { baseUrl: fill(item.Id, "Thumb", tags.Thumb), titled: true, logoUrl: null };
  const lg = tags.Logo ? logo(item.Id, tags.Logo) : null;
  const bt = item.BackdropImageTags ?? [];
  if (bt.length) {
    const idx = resolveBackdropIndex(bt, item.Id, bestBackdropIndex(item.Id));
    return { baseUrl: fill(item.Id, "Backdrop", bt[idx], idx), titled: false, logoUrl: lg };
  }
  if (tags.Primary) return { baseUrl: fill(item.Id, "Primary", tags.Primary), titled: false, logoUrl: lg };
  return { baseUrl: null, titled: false, logoUrl: lg };
}

/** Backdrop index for the home hero (details=false) or the details page. */
function pickIndex(item: Item, companion: number | null, details: boolean): number {
  const tags = backdropTags(item);
  const count = tags.length;
  if (count <= 1) return 0;
  const id = heroItemId(item);
  const card = cardDrawsBackdrop(item) ? resolveBackdropIndex(tags, id, bestBackdropIndex(id)) : -1;
  if (companion != null) {
    const wanted = resolveBackdropIndex(tags, id, companion);
    if (wanted !== card) return wanted;
  }
  const avoid = new Set([card]);
  if (details) {
    const home = peekBackdropInfo(id)?.heroIndex;
    const homeIdx = home != null ? resolveBackdropIndex(tags, id, home) : null;
    avoid.add(homeIdx != null && homeIdx !== card ? homeIdx : [...Array(count).keys()].find((i) => i !== card)!);
  }
  for (let i = 0; i < count; i++) if (!avoid.has(i)) return i;
  return 0;
}

export interface ImagePick {
  url: string | null;
  focal: Focal | null;
}

export function heroPick(item: Item, details: boolean): ImagePick {
  const tags = backdropTags(item);
  if (tags.length === 0) return { url: null, focal: null };
  const id = heroItemId(item);
  const info = peekBackdropInfo(id);
  const i = pickIndex(item, (details ? info?.detailsIndex : info?.heroIndex) ?? null, details);
  return { url: imageUrl(id, "Backdrop", { tag: tags[i], index: i, maxWidth: HERO_MAX_WIDTH, quality: 90 }), focal: info?.focal.get(i) ?? null };
}

/**
 * CSS object-position keeping the focal point in frame for an object-fit: cover image: horizontally proportional,
 * vertically at 40% of the container but never cropping into the head (same maths as the TV focalAlignment).
 */
export function focalPosition(focal: Focal | null, cw: number, ch: number, iw: number, ih: number): string {
  if (!focal || !cw || !ch || !iw || !ih) return "50% 30%";
  const scale = Math.max(cw / iw, ch / ih);
  const sw = iw * scale;
  const sh = ih * scale;
  const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);
  const x = clamp(focal.x * (cw - sw), Math.min(cw - sw, 0), 0);
  let y = 0;
  if (focal.y >= 0.25) {
    const headroom = focal.face ? 0.14 : 0.2;
    const desired = 0.4 * ch - focal.y * sh;
    const limit = -(Math.max(focal.y - headroom, 0) * sh);
    y = clamp(Math.max(desired, limit), Math.min(ch - sh, 0), 0);
  }
  return `${Math.round(x)}px ${Math.round(y)}px`;
}

export function focalOrigin(focal: Focal | null): string {
  return focal ? `${Math.min(Math.max(focal.x, 0.2), 0.8) * 100}% 40%` : "50% 30%";
}
