// Offline downloads (phone app only). The file transfer itself runs in Android's DownloadManager (DownloadsPlugin.java),
// so it survives the app closing; this module picks the source, keeps a record per item in IndexedDB (with images and
// text subtitles, so the Downloads screen and the player work in airplane mode), and tracks progress.
//
// Source per item, best first:
//  1. original file: already phone-friendly (MP4 container, H.264, AAC/MP3, at most 1080p): static, resumable;
//  2. prepared copy: the companion transcodes a phone MP4 on the server, then it downloads like (1) (needs the
//     companion downloads module; see web/server/README.md);
//  3. live transcode: Jellyfin transcodes while we download. Not resumable and only as fast as the transcode, so
//     only used when (2) isn't available.

import { authHeader, deviceId, getItem, imageUrl, jf, JF_BASE, qs, uid, type Item, type MediaStream } from "../api/jellyfin";
import { COMPANION_BASE } from "../api/companion";
import { getSession } from "../api/jellyfin";
import { isNative, nativeGetJson, nativePostJson, NativeDownloads, type NativeDownloadStatus } from "./native";
import { idbAll, idbDelete, idbGet, idbPut } from "./offlineStore";
import { cardLayers, heroItemId, HERO_MAX_WIDTH } from "./art";

export type Quality = "720" | "1080";
export type Source = "original" | "prepared" | "transcode";
export type DownloadState = "preparing" | "queued" | "downloading" | "paused" | "done" | "failed";

export interface OfflineSubtitle {
  index: number;
  label: string;
  key: string; // blobs store: VTT text
}

export interface DownloadRecord {
  itemId: string;
  item: Item;
  quality: Quality;
  source: Source;
  state: DownloadState;
  downloadId?: number;
  path?: string;
  jobId?: string;
  prepareProgress?: number;
  bytesDone: number;
  bytesTotal: number;
  error?: string;
  addedAt: number;
  /** blobs-store keys: tile art, backdrop, logo */
  images: { tile?: string; backdrop?: string; logo?: string };
  subtitles: OfflineSubtitle[];
}

const QUALITY_KEY = "jellytip.dlQuality";
const WIFI_KEY = "jellytip.dlWifiOnly";
const ACTIVE: DownloadState[] = ["preparing", "queued", "downloading", "paused"];
const PRESETS: Record<Quality, { w: number; h: number; vbr: number }> = {
  "720": { w: 1280, h: 720, vbr: 2_500_000 },
  "1080": { w: 1920, h: 1080, vbr: 5_000_000 },
};

export const downloadsAvailable = isNative;

export const getQuality = (): Quality => (localStorage.getItem(QUALITY_KEY) === "1080" ? "1080" : "720");
export const setQuality = (q: Quality) => localStorage.setItem(QUALITY_KEY, q);
export const getWifiOnly = () => localStorage.getItem(WIFI_KEY) !== "0"; // default on
export const setWifiOnly = (on: boolean) => localStorage.setItem(WIFI_KEY, on ? "1" : "0");

// ---------- change notifications ----------

const listeners = new Set<() => void>();
export function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  ensurePolling();
  return () => listeners.delete(fn);
}
const notify = () => listeners.forEach((l) => l());

// ---------- records ----------

export const listDownloads = () => idbAll<DownloadRecord>("downloads");
export const getDownload = (id: string) => idbGet<DownloadRecord>("downloads", id);
const save = async (r: DownloadRecord) => {
  await idbPut("downloads", r.itemId, r);
  notify();
};

export async function blobUrl(key?: string): Promise<string | null> {
  if (!key) return null;
  const b = await idbGet<Blob | string>("blobs", key);
  if (!b) return null;
  return typeof b === "string" ? null : URL.createObjectURL(b);
}

export const subtitleText = (key: string) => idbGet<string>("blobs", key);

// ---------- source choice ----------

function isPhoneFriendly(item: Item, quality: Quality): boolean {
  const src = item.MediaSources?.[0];
  const streams = src?.MediaStreams ?? [];
  const v = streams.find((s) => s.Type === "Video");
  const a = streams.find((s) => s.Type === "Audio" && s.Index === src?.DefaultAudioStreamIndex) ?? streams.find((s) => s.Type === "Audio");
  const container = (src?.Container ?? "").split(",");
  return (
    container.some((c) => c === "mp4" || c === "m4v" || c === "mov") &&
    v?.Codec === "h264" &&
    (v.Height ?? 9999) <= PRESETS[quality].h + 8 &&
    (!a || a.Codec === "aac" || a.Codec === "mp3")
  );
}

function liveTranscodeUrl(item: Item, quality: Quality): string {
  const p = PRESETS[quality];
  const src = item.MediaSources?.[0];
  return (
    `${JF_BASE}/Videos/${item.Id}/stream.mp4` +
    qs({
      Static: false,
      MediaSourceId: src?.Id,
      DeviceId: deviceId(),
      VideoCodec: "h264",
      AudioCodec: "aac",
      MaxWidth: p.w,
      MaxHeight: p.h,
      VideoBitrate: p.vbr,
      AudioBitrate: 160_000,
      MaxAudioChannels: 2,
      AudioStreamIndex: src?.DefaultAudioStreamIndex,
    })
  );
}

const companionHeaders = () => ({ Authorization: `MediaBrowser Token="${getSession()!.token}"` });

/** Ask the companion for a prepared copy; null when the server has no downloads module (or is unreachable). */
async function requestPrepared(itemId: string, quality: Quality): Promise<{ jobId: string } | null> {
  try {
    const r = await nativePostJson<{ jobId?: string }>(`${COMPANION_BASE}/v1/downloads/prepare`, companionHeaders(), { itemId, quality });
    return (r.status === 200 || r.status === 202) && r.data?.jobId ? { jobId: r.data.jobId } : null;
  } catch {
    return null;
  }
}

interface JobStatus {
  state: "queued" | "running" | "ready" | "failed";
  progress?: number;
  bytes?: number;
  fileUrl?: string;
  error?: string;
}

async function jobStatus(jobId: string): Promise<JobStatus | null> {
  try {
    const r = await nativeGetJson<JobStatus>(`${COMPANION_BASE}/v1/downloads/jobs/${jobId}`, companionHeaders(), 10_000);
    return r.status === 200 ? r.data : r.status === 404 ? { state: "failed", error: "The server lost the prepared copy" } : null;
  } catch {
    return null;
  }
}

// ---------- start / delete ----------

const fileName = (r: DownloadRecord) => `${r.itemId}-${r.quality}-${r.source}.mp4`;
const titleOf = (i: Item) => (i.Type === "Episode" ? `${i.SeriesName ?? ""} S${i.ParentIndexNumber}E${i.IndexNumber} ${i.Name}` : i.Name);

async function enqueueNative(r: DownloadRecord, url: string) {
  const res = await NativeDownloads.enqueue({ url, headers: { Authorization: authHeader() }, fileName: fileName(r), title: titleOf(r.item), wifiOnly: getWifiOnly() });
  r.downloadId = res.downloadId;
  r.path = res.path;
  r.state = "queued";
  r.error = undefined;
}

async function storeBlob(key: string, url: string | null): Promise<string | undefined> {
  if (!url) return undefined;
  try {
    const res = await fetch(url);
    if (!res.ok) return undefined;
    await idbPut("blobs", key, await res.blob());
    return key;
  } catch {
    return undefined;
  }
}

/** Tile art, backdrop and logo saved locally so the Downloads screen renders in airplane mode. */
async function saveImages(item: Item): Promise<DownloadRecord["images"]> {
  const tile = cardLayers(item).find((l) => l.url)?.url ?? null;
  const bid = heroItemId(item);
  const btags = (item.Type === "Episode" ? item.ParentBackdropImageTags : item.BackdropImageTags) ?? [];
  const backdrop = btags.length ? imageUrl(bid, "Backdrop", { tag: btags[0], index: 0, maxWidth: Math.min(HERO_MAX_WIDTH, 1280), quality: 80 }) : null;
  const logo =
    item.ImageTags?.Logo || item.ParentLogoImageTag
      ? imageUrl(item.ImageTags?.Logo ? item.Id : item.ParentLogoItemId ?? item.SeriesId!, "Logo", { tag: item.ImageTags?.Logo ?? item.ParentLogoImageTag, maxWidth: 600 })
      : null;
  const [t, b, l] = await Promise.all([storeBlob(`img:${item.Id}:tile`, tile), storeBlob(`img:${item.Id}:backdrop`, backdrop), storeBlob(`img:${item.Id}:logo`, logo)]);
  return { tile: t, backdrop: b, logo: l };
}

/** Text subtitles (default, forced and English first, at most 4) as VTT, for offline playback. */
async function saveSubtitles(item: Item): Promise<OfflineSubtitle[]> {
  const src = item.MediaSources?.[0];
  if (!src) return [];
  const text = (src.MediaStreams ?? []).filter((s) => s.Type === "Subtitle" && s.IsTextSubtitleStream);
  const rank = (s: MediaStream) => (s.Index === src.DefaultSubtitleStreamIndex ? 0 : s.IsForced ? 1 : (s.Language ?? "").startsWith("en") ? 2 : 3);
  const picked = [...text].sort((a, b) => rank(a) - rank(b)).slice(0, 4);
  const out: OfflineSubtitle[] = [];
  for (const s of picked) {
    try {
      const res = await fetch(`${JF_BASE}/Videos/${item.Id}/${src.Id}/Subtitles/${s.Index}/0/Stream.vtt`, { headers: { Authorization: authHeader() } });
      if (!res.ok) continue;
      const key = `sub:${item.Id}:${s.Index}`;
      await idbPut("blobs", key, await res.text());
      out.push({ index: s.Index, label: s.DisplayTitle || s.Language || `Track ${s.Index}`, key });
    } catch {
      /* skip this track */
    }
  }
  return out;
}

export async function startDownload(itemOrId: Item | string, quality: Quality = getQuality()): Promise<DownloadRecord> {
  const item = typeof itemOrId === "string" || !itemOrId.MediaSources ? await getItem(typeof itemOrId === "string" ? itemOrId : itemOrId.Id) : itemOrId;
  const existing = await getDownload(item.Id);
  if (existing && existing.state !== "failed") return existing;
  const r: DownloadRecord = {
    itemId: item.Id,
    item,
    quality,
    source: "original",
    state: "queued",
    bytesDone: 0,
    bytesTotal: 0,
    addedAt: Date.now(),
    images: {},
    subtitles: [],
  };
  if (isPhoneFriendly(item, quality)) {
    await enqueueNative(r, `${JF_BASE}/Videos/${item.Id}/stream.mp4${qs({ Static: true, MediaSourceId: item.MediaSources?.[0]?.Id })}`);
  } else {
    const job = await requestPrepared(item.Id, quality);
    if (job) {
      r.source = "prepared";
      r.state = "preparing";
      r.jobId = job.jobId;
    } else {
      r.source = "transcode";
      await enqueueNative(r, liveTranscodeUrl(item, quality));
    }
  }
  await save(r);
  // Artwork and subtitles in the background; the record is already visible
  Promise.all([saveImages(item), saveSubtitles(item)]).then(async ([images, subtitles]) => {
    const cur = await getDownload(item.Id);
    if (cur) await save({ ...cur, images, subtitles });
  });
  ensurePolling();
  return r;
}

export async function retryDownload(id: string) {
  const r = await getDownload(id);
  if (!r) return;
  if (r.downloadId != null) await NativeDownloads.remove({ ids: [r.downloadId], paths: r.path ? [r.path] : [] }).catch(() => undefined);
  await idbDelete("downloads", id);
  await startDownload(r.item.MediaSources ? r.item : id, r.quality);
}

export async function deleteDownload(id: string) {
  const r = await getDownload(id);
  if (!r) return;
  await NativeDownloads.remove({ ids: r.downloadId != null ? [r.downloadId] : [], paths: r.path ? [r.path] : [] }).catch(() => undefined);
  const keys = [r.images.tile, r.images.backdrop, r.images.logo, ...r.subtitles.map((s) => s.key)].filter((k): k is string => !!k);
  await Promise.all(keys.map((k) => idbDelete("blobs", k)));
  await idbDelete("downloads", id);
  notify();
}

export const storageInfo = () => NativeDownloads.storage();

/** Local file path of a finished download that is still on disk. */
export async function playableDownload(id: string): Promise<DownloadRecord | null> {
  if (!isNative) return null;
  const r = await getDownload(id);
  if (!r || r.state !== "done" || !r.path) return null;
  const ex = await NativeDownloads.exists({ path: r.path }).catch(() => ({ exists: false, bytes: 0 }));
  return ex.exists ? r : null;
}

// ---------- progress polling ----------

let polling = false;

async function pollOnce(): Promise<boolean> {
  const all = await listDownloads();
  const active = all.filter((r) => ACTIVE.includes(r.state));
  if (active.length === 0) return false;
  const ids = active.map((r) => r.downloadId).filter((x): x is number => x != null);
  const native = new Map<number, NativeDownloadStatus>();
  if (ids.length) (await NativeDownloads.query({ ids }).catch(() => ({ items: [] }))).items.forEach((s) => native.set(s.downloadId, s));
  for (const r of active) {
    const before = JSON.stringify([r.state, r.bytesDone, r.bytesTotal, r.prepareProgress, r.error]);
    if (r.state === "preparing" && r.jobId) {
      const st = navigator.onLine ? await jobStatus(r.jobId) : null;
      if (st?.state === "ready" && st.fileUrl) {
        try {
          await enqueueNative(r, `${COMPANION_BASE}/${st.fileUrl.replace(/^\//, "")}`);
        } catch (e) {
          r.state = "failed";
          r.error = String((e as Error).message ?? e);
        }
      } else if (st?.state === "failed") {
        r.state = "failed";
        r.error = st.error ?? "The server couldn't prepare this download";
      } else if (st) r.prepareProgress = st.progress;
    } else if (r.downloadId != null) {
      const s = native.get(r.downloadId);
      if (!s) {
        r.state = "failed";
        r.error = "The download was cancelled";
      } else {
        r.bytesDone = s.bytesDone;
        r.bytesTotal = s.bytesTotal;
        r.state = s.status === "done" ? "done" : s.status === "failed" ? "failed" : s.status === "running" ? "downloading" : s.status === "paused" ? "paused" : "queued";
        if (s.status === "failed") r.error = `Download failed (${s.reason})`;
        if (s.status === "paused") r.error = s.reason === 3 ? "Waiting for Wi-Fi" : s.reason === 2 ? "Waiting for network" : "Paused";
        else if (s.status !== "failed") r.error = undefined;
      }
    }
    if (JSON.stringify([r.state, r.bytesDone, r.bytesTotal, r.prepareProgress, r.error]) !== before) await save(r);
  }
  return true;
}

function ensurePolling() {
  if (polling || !isNative) return;
  polling = true;
  const tick = async () => {
    let more = false;
    try {
      more = await pollOnce();
    } catch {
      more = true;
    }
    if (more || listeners.size > 0) setTimeout(tick, more ? 2000 : 10_000);
    else polling = false;
  };
  tick();
}

// ---------- offline watch progress ----------

interface PendingProgress {
  itemId: string;
  positionTicks: number;
  played: boolean;
  at: string;
}

/** Remember a position watched offline (or on a local file) and push it to Jellyfin when possible. */
export async function recordProgress(itemId: string, positionTicks: number, runtimeTicks?: number) {
  const played = !!runtimeTicks && positionTicks > runtimeTicks * 0.9;
  await idbPut("progress", itemId, { itemId, positionTicks: played ? 0 : Math.round(positionTicks), played, at: new Date().toISOString() } satisfies PendingProgress);
  syncProgress();
}

/** Position recorded offline and not yet synced (it's newer than what Jellyfin knows). */
export async function localPosition(itemId: string): Promise<number | null> {
  const p = await idbGet<PendingProgress>("progress", itemId);
  return p ? p.positionTicks : null;
}

let syncing = false;
export async function syncProgress() {
  if (syncing || !navigator.onLine || !getSession()) return;
  syncing = true;
  try {
    for (const p of await idbAll<PendingProgress>("progress")) {
      try {
        await jf(`/UserItems/${p.itemId}/UserData${qs({ userId: uid() })}`, {
          method: "POST",
          json: { PlaybackPositionTicks: p.positionTicks, Played: p.played, LastPlayedDate: p.at },
        });
        const cur = await idbGet<PendingProgress>("progress", p.itemId);
        if (cur && cur.at === p.at) await idbDelete("progress", p.itemId); // a newer position may have landed meanwhile
      } catch {
        break; // offline / server down: try again on the next 'online'
      }
    }
  } finally {
    syncing = false;
  }
}

if (isNative) {
  window.addEventListener("online", () => {
    syncProgress();
    ensurePolling();
  });
  setTimeout(() => {
    syncProgress();
    ensurePolling();
  }, 1500);
}

export const isActive = (r?: DownloadRecord | null) => !!r && ACTIVE.includes(r.state);

export function formatBytes(n: number): string {
  if (!n || n < 0) return "";
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`;
  if (n >= 1e6) return `${Math.round(n / 1e6)} MB`;
  return `${Math.round(n / 1e3)} kB`;
}
