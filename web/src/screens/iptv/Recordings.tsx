// Recordings: storage against the cap, then each recording with play / cancel / delete.
import { useCallback, useEffect, useState } from "react";
import { cancelRecording, deleteRecording, errorText, recordings as getRecordings, type Answer, type Recording, type Space } from "../../api/iptv";
import { targetPath } from "../../lib/iptvPlay";
import { go } from "../../lib/router";

const STATE: Record<Recording["state"], string> = {
  scheduled: "Scheduled",
  recording: "Recording now",
  done: "Recorded",
  failed: "Failed",
  cancelled: "Cancelled",
};

const when = (start: number, stop: number) => {
  const a = new Date(start * 1000);
  const b = new Date(stop * 1000);
  const day = a.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
  const t = (d: Date) => d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return `${day} ${t(a)}–${t(b)}`;
};
const gb = (bytes?: number) => (bytes ? `${(bytes / 1e9).toFixed(bytes > 1e10 ? 0 : 1)} GB` : null);

export function Recordings() {
  const [data, setData] = useState<{ space: Space; recordings: Recording[] } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Recording["id"] | null>(null);

  const load = useCallback(async () => {
    const a = await getRecordings();
    if (a.status === 200 && a.data) {
      setData(a.data);
      setErr(null);
    } else setErr(errorText(a));
  }, []);
  useEffect(() => {
    load();
  }, [load]);
  // A recording in progress grows: refresh now and then
  const live = !!data?.recordings.some((r) => r.state === "recording");
  useEffect(() => {
    if (!live) return;
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, [live, load]);

  const act = async (fn: () => Promise<Answer<unknown>>) => {
    const a = await fn();
    if (a.status !== 200) setErr(errorText(a));
    setConfirm(null);
    load();
  };

  const space = data?.space;
  const pct = space ? Math.min(100, Math.round((space.usedGb / Math.max(space.capGb, 1)) * 100)) : 0;
  return (
    <section className="iptv-card">
      <h2>Recordings</h2>
      {space && (
        <div className="iptv-space">
          <span className="iptv-bar" aria-hidden>
            <span style={{ width: `${pct}%` }} className={pct >= 90 ? "full" : ""} />
          </span>
          <span className="iptv-hint">
            {space.usedGb.toFixed(1)} of {space.capGb} GB used
            {space.freeGb != null ? ` · ${Math.round(space.freeGb)} GB free on the server` : ""}
            {!space.canRecord && space.reason ? ` · ${space.reason}` : ""}
          </span>
        </div>
      )}
      {err && <p className="iptv-err">{err}</p>}
      {data && data.recordings.length === 0 && <p className="iptv-hint">Nothing recorded yet. Record a programme from the guide.</p>}
      <ul className="iptv-recs">
        {data?.recordings.map((r) => (
          <li key={r.id} className={`iptv-rec ${r.state}`}>
            <div className="iptv-rec-text">
              <strong>{r.title}</strong>
              <span className="iptv-hint">
                {[r.channel, when(r.start, r.stop), STATE[r.state], gb(r.bytes), r.watched ? "watched" : null].filter(Boolean).join(" · ")}
              </span>
              {r.error && <span className="iptv-hint warn">{r.error}</span>}
            </div>
            <div className="buttons">
              {r.playable && (
                <button className="btn small primary" data-nav onClick={() => go(`${targetPath({ recording: String(r.id) })}&t=${encodeURIComponent(r.title)}`)}>
                  {r.state === "recording" ? "Watch from start" : r.progress && !r.progress.played ? "Resume" : "Play"}
                </button>
              )}
              {(r.state === "scheduled" || r.state === "recording") && (
                <button className="btn small" data-nav onClick={() => act(() => cancelRecording(r.id))}>
                  {r.state === "recording" ? "Stop" : "Cancel"}
                </button>
              )}
              {confirm === r.id ? (
                <button className="btn small danger" data-nav data-autofocus onClick={() => act(() => deleteRecording(r.id))}>
                  Delete for good
                </button>
              ) : (
                <button className="btn small" data-nav onClick={() => setConfirm(r.id)}>
                  Delete
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
