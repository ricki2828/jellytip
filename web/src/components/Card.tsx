// Landscape titled tile (see cardLayers for the fallback chain). Nothing is drawn below the tile, and it is never blank:
// the title sits on the dark tile until the picture has loaded.
import { memo, useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { backdropInfo, episodeArtFor, peekBackdropInfo, peekEpisodeArt } from "../api/companion";
import { cardLayers, heroItemId } from "../lib/art";
import { go } from "../lib/router";
import { useIsPhone } from "../lib/device";
import { logoClass, logoOwner } from "../lib/logoStyle";
import { TechBadges } from "./TechBadges";
import { techOf, tileBadges } from "../lib/techBadges";

export const CARD_W = 264;
/** Phone rows: big enough to hit, with the next tile peeking in to show the row swipes. */
export const PHONE_CARD_W = 216;
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
    // Unmounted (scrolled past, page left) before the queue reached it: the ranking request is dropped
    const ctl = new AbortController();
    const t = setTimeout(() => alive && setReady(true), RANK_WAIT_MS);
    const jobs: Promise<unknown>[] = [backdropInfo(rankedId, { signal: ctl.signal })];
    if (item.Type === "Episode" && item.SeriesId) jobs.push(episodeArtFor(item.SeriesId, { signal: ctl.signal }));
    Promise.all(jobs).then(() => {
      if (!alive) return;
      setReady(true);
      bump((n) => n + 1); // late answer: upgrade the art
    });
    return () => {
      alive = false;
      ctl.abort();
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, rankedId]);
  return ready || !!item.ImageTags?.Thumb;
}

interface Props {
  item: Item;
  onFocusItem?: (item: Item) => void;
  /** CSS width in px; 0 = fluid (grid cells size it). */
  width?: number;
  autoFocus?: boolean;
  /** Overrides the default (open details). */
  onOpen?: (item: Item) => void;
  label?: string;
  /** Overlay text drawn instead of the logo/title (episode lists: "3 · Episode name"). */
  caption?: string;
  /** Small chip in the top-left corner (e.g. "Nadine finished · 2d ago"). */
  tag?: string;
  /** The art is known to carry no title (specials payload `untitled`): draw the title over it. */
  untitled?: boolean;
}

function CardImpl({ item, onFocusItem, width: widthProp, autoFocus, onOpen, label, caption, tag, untitled }: Props) {
  const phone = useIsPhone();
  const width = widthProp ?? (phone ? PHONE_CARD_W : CARD_W);
  const ref = useRef<HTMLButtonElement>(null);
  const visible = useVisible(ref);
  const ready = useCompanionReady(item, visible);
  const fluid = width === 0;
  const layers = visible && ready ? cardLayers(item, { untitled }) : null;
  // Failed images are remembered by URL and skipped; a chain that changes later (companion answer) only adds new URLs
  const [failed, setFailed] = useState<Set<string>>(new Set());
  const [loaded, setLoaded] = useState<Set<string>>(new Set());
  const layer = layers?.find((l) => !failed.has(l.url ?? l.logoUrl ?? "")) ?? null;
  const fail = (u: string | null | undefined) => u && setFailed((f) => new Set(f).add(u));
  const done = (u: string) => setLoaded((l) => (l.has(u) ? l : new Set(l).add(u)));

  const baseReady = !!layer?.url && loaded.has(layer.url);
  const titledShown = layer?.kind === "titled" && baseReady;
  const name = item.Type === "Episode" ? item.SeriesName ?? item.Name : item.Name;
  const ud = item.UserData;
  const progress = ud?.PlayedPercentage && ud.PlayedPercentage > 0 && ud.PlayedPercentage < 100 ? ud.PlayedPercentage : 0;
  const title =
    label ??
    (item.Type === "Episode" ? `${item.SeriesName ?? ""}${item.SeriesName ? " · " : ""}S${item.ParentIndexNumber ?? "?"} E${item.IndexNumber ?? "?"} ${item.Name}` : item.Name);
  // Logo: bottom-left on untitled art, centred on the dark tile for the logo layer
  const logoUrl = !caption && layer && (layer.kind === "image" || layer.kind === "logo") ? layer.logoUrl : null;
  const logoOk = !!logoUrl && !failed.has(`logo:${logoUrl}`);
  // Text shows until the picture is up (so a tile is never blank), and stays wherever nothing else names the title
  const showText = !titledShown && !(logoOk && (layer?.kind === "logo" || baseReady)) && !(layer?.kind === "poster" && baseReady);

  // Premium formats, bottom-right; episodes keep that corner for "S1 E2"
  const tech = item.Type === "Episode" ? [] : techOf(item.Id);
  const techShown = tileBadges(tech).length > 0;

  return (
    <button
      ref={ref}
      className={`card${fluid ? " fluid" : ""}${techShown ? " has-tech" : ""}`}
      data-nav
      data-autofocus={autoFocus || undefined}
      style={fluid ? undefined : { width, height: Math.round((width * 9) / 16) }}
      title={title}
      aria-label={title}
      onFocus={() => onFocusItem?.(item)}
      onMouseEnter={() => onFocusItem?.(item)}
      onClick={() => (onOpen ? onOpen(item) : go(`/item/${item.Id}`))}
    >
      {layer?.kind === "poster" && layer.blurUrl && (
        <img className={`card-blur${baseReady ? " in" : ""}`} src={layer.blurUrl} alt="" decoding="async" />
      )}
      {layer?.url && (
        <img
          key={layer.url}
          className={`${layer.kind === "poster" ? "card-poster" : "card-img"}${baseReady ? " in" : ""}`}
          src={layer.url}
          alt=""
          decoding="async"
          onLoad={() => done(layer.url!)}
          onError={() => fail(layer.url)}
        />
      )}
      {(!titledShown || caption) && layer?.kind !== "logo" && <div className="card-fade" />}
      {logoOk && (
        <img
          className={`${layer?.kind === "logo" ? "card-logo center" : `card-logo${baseReady ? " in" : ""}`} ${logoClass(logoOwner(item), "original")}`}
          src={logoUrl!}
          alt=""
          onError={() => fail(`logo:${logoUrl}`)}
          onLoad={() => layer?.kind === "logo" && done(logoUrl!)}
        />
      )}
      {(caption ? !!layer : showText) && <span className={`card-title${baseReady || layer?.kind === "text" ? "" : " pending"}`}>{caption ?? name}</span>}
      {!caption && item.Type === "Episode" && layer && !titledShown && (
        <span className="card-ep">
          S{item.ParentIndexNumber} E{item.IndexNumber}
        </span>
      )}
      {tag && <span className="card-tag">{tag}</span>}
      {techShown && <TechBadges tech={tech} />}
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
