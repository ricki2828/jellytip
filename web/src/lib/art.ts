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
/** The companion checked the Thumb and found no title in it (unknown = titled, as before). */
const isThumbUntitled = (id: string) => peekBackdropInfo(id)?.thumbTitled === false;

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
    const thumb = item.ParentThumbItemId ?? item.SeriesId;
    const titled = !!item.ParentThumbImageTag && isThumbGood(thumb);
    return !titled && (!item.ImageTags?.Primary || !isEpisodeStillUsable(item));
  }
  return !item.ImageTags?.Thumb || !isThumbGood(item.Id);
}

/** One way to draw a tile. Layers are tried in order; a failed image moves the tile to the next one. */
export interface CardLayer {
  kind: "titled" | "image" | "poster" | "logo" | "text";
  /** Base image (titled Thumb, still/backdrop, or the poster for "poster"). */
  url: string | null;
  /** Clearlogo drawn bottom-left on untitled art, or centred for "logo". */
  logoUrl: string | null;
  /** Blurred fill behind a letterboxed poster. */
  blurUrl?: string | null;
}

/**
 * Tile images are requested at one canonical size so every screen (rows, grid, search) shares the server's resize cache:
 * the seedbox resizes on first request, which is what makes cold tiles slow.
 */
export const TILE_W = 528;
export const TILE_H = 297;

/**
 * Fallback chain (TV rules first, then never blank):
 * Thumb (title baked in) → still/backdrop + clearlogo (or title text) → poster letterboxed on a blurred backdrop/poster
 * → clearlogo on dark → title text on dark. Episodes: own still + series logo → series Thumb → series backdrop.
 * A Thumb known to have no title in it (companion thumbTitled=false, or `untitled` from a specials payload) gets the
 * title text drawn over it.
 */
export function cardLayers(item: Item, opts?: { untitled?: boolean }): CardLayer[] {
  const w = TILE_W;
  const h = TILE_H;
  const tags = item.ImageTags ?? {};
  const logo = (id?: string, tag?: string) => (id && tag ? imageUrl(id, "Logo", { tag, maxWidth: 400, maxHeight: 160 }) : null);
  const fill = (id: string, type: string, tag?: string, index?: number) => imageUrl(id, type, { tag, index, fillWidth: w, fillHeight: h, quality: 85 });
  const out: CardLayer[] = [];
  // Untitled Thumb: an "image" layer with no logo, so the tile draws the title text over it (the TV look)
  const thumb = (id: string, tag: string): CardLayer =>
    ({ kind: opts?.untitled || isThumbUntitled(id) ? "image" : "titled", url: fill(id, "Thumb", tag), logoUrl: null });

  if (item.Type === "Episode" && item.SeriesId) {
    const seriesLogo = logo(item.ParentLogoItemId ?? item.SeriesId, item.ParentLogoImageTag);
    const thumbId = item.ParentThumbItemId ?? item.SeriesId;
    if (tags.Primary && isEpisodeStillUsable(item)) out.push({ kind: "image", url: fill(item.Id, "Primary", tags.Primary), logoUrl: seriesLogo });
    if (item.ParentThumbImageTag && isThumbGood(thumbId)) out.push(thumb(thumbId, item.ParentThumbImageTag));
    const bt = item.ParentBackdropImageTags ?? [];
    const bid = item.ParentBackdropItemId ?? item.SeriesId;
    if (bt.length) {
      const idx = resolveBackdropIndex(bt, bid, bestBackdropIndex(bid));
      out.push({ kind: "image", url: fill(bid, "Backdrop", bt[idx], idx), logoUrl: seriesLogo });
    }
    if (tags.Primary && !isEpisodeStillUsable(item)) out.push({ kind: "image", url: fill(item.Id, "Primary", tags.Primary), logoUrl: seriesLogo });
    if (seriesLogo) out.push({ kind: "logo", url: null, logoUrl: seriesLogo });
    out.push({ kind: "text", url: null, logoUrl: null });
    return out;
  }

  const ownLogo = tags.Logo ? logo(item.Id, tags.Logo) : item.ParentLogoImageTag ? logo(item.ParentLogoItemId ?? item.SeriesId, item.ParentLogoImageTag) : null;
  if (item.Type === "Season" && item.SeriesId && item.ParentThumbImageTag)
    out.push(thumb(item.ParentThumbItemId ?? item.SeriesId, item.ParentThumbImageTag));
  if (tags.Thumb && isThumbGood(item.Id)) out.push(thumb(item.Id, tags.Thumb));
  const bt = item.BackdropImageTags ?? [];
  const idx = bt.length ? resolveBackdropIndex(bt, item.Id, bestBackdropIndex(item.Id)) : -1;
  const backdrop = idx >= 0 ? fill(item.Id, "Backdrop", bt[idx], idx) : null;
  if (backdrop) out.push({ kind: "image", url: backdrop, logoUrl: ownLogo });
  if (tags.Primary) {
    const poster = imageUrl(item.Id, "Primary", { tag: tags.Primary, maxHeight: h, quality: 85 });
    out.push({ kind: "poster", url: poster, logoUrl: null, blurUrl: backdrop ?? poster });
  }
  // A Thumb the companion scored as poor is still better than no picture at all
  if (tags.Thumb && !isThumbGood(item.Id)) out.push(thumb(item.Id, tags.Thumb));
  if (ownLogo) out.push({ kind: "logo", url: null, logoUrl: ownLogo });
  out.push({ kind: "text", url: null, logoUrl: null });
  return out;
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

/**
 * Still for a special on the home page (trailer break panel, tonight's pick): never the hero's image (heroIndex) and,
 * where there is a choice, not the tile's either. Prefers the companion's detailsIndex (its #2).
 */
export function specialPick(item: Item): ImagePick {
  const tags = backdropTags(item);
  if (tags.length === 0) return { url: null, focal: null };
  const id = heroItemId(item);
  const info = peekBackdropInfo(id);
  const hero = resolveBackdropIndex(tags, id, info?.heroIndex ?? 0);
  const card = cardDrawsBackdrop(item) ? resolveBackdropIndex(tags, id, bestBackdropIndex(id)) : -1;
  const order = [info?.detailsIndex != null ? resolveBackdropIndex(tags, id, info.detailsIndex) : -1, ...tags.keys()];
  const i = order.find((n) => n >= 0 && n !== hero && n !== card) ?? order.find((n) => n >= 0 && n !== hero) ?? 0;
  return backdropAt(id, tags, i, info);
}

function backdropAt(id: string, tags: string[], i: number, info: ReturnType<typeof peekBackdropInfo>): ImagePick {
  const width = Math.min(HERO_MAX_WIDTH, Math.round((window.innerWidth * Math.min(window.devicePixelRatio || 1, 2)) / 160) * 160 || HERO_MAX_WIDTH);
  return { url: imageUrl(id, "Backdrop", { tag: tags[i], index: i, maxWidth: width, quality: 85 }), focal: info?.focal.get(i) ?? null };
}

export function heroPick(item: Item, details: boolean): ImagePick {
  const tags = backdropTags(item);
  if (tags.length === 0) return { url: null, focal: null };
  const id = heroItemId(item);
  const info = peekBackdropInfo(id);
  const i = pickIndex(item, (details ? info?.detailsIndex : info?.heroIndex) ?? null, details);
  return backdropAt(id, tags, i, info);
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
