// Library + IPTV queries for the personal channels (lib/channels.ts has the schedule maths). One query per channel (two for an
// episode channel: matching series, then all episodes, filtered here), bare Fields. The result is kept in memory and in
// sessionStorage for a while, per person, so reopening the screen asks Jellyfin nothing. The queries run as the signed-in
// user, so their parental rating limits and library access apply without any code here.
import { getItems, uid } from "./jellyfin";
import { CHANNELS, chainBudget, chainTarget, channelById, getPersonalDefs, setPersonalDefs, rejoinLive, iptvPoolItems, iptvEpisodeItems, mixItems, mixEpisodes, parseHidden, playable, poolOf, sample, sampleByGroup, playedEnough, schedule, TICKS_PER_SECOND, tunePath, withinYearTo, yearsParam, type ChanItem, type ChannelDef } from "../lib/channels";
import { iptvGet } from "./iptv";
import { tasteProfileSoon } from "./tasteProfile";
import { affinity, type TasteProfile } from "../lib/tasteProfile";
import { personalItems } from "../lib/personalChannels";

const TTL_MS = 6 * 3600 * 1000;
/** While the server is still fetching runtimes for IPTV films, look again sooner. */
const PENDING_TTL_MS = 15 * 60 * 1000;
/** Most items a channel keeps (a deterministic hash sample of what the library has). */
const CAP = 150;
const PER_SERIES = 15;
const EPISODE_CAP = 300;

interface Entry {
  at: number;
  items: ChanItem[];
  /** Shorter life than TTL_MS (an answer the server says is still filling in). */
  ttl?: number;
}
const mem = new Map<string, Entry>();
const inflight = new Map<string, Promise<ChanItem[]>>();
// "p" = built with the person's taste profile (items carry an affinity), "n" = without: a profile turning up later gets fresh items
const keyFor = (u: string, def: ChannelDef, profiled: boolean) => `jellytip.channel.v6.${u}.${def.id}.${profiled ? "p" : "n"}`;

const slim = (i: ChanItem): ChanItem => ({ Id: i.Id, Name: i.Name, Type: i.Type, SeriesName: i.SeriesName, SeriesId: i.SeriesId, SeriesTmdb: i.SeriesTmdb, ParentIndexNumber: i.ParentIndexNumber, IndexNumber: i.IndexNumber, ProductionYear: i.ProductionYear, RunTimeTicks: i.RunTimeTicks, Tmdb: i.Tmdb, Art: i.Art, G: i.G, Aff: i.Aff });

function read(key: string): ChanItem[] | null {
  const now = Date.now();
  const m = mem.get(key);
  if (m && now - m.at < (m.ttl ?? TTL_MS)) return m.items;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const e = JSON.parse(raw) as Entry;
    if (!Array.isArray(e.items) || typeof e.at !== "number" || now - e.at >= (e.ttl ?? TTL_MS)) return null;
    mem.set(key, e);
    return e.items;
  } catch {
    return null;
  }
}

function write(key: string, items: ChanItem[], ttl?: number) {
  const e: Entry = { at: Date.now(), items, ...(ttl ? { ttl } : {}) };
  mem.set(key, e);
  try {
    sessionStorage.setItem(key, JSON.stringify(e));
  } catch {
    // storage full or unavailable: memory only
  }
}

// No Limit and no SortBy (a cut-off would be a name-ordered slice); no extra Fields, no images, no user data
const common = { Recursive: true, EnableUserData: false, EnableImages: false, Fields: "", IsMissing: false } as const;

// IPTV films (Movie channel) or episodes (Episode channel): ONE catalogue call (metadata only: the server never opens a stream or calls /play to answer it).
// A server without the route, or a person without IPTV, just means no IPTV films. Playing one is the normal IPTV player's job.
const noIptv = new Set<string>();
async function iptvPool(def: ChannelDef, u: string, prof: TasteProfile | null): Promise<{ items: ChanItem[]; pending: number }> {
  const r = await iptvPoolRaw(def, u);
  const kind = def.type === "Episode" ? "series" : "movie";
  // The channel's own genres stand in for the title's (that is what the query matched); the server's per-title affinity wins when it sends one
  return { ...r, items: r.items.map((i) => ({ ...i, G: def.genres, ...(prof && i.Aff == null ? { Aff: affinity(prof, { genres: def.genres, year: i.ProductionYear, kind }) } : {}) })) };
}
async function iptvPoolRaw(def: ChannelDef, u: string): Promise<{ items: ChanItem[]; pending: number }> {
  if (!def.tmdbGenres?.length || noIptv.has(u)) return { items: [], pending: 0 };
  const episodes = def.type === "Episode";
  const q = new URLSearchParams({ genres: def.tmdbGenres.join(","), limit: "200" });
  if (episodes) {
    q.set("kind", "episode");
    if (def.minMinutes) q.set("runtimeMin", String(def.minMinutes));
    if (def.maxMinutes) q.set("runtimeMax", String(Math.min(100, def.maxMinutes)));
  } else {
    if (def.yearFrom != null) q.set("yearFrom", String(def.yearFrom));
    if (def.yearTo != null) q.set("yearTo", String(def.yearTo));
  }
  try {
    const a = await iptvGet<unknown>(`v1/iptv/channel-pool?${q}`, 20_000);
    if (a.status === 404 || a.status === 501 || a.status === 401 || a.status === 403) noIptv.add(u);
    if (a.status === 200 && episodes) { const r = iptvEpisodeItems(a.data); return { items: withinYearTo(r.items, def), pending: r.pending }; }
    return a.status === 200 ? iptvPoolItems(a.data) : { items: [], pending: 0 };
  } catch {
    return { items: [], pending: 0 };
  }
}
window.addEventListener("jellytip-session", () => noIptv.clear());

interface Fetched {
  items: ChanItem[];
  pending: number;
}

async function fetchItems(def: ChannelDef, u: string, prof: TasteProfile | null): Promise<Fetched> {
  const genres = def.genres?.length ? def.genres.join("|") : undefined;
  if (def.type === "Movie") {
    // ProviderIds carries the TMDB id: the same film on IPTV is left out in favour of the library copy
    const [r, v] = await Promise.all([getItems({ ...common, Fields: "ProviderIds,Genres", IncludeItemTypes: "Movie", Genres: genres, Years: yearsParam(def) }), iptvPool(def, u, prof)]);
    const lib = sample(
      playable(
        r.Items.map((i) => slim({ ...i, Tmdb: i.ProviderIds?.Tmdb, G: i.Genres?.slice(0, 4), ...(prof ? { Aff: affinity(prof, { genres: i.Genres, year: i.ProductionYear, kind: "movie" }) } : {}) } as ChanItem)),
        def,
      ),
      CAP,
      def.id,
    );
    return { items: mixItems(lib, v.items), pending: v.pending };
  }
  // Genres sit on the series, and this server ignores AncestorIds: find the matching series, take the episodes (they carry
  // their SeriesId) and keep those of that set, a few per series. ProviderIds on the series lets an IPTV copy of the same show defer to it.
  const [s, v] = await Promise.all([getItems({ ...common, Fields: "ProviderIds,Genres", IncludeItemTypes: "Series", Genres: genres }), iptvPool(def, u, prof)]);
  const shows = withinYearTo(s.Items, def);
  const tmdb = new Map(shows.map((x) => [x.Id, x.ProviderIds?.Tmdb] as const));
  // Genres sit on the series: its episodes share its genres and its affinity
  const taste = new Map(shows.map((x) => [x.Id, { G: x.Genres?.slice(0, 4), ...(prof ? { Aff: affinity(prof, { genres: x.Genres, year: x.ProductionYear, kind: "series" }) } : {}) }] as const));
  let lib: ChanItem[] = [];
  if (tmdb.size) {
    const r = await getItems({ ...common, IncludeItemTypes: "Episode" });
    const mine = playable(r.Items.map(slim), def).filter((e) => e.SeriesId && tmdb.has(e.SeriesId));
    lib = sampleByGroup(mine, (e) => e.SeriesId, PER_SERIES, EPISODE_CAP, def.id).map((e) => ({ ...e, SeriesTmdb: tmdb.get(e.SeriesId!), ...taste.get(e.SeriesId!) }));
  }
  return { items: mixEpisodes(lib, v.items), pending: v.pending };
}

/** The channel's candidate items (cached ~6 h per person). Rejects when Jellyfin can't be reached. */
export async function channelItems(def: ChannelDef, fresh = false): Promise<ChanItem[]> {
  if (def.personal) return personalFor(def); // cut from the other channels' (cached) pools; nothing of its own to cache
  const u = uid(); // the person this request is for: a switch of account mid-fetch discards the answer
  const pa = await tasteProfileSoon();
  const key = keyFor(u, def, !!pa.profile);
  if (!fresh) {
    const hit = read(key);
    if (hit) return hit;
  }
  const running = inflight.get(key);
  if (running) return running;
  const p = fetchItems(def, u, pa.profile)
    .then(({ items, pending }) => {
      if (uid() !== u) throw new Error("account changed");
      // a slow companion (profile not known yet) or a still-filling pool: look again sooner
      write(key, items, pending > 0 || !pa.settled ? PENDING_TTL_MS : undefined);
      return items;
    })
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

// ---------- personal channels (built from the taste profile) ----------

async function personalFor(def: ChannelDef): Promise<ChanItem[]> {
  const base: Record<string, ChanItem[]> = {};
  await Promise.all(CHANNELS.map((c) => channelItems(c).then((l) => void (base[c.id] = l), () => undefined)));
  return personalItems(def, base, def.genres?.[0]);
}

const personalKey = () => `jellytip.channels.personal.v1.${uid()}`;
/** Keep the person's personal channel definitions, so a player opened on one (or after a reload) can look it up by id. */
export function rememberPersonal(defs: ChannelDef[]) {
  setPersonalDefs(defs);
  try {
    sessionStorage.setItem(personalKey(), JSON.stringify(getPersonalDefs()));
  } catch {
    // memory only
  }
}
function hydratePersonal() {
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(personalKey()) ?? "[]");
    if (Array.isArray(v)) setPersonalDefs(v.filter((d): d is ChannelDef => !!d && typeof d === "object" && typeof (d as ChannelDef).id === "string" && !!(d as ChannelDef).personal));
  } catch {
    // none
  }
}
hydratePersonal();
window.addEventListener("jellytip-session", () => {
  setPersonalDefs([]);
  hydratePersonal();
});

// ---------- hidden channels (per person), shared by #/channels and the Home row ----------

const hiddenKey = () => `jellytip.channels.hidden.${uid()}`;
export function readHiddenChannels(): string[] {
  try {
    return parseHidden(localStorage.getItem(hiddenKey()));
  } catch {
    return [];
  }
}
export function writeHiddenChannels(ids: string[]) {
  try {
    localStorage.setItem(hiddenKey(), JSON.stringify(ids));
  } catch {
    // private mode: lasts until reload
  }
}

const chainKey = () => `jellytip.channels.chain.${uid()}`;
/** Spend one automatic hop; false when too many happened lately (the caller stops and says so). */
export function takeChainHop(): boolean {
  let stamps: number[] = [];
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(chainKey()) ?? "[]");
    if (Array.isArray(v)) stamps = v.filter((x): x is number => typeof x === "number");
  } catch {
    // none
  }
  const r = chainBudget(stamps, Date.now());
  try {
    sessionStorage.setItem(chainKey(), JSON.stringify(r.stamps));
  } catch {
    // memory only: no budget this time
  }
  return r.ok;
}

/**
 * Where a channel's player goes when item `currentId` ends: what the clock says is on now, never the item that just ended,
 * and never when it ended after a few seconds of what should have been a long film, or when too many hops happened lately
 * (null: the caller leaves the player). `resumeS` is where this play started.
 */
export async function nextChannelPath(channelId: string, currentId?: string, playedS?: number, resumeS = 0): Promise<string | null> {
  const def = channelById(channelId);
  if (!def) return null;
  try {
    const pool = poolOf(await channelItems(def), def);
    const cur = currentId ? pool.find((i) => i.Id === currentId) : undefined;
    if (cur?.RunTimeTicks && playedS != null && !playedEnough(playedS, cur.RunTimeTicks / TICKS_PER_SECOND - resumeS)) return null;
    if (!takeChainHop()) return null;
    const sch = schedule(pool, def.id, Date.now(), 4);
    const t = chainTarget(sch.slots, sch.offsetS, currentId);
    return t ? tunePath(t, def.id) : null;
  } catch {
    return null;
  }
}

/** "Back to live" for a channel player that is off the clock: seek in place, or the path of what is on now. Takes no chain-hop budget. */
export async function rejoinPlan(channelId: string, currentId: string): Promise<{ kind: "seek"; s: number } | { kind: "go"; path: string } | null> {
  const def = channelById(channelId);
  if (!def) return null;
  try {
    const pool = poolOf(await channelItems(def), def);
    const sch = schedule(pool, def.id, Date.now(), 3);
    const r = rejoinLive(sch.slots, sch.offsetS, currentId);
    if (!r) return null;
    return r.kind === "seek" ? r : { kind: "go", path: tunePath(r.target, def.id) };
  } catch {
    return null;
  }
}
