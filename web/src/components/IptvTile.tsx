// An IPTV film / series tile: same shapes as the library tiles (16:9 backdrop card or 2:3 poster) plus a small corner
// dot, the one cue that it comes from the person's IPTV provider. Opens the IPTV details (#/iptv/item?id=…).
import { useState } from "react";
import type { Way } from "../lib/availability";
import { logoSrc } from "../api/iptv";
import { vodPath, vodTech, type IptvPart, type VodItem } from "../lib/iptvVod";
import { tileBadges } from "../lib/techBadges";
import { TechBadges } from "./TechBadges";
import { useIsPhone } from "../lib/device";
import { go } from "../lib/router";
import { announceIptvFocus } from "../lib/iptvFocus";
import { CARD_W, PHONE_CARD_W } from "./Card";
import { PosterCard } from "./PosterCard";
import { useTileMenu, type IptvMenuSubject } from "./TasteButtons";
import { tmdbSized } from "./FranchiseRow";

type Tile = Pick<VodItem, "id" | "title" | "year" | "quality" | "poster" | "backdrop"> & { kind?: VodItem["kind"]; tmdbId?: number | null; thumb?: VodItem["thumb"]; notInterested?: boolean } & { tech?: string[]; progress?: VodItem["progress"]; played?: boolean };

/** What the tile menu needs (thumbs + watched); the kind falls back to the id's `:s:` / `:m:` marker. */
const menuSubject = (t: Tile & { played?: boolean; progress?: VodItem["progress"] }): IptvMenuSubject => ({
  id: t.id,
  title: t.title,
  kind: t.kind ?? (t.id.includes(":s:") ? "series" : "movie"),
  tmdbId: t.tmdbId,
  played: !!t.played || !!t.progress?.played,
  thumb: t.thumb,
  notInterested: t.notInterested,
});

const label = (t: Tile) => `${t.title}${t.year ? ` (${t.year})` : ""} · via IPTV`;

/** TMDB art, or provider art through the companion's image proxy; raw provider URLs never load (CSP, privacy). */
export const safeArt = (u: string | null | undefined, size?: 96 | 192 | 480 | 960) => (u && /^https:\/\/image\.tmdb\.org\//.test(u) ? u : logoSrc(u, size));

/** Tells the Home hero which IPTV title the person is on (a no-op where nothing listens). */
const announce = (t: Pick<VodItem, "id" | "title" | "year" | "poster" | "backdrop">) =>
  announceIptvFocus({ id: t.id, title: t.title, year: t.year, art: safeArt(t.backdrop ?? t.poster, 960) });

export function IptvDot() {
  return <span className="iptv-corner-dot" aria-hidden title="via IPTV" />;
}

export function IptvPoster({ item, width, tag, avail, autoFocus }: { item: Tile | IptvPart; width: number; tag?: string; avail?: Way["badge"]; autoFocus?: boolean }) {
  return (
    <span className="iptv-tile-wrap" style={width ? { width } : undefined}>
      <PosterCard
        width={width}
        src={safeArt(item.poster ?? item.backdrop, 480)}
        name={item.title}
        label={label(item as Tile)}
        tag={tag}
        avail={avail}
        tech={vodTech(item)}
        autoFocus={autoFocus}
        watched={!!(item as Tile).played || !!(item as Tile).progress?.played}
        iptvMenu={menuSubject(item as Tile)}
        onFocus={() => announce(item)}
        onOpen={() => go(vodPath(item.id))}
      />
      <IptvDot />
    </span>
  );
}

export function IptvCard({ item, width: widthProp, tag, autoFocus, onOpen }: { item: Tile; width?: number; tag?: string; autoFocus?: boolean; onOpen?: () => void }) {
  const phone = useIsPhone();
  const width = widthProp ?? (phone ? PHONE_CARD_W : CARD_W);
  const art = tmdbSized(safeArt(item.backdrop ?? item.poster, 480), "w780");
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const tech = vodTech(item);
  const p = item.progress;
  const menu = useTileMenu(undefined, menuSubject(item));
  const pct = p && !p.played && p.duration ? Math.min(100, (p.position / p.duration) * 100) : 0;
  return (
    <button
      className={`card iptv-tile-card${width === 0 ? " fluid" : ""}${tileBadges(tech).length ? ` has-tech${tileBadges(tech).length > 1 ? " has-tech-2" : ""}` : ""}`}
      data-nav
      data-autofocus={autoFocus || undefined}
      style={width === 0 ? undefined : { width, height: Math.round((width * 9) / 16) }}
      title={label(item)}
      aria-label={label(item)}
      onClick={() => !menu.swallowClick() && (onOpen ? onOpen() : go(vodPath(item.id)))}
      onFocus={() => announce(item)}
      onMouseEnter={() => announce(item)}
      {...menu.props}
    >
      {art && !failed && <img className={`card-img${loaded ? " in" : ""}`} src={art} alt="" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
      <div className="card-fade" />
      <span className="card-title">{item.title}</span>
      {tag && <span className="card-tag">{tag}</span>}
      <TechBadges tech={tech} />
      {(p?.played || item.played) && (
        <span className="card-watched" aria-label="Watched">
          ✓
        </span>
      )}
      {pct > 0 && (
        <span className="card-progress">
          <span style={{ width: `${pct}%` }} />
        </span>
      )}
      <IptvDot />
    </button>
  );
}
