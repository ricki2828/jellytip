// Landscape titled tile: titled Thumb art, else still/backdrop + clearlogo, else backdrop + title text.
// Nothing is drawn below the tile.
import { memo, useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { backdropInfo, episodeArtFor, peekBackdropInfo, peekEpisodeArt } from "../api/companion";
import { cardArt, heroItemId } from "../lib/art";
import { go } from "../lib/router";

export const CARD_W = 264;
export const CARD_H = Math.round((CARD_W * 9) / 16);
const RANK_WAIT_MS = 300;

function useVisible<T extends Element>(ref: React.RefObject<T | null>, margin = "300px"): boolean {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || visible) return;
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && setVisible(true), { rootMargin: margin });
    io.observe(el);
    return () => io.disconnect();
  }, [ref, margin, visible]);
  return visible;
}

/** Waits (briefly) for the companion's ranking so the first paint is usually the final image. */
function useCompanionReady(item: Item, visible: boolean): boolean {
  const rankedId = item.Type === "Episode" && item.SeriesId ? item.SeriesId : heroItemId(item);
  const known = () => !!peekBackdropInfo(rankedId) && (item.Type !== "Episode" || !item.SeriesId || !!peekEpisodeArt(item.SeriesId));
  const [ready, setReady] = useState(known);
  const [, bump] = useState(0);
  useEffect(() => {
    if (!visible || known()) return;
    let alive = true;
    const t = setTimeout(() => alive && setReady(true), RANK_WAIT_MS);
    const jobs: Promise<unknown>[] = [backdropInfo(rankedId)];
    if (item.Type === "Episode" && item.SeriesId) jobs.push(episodeArtFor(item.SeriesId));
    if (item.ParentThumbItemId && item.ParentThumbItemId !== rankedId) jobs.push(backdropInfo(item.ParentThumbItemId));
    Promise.all(jobs).then(() => {
      if (!alive) return;
      setReady(true);
      bump((n) => n + 1); // late answer: upgrade the art
    });
    return () => {
      alive = false;
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, rankedId]);
  return ready || !!item.ImageTags?.Thumb;
}

interface Props {
  item: Item;
  onFocusItem?: (item: Item) => void;
  width?: number;
  autoFocus?: boolean;
  /** Overrides the default (open details). */
  onOpen?: (item: Item) => void;
  label?: string;
  /** Overlay text drawn instead of the logo/title (episode lists: "3 · Episode name"). */
  caption?: string;
}

function CardImpl({ item, onFocusItem, width = CARD_W, autoFocus, onOpen, label, caption }: Props) {
  const ref = useRef<HTMLButtonElement>(null);
  const visible = useVisible(ref);
  const ready = useCompanionReady(item, visible);
  // width 0 = fluid (grid cells size it); images are requested at a typical cell size
  const fluid = width === 0;
  const imgW = fluid ? 340 : width;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const art = visible && ready ? cardArt(item, Math.round(imgW * dpr), Math.round(((imgW * 9) / 16) * dpr)) : null;
  const [baseFailed, setBaseFailed] = useState<string | null>(null);
  const [logoFailed, setLogoFailed] = useState<string | null>(null);
  const showLogo = !caption && art && !(art.titled && baseFailed !== art.baseUrl) && art.logoUrl && logoFailed !== art.logoUrl;
  const baseOk = !!art?.baseUrl && baseFailed !== art.baseUrl;
  // Titled art that failed to load still needs a name on the tile
  const titled = !!art?.titled && baseOk;
  const showText = art && !titled && !showLogo;
  const ud = item.UserData;
  const progress = ud?.PlayedPercentage && ud.PlayedPercentage > 0 && ud.PlayedPercentage < 100 ? ud.PlayedPercentage : 0;
  const title =
    label ??
    (item.Type === "Episode" ? `${item.SeriesName ?? ""}${item.SeriesName ? " · " : ""}S${item.ParentIndexNumber ?? "?"} E${item.IndexNumber ?? "?"} ${item.Name}` : item.Name);

  return (
    <button
      ref={ref}
      className={fluid ? "card fluid" : "card"}
      data-nav
      data-autofocus={autoFocus || undefined}
      style={fluid ? undefined : { width, height: Math.round((width * 9) / 16) }}
      title={title}
      aria-label={title}
      onFocus={() => onFocusItem?.(item)}
      onMouseEnter={() => onFocusItem?.(item)}
      onClick={() => (onOpen ? onOpen(item) : go(`/item/${item.Id}`))}
    >
      {baseOk && (
        <img className="card-img" src={art!.baseUrl!} alt="" loading="lazy" decoding="async" onError={() => setBaseFailed(art!.baseUrl)} />
      )}
      {art && (!titled || caption) && <div className="card-fade" />}
      {caption && titled && <span className="card-title">{caption}</span>}
      {showLogo && <img className="card-logo" src={art!.logoUrl!} alt="" onError={() => setLogoFailed(art!.logoUrl)} />}
      {showText && <span className="card-title">{caption ?? (item.Type === "Episode" ? item.SeriesName ?? item.Name : item.Name)}</span>}
      {!caption && item.Type === "Episode" && art && !titled && (
        <span className="card-ep">
          S{item.ParentIndexNumber} E{item.IndexNumber}
        </span>
      )}
      {ud?.Played && <span className="card-watched" aria-label="Watched">✓</span>}
      {progress > 0 && (
        <span className="card-progress">
          <span style={{ width: `${progress}%` }} />
        </span>
      )}
    </button>
  );
}

export const Card = memo(CardImpl);
