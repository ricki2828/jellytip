import { useState } from "react";
import { imageUrl, type Item } from "../api/jellyfin";
import "./PosterCard.css";

/**
 * 2:3 poster tile. Primary posters carry their title, so nothing is drawn below or over them; the title sits on the
 * dark tile until the picture loads (never blank), stays when there is no picture, and is drawn over art known to have
 * no title in it (`untitled`).
 */
export function PosterCard({ item, width = 150, onOpen, onFocus, autoFocus, faded, lit, src, label, name: nameProp, untitled, tag: chip }: {
  item?: Item;
  width?: number;
  onOpen?: () => void;
  /** Focus / hover (the home hero follows the tile). */
  onFocus?: () => void;
  autoFocus?: boolean;
  faded?: boolean;
  lit?: boolean;
  src?: string | null;
  /** Accessible name / tooltip (defaults to the title). */
  label?: string;
  /** Title drawn on the tile (defaults to the item name). */
  name?: string;
  untitled?: boolean;
  /** Small corner chip (franchise part number). */
  tag?: string;
}) {
  const tag = item?.ImageTags?.Primary;
  const w = width || 160; // 0 = fluid (grid cell sets the size)
  const url = src ?? (item && tag ? imageUrl(item.Id, "Primary", { tag, fillWidth: Math.round(w * 2), fillHeight: Math.round(w * 3), quality: 85 }) : null);
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const name = nameProp ?? item?.Name ?? label ?? "";
  const hasImg = !!url && !failed;
  const overlay = hasImg && loaded && untitled;
  const cls = `poster${faded ? " faded" : ""}${lit ? " lit" : ""}`;
  const inner = (
    <>
      {hasImg && <img className={loaded ? "in" : undefined} src={url} alt="" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
      {overlay && <span className="poster-fade" />}
      {(!hasImg || !loaded || overlay) && <span className={`poster-title${hasImg && !loaded ? " pending" : ""}`}>{name}</span>}
      {chip && <span className="card-tag">{chip}</span>}
      {item?.UserData?.Played && <span className="card-watched">✓</span>}
    </>
  );
  const aria = label ?? name;
  if (!onOpen)
    return (
      <div className={cls} style={width ? { width } : undefined} aria-label={aria} title={aria}>
        {inner}
      </div>
    );
  return (
    <button
      className={cls}
      style={width ? { width } : undefined}
      data-nav
      data-autofocus={autoFocus || undefined}
      aria-label={aria}
      title={aria}
      onClick={onOpen}
      onFocus={onFocus}
      onMouseEnter={onFocus}
    >
      {inner}
    </button>
  );
}
