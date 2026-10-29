// Downloads (phone app): everything saved for offline, grouped movies / shows, rendered from local data only so it
// works in airplane mode. Settings: quality for new downloads, Wi-Fi only, storage used.
import { useEffect, useState } from "react";
import {
  blobUrl,
  deleteDownload,
  formatBytes,
  getQuality,
  getWifiOnly,
  isActive,
  listDownloads,
  retryDownload,
  setQuality,
  setWifiOnly,
  storageInfo,
  subscribe,
  type DownloadRecord,
  type Quality,
} from "../lib/downloads";
import { go } from "../lib/router";

function useBlob(key?: string): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let u: string | null = null;
    let alive = true;
    blobUrl(key).then((x) => {
      u = x;
      if (alive) setUrl(x);
      else if (x) URL.revokeObjectURL(x);
    });
    return () => {
      alive = false;
      if (u) URL.revokeObjectURL(u);
    };
  }, [key]);
  return url;
}

function status(r: DownloadRecord): string {
  const size = formatBytes(r.bytesTotal > 0 ? r.bytesTotal : r.bytesDone);
  switch (r.state) {
    case "done":
      return [size, r.quality === "1080" ? "1080p" : "720p"].filter(Boolean).join("  ·  ");
    case "preparing":
      return `Preparing on the server${r.prepareProgress != null ? ` ${Math.round(r.prepareProgress * 100)}%` : ""}`;
    case "failed":
      return r.error ?? "Failed";
    case "paused":
      return r.error ?? "Paused";
    default:
      return r.bytesTotal > 0 ? `${formatBytes(r.bytesDone)} of ${size}` : r.bytesDone > 0 ? `${formatBytes(r.bytesDone)}${r.source === "transcode" ? " (converting live)" : ""}` : "Waiting to start";
  }
}

function Entry({ r, label }: { r: DownloadRecord; label: string }) {
  const img = useBlob(r.images.tile);
  const frac = r.bytesTotal > 0 ? r.bytesDone / r.bytesTotal : r.state === "preparing" ? r.prepareProgress ?? 0 : 0;
  const [confirm, setConfirm] = useState(false);
  return (
    <div className="dl-row">
      <button className="dl-art" onClick={() => r.state === "done" && go(`/play/${r.itemId}`)} aria-label={`Play ${label}`} disabled={r.state !== "done"}>
        {img ? <img src={img} alt="" /> : <span className="dl-art-title">{label}</span>}
        {r.state === "done" && <span className="dl-play">▶</span>}
        {isActive(r) && (
          <span className="card-progress">
            <span style={{ width: `${Math.max(2, frac * 100)}%` }} />
          </span>
        )}
      </button>
      <div className="dl-text">
        <span className="dl-name">{label}</span>
        <span className={`dl-status${r.state === "failed" ? " bad" : ""}`}>{status(r)}</span>
        <span className="dl-actions">
          {r.state === "failed" && (
            <button className="chip" data-nav onClick={() => retryDownload(r.itemId)}>
              Retry
            </button>
          )}
          {confirm ? (
            <>
              <button className="chip on" data-nav onClick={() => deleteDownload(r.itemId)}>
                Delete
              </button>
              <button className="chip" data-nav onClick={() => setConfirm(false)}>
                Keep
              </button>
            </>
          ) : (
            <button className="chip" data-nav onClick={() => setConfirm(true)}>
              {isActive(r) ? "Cancel" : "Delete"}
            </button>
          )}
        </span>
      </div>
    </div>
  );
}

export function Downloads() {
  const [items, setItems] = useState<DownloadRecord[] | null>(null);
  const [quality, setQ] = useState<Quality>(getQuality);
  const [wifi, setWifi] = useState(getWifiOnly);
  const [space, setSpace] = useState<{ usedBytes: number; freeBytes: number } | null>(null);
  const [online, setOnline] = useState(navigator.onLine);

  useEffect(() => {
    const load = () => {
      listDownloads().then((all) => setItems(all.sort((a, b) => b.addedAt - a.addedAt)));
      storageInfo().then(setSpace, () => undefined);
    };
    load();
    const on = () => setOnline(navigator.onLine);
    window.addEventListener("online", on);
    window.addEventListener("offline", on);
    const unsub = subscribe(load);
    return () => {
      unsub();
      window.removeEventListener("online", on);
      window.removeEventListener("offline", on);
    };
  }, []);

  const movies = (items ?? []).filter((r) => r.item.Type !== "Episode");
  const shows = new Map<string, DownloadRecord[]>();
  for (const r of items ?? []) {
    if (r.item.Type !== "Episode") continue;
    const k = r.item.SeriesName ?? "Episodes";
    shows.set(k, [...(shows.get(k) ?? []), r]);
  }
  for (const list of shows.values()) list.sort((a, b) => (a.item.ParentIndexNumber ?? 0) - (b.item.ParentIndexNumber ?? 0) || (a.item.IndexNumber ?? 0) - (b.item.IndexNumber ?? 0));

  return (
    <div className="downloads">
      <h1 className="page-title">Downloads</h1>
      {!online && <p className="dl-offline">You're offline. Downloaded titles still play; watch progress syncs when you're back online.</p>}
      <div className="dl-settings">
        <div className="chips">
          {(["720", "1080"] as Quality[]).map((q) => (
            <button
              key={q}
              data-nav
              className={`chip${quality === q ? " on" : ""}`}
              onClick={() => {
                setQuality(q);
                setQ(q);
              }}
            >
              {q === "720" ? "Standard 720p" : "High 1080p"}
            </button>
          ))}
          <button
            data-nav
            className={`chip${wifi ? " on" : ""}`}
            onClick={() => {
              setWifiOnly(!wifi);
              setWifi(!wifi);
            }}
          >
            Wi-Fi only
          </button>
        </div>
        {space && (
          <p className="dl-space">
            {formatBytes(space.usedBytes) || "0 MB"} used  ·  {formatBytes(space.freeBytes)} free on this phone
          </p>
        )}
        <p className="dl-hint">Quality and Wi-Fi only apply to new downloads.</p>
      </div>
      {items && items.length === 0 && <p className="empty">Nothing downloaded yet. Use Download on a movie or episode.</p>}
      {movies.length > 0 && (
        <section className="dl-group">
          <h2 className="row-title">Movies</h2>
          {movies.map((r) => (
            <Entry key={r.itemId} r={r} label={r.item.Name} />
          ))}
        </section>
      )}
      {[...shows.entries()].map(([name, list]) => (
        <section key={name} className="dl-group">
          <h2 className="row-title">{name}</h2>
          {list.map((r) => (
            <Entry key={r.itemId} r={r} label={`S${r.item.ParentIndexNumber} E${r.item.IndexNumber} · ${r.item.Name}`} />
          ))}
        </section>
      ))}
    </div>
  );
}
