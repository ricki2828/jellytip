// An episode tile drawn from a still URL (IPTV), in the library episode tile's design (components/Card with a caption):
// 16:9, the caption on a fade, ✓ when watched, a progress bar. The still falls back once to the show art, then the dark tile.
import { useState } from "react";

export function EpisodeStill({
  src,
  fallback,
  width,
  caption,
  label,
  played,
  pct,
  dataEp,
  showWatched = true,
  onClick,
}: {
  src: string | null;
  fallback: string | null;
  width: number;
  caption: string;
  label: string;
  played: boolean;
  /** Progress bar width in percent; 0 = none. */
  pct: number;
  dataEp?: string;
  /** Off when the page draws its own watched toggle in that corner. */
  showWatched?: boolean;
  onClick: () => void;
}) {
  const chain = [src, fallback].filter((u, i, a): u is string => !!u && a.indexOf(u) === i);
  const [failed, setFailed] = useState(0);
  const [loaded, setLoaded] = useState<string | null>(null);
  const url = chain[failed] ?? null;
  return (
    <button className="card" data-nav data-ep={dataEp} style={{ width, height: Math.round((width * 9) / 16) }} title={label} aria-label={label} onClick={onClick}>
      {url && (
        <img
          key={url}
          className={`card-img${loaded === url ? " in" : ""}`}
          src={url}
          alt=""
          loading="lazy"
          decoding="async"
          onLoad={() => setLoaded(url)}
          onError={() => setFailed((n) => n + 1)}
        />
      )}
      <div className="card-fade" />
      <span className="card-title">{caption}</span>
      {played && showWatched && (
        <span className="card-watched" aria-label="Watched">
          ✓
        </span>
      )}
      {pct > 0 && (
        <span className="card-progress">
          <span style={{ width: `${pct}%` }} />
        </span>
      )}
    </button>
  );
}
