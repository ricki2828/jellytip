// Seek bar for the players. Dragging only moves the thumb and shows the time under it; the seek happens once, on release
// (a seek per touch move restarted the transcode at every pixel, and timeupdate kept pulling the thumb back). After the
// release the thumb stays where it was dropped until playback reaches it, so it never snaps back to the old position.
import { useEffect, useRef, useState } from "react";

const LAND_MS = 6000;

export function Scrubber({ time, max, onSeek, onActivity, fmt }: { time: number; max: number; onSeek: (to: number) => void; onActivity?: () => void; fmt: (s: number) => string }) {
  const [drag, setDrag] = useState<number | null>(null);
  const [landing, setLanding] = useState<number | null>(null);
  const down = useRef(false);
  const timer = useRef<number | undefined>(undefined);

  // The seek arrived (playback is near the drop point): follow the clock again
  useEffect(() => {
    if (landing != null && Math.abs(time - landing) < 3) setLanding(null);
  }, [time, landing]);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  const commit = (v: number) => {
    setDrag(null);
    setLanding(v);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setLanding(null), LAND_MS);
    onSeek(v);
  };
  const end = () => {
    down.current = false;
    if (drag != null) commit(drag);
  };
  const cap = max > 0 ? max : 0;
  const shown = Math.min(drag ?? landing ?? time, cap);
  const pct = cap ? (shown / cap) * 100 : 0;
  return (
    <div className="seek-wrap">
      {drag != null && (
        <span className="seek-tip" style={{ left: `${Math.min(94, Math.max(6, pct))}%` }} aria-hidden>
          {fmt(drag)}
        </span>
      )}
      <input
        className="seek"
        type="range"
        min={0}
        max={cap}
        step={1}
        value={shown}
        onPointerDown={() => (down.current = true)}
        onChange={(e) => {
          const v = Number(e.target.value);
          onActivity?.();
          // Mouse / touch drag: preview only. Keyboard (arrow keys on the focused bar): seek straight away.
          if (down.current) setDrag(v);
          else commit(v);
        }}
        onPointerUp={end}
        onPointerCancel={end}
        onBlur={end}
        style={{ ["--p" as string]: `${pct}%` }}
        aria-label="Seek"
        aria-valuetext={fmt(shown)}
      />
    </div>
  );
}
