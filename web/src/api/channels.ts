// Library queries for the personal channels (lib/channels.ts has the schedule maths). One query per channel (two for an
// episode channel: matching series, then their episodes), limited Fields. The result is kept in memory and in
// sessionStorage for a while, per person, so reopening the screen asks Jellyfin nothing. The queries run as the signed-in
// user, so their parental rating limits and library access apply without any code here.
import { getItems, uid } from "./jellyfin";
import { yearsParam, type ChanItem, type ChannelDef } from "../lib/channels";

const TTL_MS = 6 * 3600 * 1000;
const LIMIT = 500;
const SERIES_LIMIT = 25;
const FIELDS = "PrimaryImageAspectRatio";

interface Entry {
  at: number;
  items: ChanItem[];
}
const mem = new Map<string, Entry>();
const inflight = new Map<string, Promise<ChanItem[]>>();
const keyFor = (def: ChannelDef) => `jellytip.channel.${uid()}.${def.id}`;

const slim = (i: ChanItem): ChanItem => ({ Id: i.Id, Name: i.Name, Type: i.Type, SeriesName: i.SeriesName, ParentIndexNumber: i.ParentIndexNumber, IndexNumber: i.IndexNumber, ProductionYear: i.ProductionYear, RunTimeTicks: i.RunTimeTicks });

function read(key: string): ChanItem[] | null {
  const now = Date.now();
  const m = mem.get(key);
  if (m && now - m.at < TTL_MS) return m.items;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const e = JSON.parse(raw) as Entry;
    if (!Array.isArray(e.items) || typeof e.at !== "number" || now - e.at >= TTL_MS) return null;
    mem.set(key, e);
    return e.items;
  } catch {
    return null;
  }
}

function write(key: string, items: ChanItem[]) {
  const e = { at: Date.now(), items };
  mem.set(key, e);
  try {
    sessionStorage.setItem(key, JSON.stringify(e));
  } catch {
    // storage full or unavailable: memory only
  }
}

const common = { Recursive: true, EnableUserData: false, Fields: FIELDS, IsMissing: false, SortBy: "SortName", SortOrder: "Ascending" } as const;

async function fetchItems(def: ChannelDef): Promise<ChanItem[]> {
  const genres = def.genres?.length ? def.genres.join("|") : undefined;
  if (def.type === "Movie") {
    const r = await getItems({ ...common, IncludeItemTypes: "Movie", Genres: genres, Years: yearsParam(def), Limit: LIMIT });
    return r.Items.map(slim);
  }
  // Genres sit on the series, not its episodes: find the series, then their episodes in one query
  const s = await getItems({ ...common, IncludeItemTypes: "Series", Genres: genres, Limit: SERIES_LIMIT });
  if (!s.Items.length) return [];
  const r = await getItems({ ...common, IncludeItemTypes: "Episode", AncestorIds: s.Items.map((x) => x.Id).join(","), SortBy: "SeriesSortName,ParentIndexNumber,IndexNumber", Limit: LIMIT });
  return r.Items.map(slim);
}

/** The channel's candidate items (cached ~6 h per person). Rejects when Jellyfin can't be reached. */
export function channelItems(def: ChannelDef, fresh = false): Promise<ChanItem[]> {
  const key = keyFor(def);
  if (!fresh) {
    const hit = read(key);
    if (hit) return Promise.resolve(hit);
  }
  const running = inflight.get(key);
  if (running) return running;
  const p = fetchItems(def)
    .then((items) => {
      write(key, items);
      return items;
    })
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}
