// Library queries for the personal channels (lib/channels.ts has the schedule maths). One query per channel (two for an
// episode channel: matching series, then all episodes, filtered here), bare Fields. The result is kept in memory and in
// sessionStorage for a while, per person, so reopening the screen asks Jellyfin nothing. The queries run as the signed-in
// user, so their parental rating limits and library access apply without any code here.
import { getItems, uid } from "./jellyfin";
import { playable, sample, sampleByGroup, yearsParam, type ChanItem, type ChannelDef } from "../lib/channels";

const TTL_MS = 6 * 3600 * 1000;
/** Most items a channel keeps (a deterministic hash sample of what the library has). */
const CAP = 150;
const PER_SERIES = 15;
const EPISODE_CAP = 300;

interface Entry {
  at: number;
  items: ChanItem[];
}
const mem = new Map<string, Entry>();
const inflight = new Map<string, Promise<ChanItem[]>>();
const keyFor = (def: ChannelDef) => `jellytip.channel.v2.${uid()}.${def.id}`;

const slim = (i: ChanItem): ChanItem => ({ Id: i.Id, Name: i.Name, Type: i.Type, SeriesName: i.SeriesName, SeriesId: i.SeriesId, ParentIndexNumber: i.ParentIndexNumber, IndexNumber: i.IndexNumber, ProductionYear: i.ProductionYear, RunTimeTicks: i.RunTimeTicks });

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

// No Limit and no SortBy (a cut-off would be a name-ordered slice); no extra Fields, no images, no user data
const common = { Recursive: true, EnableUserData: false, EnableImages: false, Fields: "", IsMissing: false } as const;

async function fetchItems(def: ChannelDef): Promise<ChanItem[]> {
  const genres = def.genres?.length ? def.genres.join("|") : undefined;
  if (def.type === "Movie") {
    const r = await getItems({ ...common, IncludeItemTypes: "Movie", Genres: genres, Years: yearsParam(def) });
    return sample(playable(r.Items.map(slim), def), CAP, def.id);
  }
  // Genres sit on the series, and this server ignores AncestorIds: find the matching series, take the episodes (they carry
  // their SeriesId) and keep those of that set, a few per series
  const s = await getItems({ ...common, IncludeItemTypes: "Series", Genres: genres });
  const ids = new Set(s.Items.map((x) => x.Id));
  if (!ids.size) return [];
  const r = await getItems({ ...common, IncludeItemTypes: "Episode" });
  const mine = playable(r.Items.map(slim), def).filter((e) => e.SeriesId && ids.has(e.SeriesId));
  return sampleByGroup(mine, (e) => e.SeriesId, PER_SERIES, EPISODE_CAP, def.id);
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
