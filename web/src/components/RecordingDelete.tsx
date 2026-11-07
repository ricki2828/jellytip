// Delete the recording that is open in the player: two taps (Delete, then "Delete for good"), then back to Recordings.
import { useState } from "react";
import { deleteRecording, errorText } from "../api/iptv";
import { flashNote } from "./TasteButtons";
import { go } from "../lib/router";

export function RecordingDelete({ id }: { id: string }) {
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    const a = await deleteRecording(id);
    if (a.status === 200) {
      flashNote("Recording deleted");
      go("/recordings", true);
    } else {
      flashNote(errorText(a));
      setBusy(false);
      setConfirm(false);
    }
  };
  return confirm ? (
    <button className="btn small danger" data-nav data-autofocus disabled={busy} onClick={() => void run()} onBlur={() => !busy && setConfirm(false)}>
      Delete for good
    </button>
  ) : (
    <button className="btn small danger" data-nav onClick={() => setConfirm(true)}>
      Delete recording
    </button>
  );
}
