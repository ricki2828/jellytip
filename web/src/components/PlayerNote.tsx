// flashNote() messages while the player is up. The app-level note host is not mounted on the player route (and would sit
// outside the fullscreen element anyway), so the player renders its own inside its root.
import { useEffect, useState } from "react";

export function PlayerNote() {
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    let t: number | undefined;
    const onNote = (e: Event) => {
      setNote((e as CustomEvent<string>).detail);
      window.clearTimeout(t);
      t = window.setTimeout(() => setNote(null), 2600);
    };
    window.addEventListener("jellytip-note", onNote);
    return () => {
      window.clearTimeout(t);
      window.removeEventListener("jellytip-note", onNote);
    };
  }, []);
  return note ? <p className="player-note" role="status">{note}</p> : null;
}
