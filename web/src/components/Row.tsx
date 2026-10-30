import { useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { useIsPhone } from "../lib/device";
import { go } from "../lib/router";
import { Card, CARD_W, PHONE_CARD_W } from "./Card";
import { filmCount, PersonTile } from "./PersonTile";
import { PosterCard } from "./PosterCard";
import { signalOf } from "../api/me";

const POSTER_W = 150;
const PHONE_POSTER_W = 112;

interface Props {
  title: string;
  /** Items, or a loader called once the row nears the viewport. */
  items?: Item[];
  load?: () => Promise<Item[]>;
  onFocusItem?: (item: Item) => void;
  autoFocusFirst?: boolean;
  onOpen?: (item: Item) => void;
  /** "poster" = 2:3 Primary posters; anything else the 16:9 titled tiles. */
  layout?: "standard" | "poster";
  /** Ids whose art has no title in it: those tiles draw the title. */
  untitled?: Set<string>;
  /** Header becomes a link (a person's filmography, a franchise page). */
  onTitle?: () => void;
  /** Person rows: a portrait tile first (same height as the row's tiles), opening the filmography. */
  person?: { id: string; name: string; imageTag?: string | null; count?: number } | null;
  /** More tiles after the items (My list: IPTV titles beside library ones). */
  extra?: React.ReactNode;
}

/** Calls `load` once the element nears the viewport; null while waiting, [] when it failed or came back empty. */
export function useLazyItems(ref: React.RefObject<Element | null>, items?: Item[], load?: () => Promise<Item[]>): Item[] | null {
  const [loaded, setLoaded] = useState<Item[] | null>(items ?? null);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (items) setLoaded(items);
  }, [items]);

  useEffect(() => {
    if (items || !loadRef.current || loaded) return;
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (es) => {
        if (!es.some((e) => e.isIntersecting)) return;
        io.disconnect();
        loadRef.current!()
          .then(setLoaded)
          .catch(() => setLoaded([]));
      },
      { rootMargin: "600px 0px" }, // one batched Jellyfin call covers the rows coming up next
    );
    io.observe(el);
    return () => io.disconnect();
  }, [ref, items, loaded]);
  return loaded;
}

export function RowTitle({ title, onTitle }: { title: string; onTitle?: () => void }) {
  if (!onTitle) return <h2 className="row-title">{title}</h2>;
  return (
    <h2 className="row-title">
      <button className="row-title-link" data-nav onClick={onTitle}>
        {title}
        <span className="row-title-more" aria-hidden>
          ›
        </span>
      </button>
    </h2>
  );
}

export function Row({ title, items, load, onFocusItem, autoFocusFirst, onOpen, layout, untitled, onTitle, person, extra }: Props) {
  const ref = useRef<HTMLElement>(null);
  const lazy = useLazyItems(ref, items, load);
  // "Not interested" takes a title out of the row at once (the server drops it from tomorrow's rows)
  const [, bump] = useState(0);
  useEffect(() => {
    const on = () => bump((n) => n + 1);
    window.addEventListener("jellytip-signals", on);
    return () => window.removeEventListener("jellytip-signals", on);
  }, []);
  const loaded = lazy && lazy.filter((it) => !signalOf(it.Id).notInterested);
  const phone = useIsPhone();
  const poster = layout === "poster";
  const posterW = phone ? PHONE_POSTER_W : POSTER_W;
  const cardH = Math.round(((phone ? PHONE_CARD_W : CARD_W) * 9) / 16);

  if (loaded && loaded.length === 0 && !extra) return null;
  return (
    <section className={poster ? "row poster-row" : "row"} ref={ref}>
      <RowTitle title={title} onTitle={onTitle} />
      <div className="row-scroll">
        {person && (
          <PersonTile
            id={person.id}
            name={person.name}
            imageTag={person.imageTag}
            width={poster ? posterW : Math.round((cardH * 2) / 3)}
            sub={person.count ? filmCount(person.count) : undefined}
          />
        )}
        {loaded
          ? loaded.map((it, i) =>
              poster ? (
                <PosterCard
                  key={it.Id}
                  item={it}
                  width={posterW}
                  onOpen={() => (onOpen ? onOpen(it) : go(`/item/${it.Id}`))}
                  onFocus={onFocusItem && (() => onFocusItem(it))}
                  autoFocus={autoFocusFirst && i === 0}
                  untitled={untitled?.has(it.Id)}
                />
              ) : (
                <Card key={it.Id} item={it} onFocusItem={onFocusItem} autoFocus={autoFocusFirst && i === 0} onOpen={onOpen} untitled={untitled?.has(it.Id)} />
              ),
            )
          : Array.from({ length: 6 }, (_, i) => (
              <div key={i} className={poster ? "poster card-skeleton" : "card card-skeleton"} style={poster ? { width: phone ? PHONE_POSTER_W : POSTER_W } : undefined} />
            ))}
        {extra}
      </div>
    </section>
  );
}
