// Seek bar for the players. Dragging only moves the thumb and shows the time under it; the seek happens once, on release
// (a seek per touch move restarted the transcode at every pixel, and timeupdate kept pulling the thumb back). After the
// release the thumb stays where it was dropped until playback reaches it, so it never snaps back to the old position.
import { useEffect, useRef, useState } from "react";
import { tileLocation, type TrickplayInfo } from "../lib/trickplay";
import { chapterAt, type ChapterMark } from "../lib/chapters";

/** Jellyfin trickplay source: tile metadata + the URL of a sheet. Absent = time-only tooltip (never a made-up picture). */
export interface ScrubThumbs {
  info: TrickplayInfo;
  url: (sheet: number) => string;
}

const LAND_MS = 6000;

export function Scrubber({
  time,
  max,
  onSeek,
  onActivity,
  fmt,
  thumbs,
  chapters,
  pending,
}: {
  time: number;
  max: number;
  onSeek: (to: number) => void;
  onActivity?: () => void;
  fmt: (s: number) => string;
  thumbs?: ScrubThumbs | null;
  chapters?: ChapterMark[];
  /** A keyboard/remote seek waiting to be committed: previewed like a hover. */
  pending?: number | null;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
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
  const shown = Math.min(drag ?? pending ?? landing ?? time, cap);
  const pct = cap ? (shown / cap) * 100 : 0;
  const tipAt = drag ?? pending ?? hover;
  const tipT = tipAt != null ? Math.min(Math.max(tipAt, 0), cap) : null;
  const tipPct = tipT != null && cap ? (tipT / cap) * 100 : 0;
  const loc = tipT != null && thumbs ? tileLocation(thumbs.info, tipT) : null;
  const chap = tipT != null && chapters?.length ? chapterAt(chapters, tipT) : null;
  const fromPointer = (e: React.PointerEvent<HTMLInputElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return r.width && cap ? Math.min(cap, Math.max(0, ((e.clientX - r.left) / r.width) * cap)) : 0;
  };
  return (
    <div className="seek-wrap">
      {tipT != null && (
        <span className={`seek-tip${loc ? " has-thumb" : ""}`} style={{ left: `${Math.min(94, Math.max(6, tipPct))}%` }} aria-hidden>
          {loc && thumbs && (
            <span
              className="seek-thumb"
              style={{
                width: loc.w,
                height: loc.h,
                backgroundImage: `url("${thumbs.url(loc.sheet)}")`,
                backgroundSize: `${loc.w * loc.cols}px ${loc.h * loc.rows}px`,
                backgroundPosition: `-${loc.x}px -${loc.y}px`,
              }}
            />
          )}
          {chap?.name && <span className="seek-chap">{chap.name}</span>}
          <span>{fmt(tipT)}</span>
        </span>
      )}
      {cap > 0 &&
        chapters?.map((c) => (
          <i key={c.at} className="seek-tick" style={{ left: `${(c.at / cap) * 100}%` }} aria-hidden />
        ))}
      <input
        className="seek"
        type="range"
        min={0}
        max={cap}
        step={1}
        value={shown}
        onPointerDown={() => (down.current = true)}
        onPointerMove={(e) => e.pointerType === "mouse" && !down.current && setHover(fromPointer(e))}
        onPointerLeave={() => setHover(null)}
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
