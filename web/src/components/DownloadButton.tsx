// Download controls on the details page (phone app only). One item, or every episode of a season.
import { useEffect, useState } from "react";
import type { Item } from "../api/jellyfin";
import { downloadsAvailable, getDownload, isActive, listDownloads, startDownload, subscribe, type DownloadRecord } from "../lib/downloads";
import { go } from "../lib/router";

function pct(r: DownloadRecord): string {
  if (r.state === "waiting" || r.state === "serverQueued") return "Queued";
  if (r.state === "preparing") return r.prepareProgress != null ? `Preparing ${Math.round(r.prepareProgress * 100)}%` : "Preparing";
  if (r.bytesTotal > 0) return `${Math.floor((r.bytesDone / r.bytesTotal) * 100)}%`;
  return r.state === "paused" ? r.error ?? "Paused" : "Starting";
}

export function DownloadButton({ item, onError }: { item: Item; onError: (msg: string) => void }) {
  const [rec, setRec] = useState<DownloadRecord | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!downloadsAvailable) return;
    const load = () => getDownload(item.Id).then((r) => setRec(r ?? null));
    load();
    return subscribe(load);
  }, [item.Id]);
  if (!downloadsAvailable || rec === undefined) return null;
  if (rec && rec.state === "done")
    return (
      <button className="btn" data-nav onClick={() => go("/downloads")}>
        Downloaded ✓
      </button>
    );
  if (rec && isActive(rec))
    return (
      <button className="btn" data-nav onClick={() => go("/downloads")}>
        ↓ {pct(rec)}
      </button>
    );
  return (
    <button
      className="btn"
      data-nav
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        try {
          await startDownload(item);
        } catch (e) {
          onError(`Couldn't start the download: ${(e as Error).message ?? e}`);
        } finally {
          setBusy(false);
        }
      }}
    >
      {rec?.state === "failed" ? "Retry download" : "↓ Download"}
    </button>
  );
}

export function SeasonDownloadButton({ episodes, onError }: { episodes: Item[]; onError: (msg: string) => void }) {
  const [have, setHave] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!downloadsAvailable) return;
    const load = () => listDownloads().then((all) => setHave(new Set(all.filter((r) => r.state !== "failed").map((r) => r.itemId))));
    load();
    return subscribe(load);
  }, []);
  if (!downloadsAvailable || episodes.length === 0) return null;
  const missing = episodes.filter((e) => !have.has(e.Id));
  return (
    <button
      className="chip dl-season"
      data-nav
      disabled={busy || missing.length === 0}
      onClick={async () => {
        setBusy(true);
        let failed = 0;
        for (const e of missing) {
          try {
            await startDownload(e.Id);
          } catch {
            failed++;
          }
        }
        setBusy(false);
        if (failed) onError(`${failed} episode${failed > 1 ? "s" : ""} couldn't be queued.`);
      }}
    >
      {missing.length === 0 ? "Season downloaded" : busy ? "Queuing…" : `↓ Download season (${missing.length})`}
    </button>
  );
}
