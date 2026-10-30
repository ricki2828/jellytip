// Pop-up for a refused play (409 max_connections): a recording holds the stream (offer to stop it) or someone else is watching.
import { useState } from "react";
import { cancelRecording, errorText } from "../api/iptv";
import { blockerText, canStop, type Blocker } from "../lib/holders";
import { refreshRecs } from "../api/recStore";
import { SheetClose } from "./BackArrow";
import "../screens/iptv/iptv.css";

const WAIT_MS = 3000;

/** onRetry resolves true when the retried play worked; the pop-up then closes itself via onClose. */
export function StreamBusy({ blocker, onRetry, onClose }: { blocker: Blocker; onRetry: () => Promise<boolean>; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const stoppable = canStop(blocker);
  const stopAndWatch = async () => {
    if (!canStop(blocker)) return;
    setBusy(true);
    setErr(null);
    const a = await cancelRecording(blocker.recordingId);
    if (a.status !== 200) {
      setErr(errorText(a));
      setBusy(false);
      return;
    }
    void refreshRecs(true);
    await new Promise((r) => setTimeout(r, WAIT_MS));
    if (!(await onRetry())) setBusy(false);
  };
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape" || e.key === "Backspace") {
      e.preventDefault();
      e.stopPropagation();
      if (!busy) onClose();
    }
  };
  return (
    <div className="livetv-sheet-back" onClick={() => !busy && onClose()}>
      <div className="livetv-sheet" role="alertdialog" aria-label="Stream in use" onClick={(e) => e.stopPropagation()} onKeyDown={onKeyDown}>
        {!busy && <SheetClose onClose={onClose} />}
        <h2>{blockerText(blocker)}</h2>
        {busy && <p className="iptv-hint">Stopping the recording and freeing the stream…</p>}
        {err && <p className="iptv-err">{err}</p>}
        <div className="buttons">
          {stoppable ? (
            <>
              <button className="btn" data-nav data-autofocus disabled={busy} onClick={onClose}>
                Keep recording
              </button>
              <button className="btn primary" data-nav disabled={busy} onClick={() => void stopAndWatch()}>
                Stop recording &amp; watch
              </button>
            </>
          ) : (
            <button className="btn" data-nav data-autofocus onClick={onClose}>
              Close
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
