// An IPTV film / series tile: same shapes as the library tiles (16:9 backdrop card or 2:3 poster) plus a small corner
// dot, the one cue that it comes from the person's IPTV provider. Opens the IPTV details (#/iptv/item?id=…).
import { useState } from "react";
import { qualityLabel, vodPath, type IptvPart, type VodItem } from "../lib/iptvVod";
import { useIsPhone } from "../lib/device";
import { go } from "../lib/router";
import { CARD_W, PHONE_CARD_W } from "./Card";
import { PosterCard } from "./PosterCard";
import { tmdbSized } from "./FranchiseRow";

type Tile = Pick<VodItem, "id" | "title" | "year" | "quality" | "poster" | "backdrop"> & { progress?: VodItem["progress"] };

const label = (t: Tile) => `${t.title}${t.year ? ` (${t.year})` : ""} · via IPTV`;

/** TMDB art only: provider-hosted art is blocked by the CSP and would hand the viewer's address to the panel. */
const safeArt = (u: string | null | undefined) => (u && /^https:\/\/image\.tmdb\.org\//.test(u) ? u : null);

export function IptvDot() {
  return <span className="iptv-corner-dot" aria-hidden title="via IPTV" />;
}

export function IptvPoster({ item, width, tag, autoFocus }: { item: Tile | IptvPart; width: number; tag?: string; autoFocus?: boolean }) {
  return (
    <span className="iptv-tile-wrap" style={width ? { width } : undefined}>
      <PosterCard
        width={width}
        src={safeArt(item.poster)}
        name={item.title}
        label={label(item as Tile)}
        tag={tag}
        autoFocus={autoFocus}
        onOpen={() => go(vodPath(item.id))}
      />
      <IptvDot />
    </span>
  );
}

export function IptvCard({ item, width: widthProp, tag, autoFocus }: { item: Tile; width?: number; tag?: string; autoFocus?: boolean }) {
  const phone = useIsPhone();
  const width = widthProp ?? (phone ? PHONE_CARD_W : CARD_W);
  const art = tmdbSized(safeArt(item.backdrop), "w780");
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const q = qualityLabel(item.quality);
  const p = item.progress;
  const pct = p && !p.played && p.duration ? Math.min(100, (p.position / p.duration) * 100) : 0;
  return (
    <button
      className="card iptv-card"
      data-nav
      data-autofocus={autoFocus || undefined}
      style={{ width, height: Math.round((width * 9) / 16) }}
      title={label(item)}
      aria-label={label(item)}
      onClick={() => go(vodPath(item.id))}
    >
      {art && !failed && <img className={`card-img${loaded ? " in" : ""}`} src={art} alt="" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
      <div className="card-fade" />
      <span className="card-title">{item.title}</span>
      {tag && <span className="card-tag">{tag}</span>}
      {q && <span className="iptv-quality">{q}</span>}
      {p?.played && (
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
