import { useState } from "react";
import { imageUrl, type Item } from "../api/jellyfin";
import { TechBadges } from "./TechBadges";
import { useTileMenu } from "./TasteButtons";
import { techOf, tileBadges } from "../lib/techBadges";
import "./PosterCard.css";

/**
 * 2:3 poster tile. Primary posters carry their title, so nothing is drawn below or over them; the title sits on the
 * dark tile until the picture loads (never blank), stays when there is no picture, and is drawn over art known to have
 * no title in it (`untitled`).
 */
export function PosterCard({ item, width = 150, onOpen, onFocus, autoFocus, faded, lit, src, label, name: nameProp, untitled, tag: chip, tech: techProp }: {
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
  /** Premium formats (defaults to what the server said for `item`). */
  tech?: readonly string[];
}) {
  const tag = item?.ImageTags?.Primary;
  const w = width || 160; // 0 = fluid (grid cell sets the size)
  const url = src ?? (item && tag ? imageUrl(item.Id, "Primary", { tag, fillWidth: Math.round(w * 2), fillHeight: Math.round(w * 3), quality: 85 }) : null);
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const name = nameProp ?? item?.Name ?? label ?? "";
  const hasImg = !!url && !failed;
  const overlay = hasImg && loaded && untitled;
  const menu = useTileMenu(item);
  const tech = techProp ?? (item && item.Type !== "Episode" ? techOf(item.Id) : []);
  const techShown = tileBadges(tech).length > 0;
  const cls = `poster${faded ? " faded" : ""}${lit ? " lit" : ""}${techShown ? " has-tech" : ""}`;
  const inner = (
    <>
      {hasImg && <img className={loaded ? "in" : undefined} src={url} alt="" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
      {overlay && <span className="poster-fade" />}
      {(!hasImg || !loaded || overlay) && <span className={`poster-title${hasImg && !loaded ? " pending" : ""}`}>{name}</span>}
      {chip && <span className="card-tag">{chip}</span>}
      {techShown && <TechBadges tech={tech} />}
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
      onClick={() => !menu.swallowClick() && onOpen()}
      {...menu.props}
      onFocus={onFocus}
      onMouseEnter={onFocus}
    >
      {inner}
    </button>
  );
}
