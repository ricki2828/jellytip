// League/sport artwork behind Game Centre's hero and fixture tiles (Ricki 21-Nov), washed in the team colours. The overlay
// numbers and their contrast proof live in lib/artOverlay. No art, or art that fails to load, leaves the plain look.
import { useState } from "react";
import { logoSrc } from "../api/iptv";
import type { TeamColors } from "../lib/broadcast";
import { heroOverlay, tileOverlay } from "../lib/artOverlay";

/** Hosts the page's CSP allows for images (public CDNs); anything else must be a companion-proxied path. */
const CDN = /^https:\/\/(a\.espncdn\.com|[a-z0-9-]+\.thesportsdb\.com)\//i;
export const artSrc = (u: string | null | undefined, size: 96 | 192 | 480 | 960): string | null => (u ? (CDN.test(u) ? u : logoSrc(u, size)) : null);

/** The art URL to draw, or null once it has failed (so the caller can fall back to its plain look). */
export function useArt(u: string | null | undefined, size: 480 | 960): { src: string | null; failed: () => void } {
  const [bad, setBad] = useState<string | null>(null);
  const src = artSrc(u, size);
  return { src: src && src !== bad ? src : null, failed: () => setBad(src) };
}

/** Hero layers: the art full-bleed (a slow pan unless reduced motion), the team-colour gradient and the bottom scrim. */
export function HeroArt({ src, colours, onError }: { src: string; colours: TeamColors; onError: () => void }) {
  return (
    <>
      <img className="lh-art" src={src} alt="" decoding="async" onError={onError} />
      <span className="lh-overlay" style={{ background: heroOverlay(colours) }} aria-hidden />
    </>
  );
}

/** Tile layers: the art dimmed and blurred, a light colour wash; the card's ring is redrawn on top (.gc-artbg::after). */
export function TileArt({ src, colours, hasAway, onError }: { src: string; colours: TeamColors; hasAway: boolean; onError: () => void }) {
  const wash = tileOverlay(colours, hasAway);
  return (
    <span className="gc-artbg" aria-hidden>
      <img src={src} alt="" loading="lazy" decoding="async" onError={onError} />
      {wash && <span className="gc-wash" style={{ background: wash }} />}
    </span>
  );
}

/** The league's badge, small, beside its name; nothing when absent or failing. */
export function LeagueBadge({ path, className }: { path: string | null | undefined; className: string }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? null : artSrc(path, 96);
  return src ? <img className={className} src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} /> : null;
}
