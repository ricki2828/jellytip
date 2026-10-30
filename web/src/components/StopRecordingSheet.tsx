// Sheet for a recording that is running: stop it now (the file keeps what's recorded) or leave it. Autofocus is on the safe button.
import { useState } from "react";
import { cancelRecording, errorText } from "../api/iptv";
import { clock, endsAtOf } from "../lib/holders";
import type { Rec } from "../lib/recs";
import "../screens/iptv/iptv.css";

export function StopRecordingSheet({ rec, onClose, onStopped, onWatch }: { rec: Rec; onClose: () => void; onStopped: () => void; onWatch?: () => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const stop = async () => {
    setBusy(true);
    const a = await cancelRecording(rec.id);
    if (a.status === 200) {
      onStopped();
      onClose();
    } else {
      setErr(errorText(a));
      setBusy(false);
    }
  };
  return (
    <div className="livetv-sheet-back" onClick={onClose}>
      <div
        className="livetv-sheet"
        role="dialog"
        aria-label={rec.title}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape" || e.key === "Backspace") {
            e.preventDefault();
            e.stopPropagation();
            onClose();
          }
        }}
      >
        <p className="iptv-hint">
          {rec.channel} · ● Recording now, ends {clock(endsAtOf(rec))}
        </p>
        <h2>{rec.title}</h2>
        {err && <p className="iptv-err">{err}</p>}
        <div className="buttons">
          <button className="btn" data-nav data-autofocus onClick={onClose}>
            Keep recording
          </button>
          <button className="btn primary" data-nav disabled={busy} onClick={() => void stop()}>
            Stop recording now (keeps what's recorded)
          </button>
          {onWatch && (
            <button className="btn" data-nav onClick={onWatch}>
              Watch from start
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
