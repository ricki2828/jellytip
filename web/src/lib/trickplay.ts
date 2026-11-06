// Jellyfin trickplay (10.9+): the item carries Trickplay[mediaSourceId][width] = tile-sheet metadata, and the sheets are
// GET /Videos/{id}/Trickplay/{width}/{index}.jpg. Thumbnail i covers time i*Interval (ms); sheets are TileWidth x TileHeight grids.
export interface TrickplayInfo {
  Width: number;
  Height: number;
  TileWidth: number;
  TileHeight: number;
  ThumbnailCount: number;
  Interval: number; // ms between thumbnails
}
export type TrickplayMap = Record<string, Record<string, TrickplayInfo>> | undefined | null;

const valid = (i: TrickplayInfo | undefined): i is TrickplayInfo =>
  !!i && i.Width > 0 && i.Height > 0 && i.TileWidth > 0 && i.TileHeight > 0 && i.ThumbnailCount > 0 && i.Interval > 0;

/** The widest set not wider than maxWidth (else the narrowest available), or null if the source has none. Never invents data. */
export function pickTrickplay(map: TrickplayMap, sourceId: string, maxWidth = 480): { width: number; info: TrickplayInfo } | null {
  const sets = map?.[sourceId];
  if (!sets) return null;
  const all = Object.entries(sets)
    .map(([w, info]) => ({ width: Number(w), info }))
    .filter((e) => Number.isFinite(e.width) && valid(e.info))
    .sort((a, b) => a.width - b.width);
  if (!all.length) return null;
  return [...all].reverse().find((e) => e.width <= maxWidth) ?? all[0];
}

export interface TileLoc {
  sheet: number; // {index} in the URL
  x: number; // px offset of the thumbnail inside the sheet
  y: number;
  w: number;
  h: number;
  cols: number;
  rows: number;
}

export function tileLocation(info: TrickplayInfo, seconds: number): TileLoc {
  const per = info.TileWidth * info.TileHeight;
  const raw = Math.floor((Math.max(0, seconds) * 1000) / info.Interval);
  const n = Math.min(raw, info.ThumbnailCount - 1);
  const inSheet = n % per;
  return {
    sheet: Math.floor(n / per),
    x: (inSheet % info.TileWidth) * info.Width,
    y: Math.floor(inSheet / info.TileWidth) * info.Height,
    w: info.Width,
    h: info.Height,
    cols: info.TileWidth,
    rows: info.TileHeight,
  };
}

export function tileUrl(base: string, itemId: string, width: number, sheet: number, sourceId: string, token: string): string {
  const q = new URLSearchParams({ MediaSourceId: sourceId, api_key: token });
  return `${base}/Videos/${itemId}/Trickplay/${width}/${sheet}.jpg?${q}`;
}
