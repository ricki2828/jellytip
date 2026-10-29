// Portrait tile for a person (2:3, Jellyfin person Primary image): leads person rows and the person spotlight, opens
// the filmography. Name + count on a soft scrim; the name on dark when there is no picture (or it fails).
import { useState } from "react";
import { imageUrl } from "../api/jellyfin";
import { go } from "../lib/router";
import "./PersonTile.css";

export function PersonTile({ id, name, imageTag, width, sub }: { id: string; name: string; imageTag?: string | null; width: number; sub?: string }) {
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const img = imageTag && !failed ? imageUrl(id, "Primary", { tag: imageTag, fillWidth: Math.round(width * 2), fillHeight: Math.round(width * 3), quality: 85 }) : null;
  return (
    <button
      className="person-tile"
      style={{ width }}
      data-nav
      onClick={() => go(`/person/${id}?name=${encodeURIComponent(name)}`)}
      aria-label={`${name}: all films`}
      title={name}
    >
      {img && <img className={loaded ? "in" : undefined} src={img} alt="" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
      {!img && <span className="person-initial">{name.slice(0, 1)}</span>}
      <span className="person-tile-text">
        <strong>{name}</strong>
        {sub && <span>{sub}</span>}
      </span>
    </button>
  );
}

export const filmCount = (n: number) => `${n} ${n === 1 ? "film" : "films"}`;
