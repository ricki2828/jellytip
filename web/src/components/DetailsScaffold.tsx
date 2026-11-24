// The details page's shared parts, whatever the source (parity #7, "the same irrespective of source"): the library page
// (screens/Details.tsx) and the IPTV page (screens/iptv/IptvDetails.tsx) both draw the same hero, button row slot, season
// chips, episode list / strip, people row and trailer modal. Each page keeps its own loading; these only draw.
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Backdrop } from "./Backdrop";
import { PreviewVideo } from "./PreviewVideo";
import { logoClass } from "../lib/logoStyle";
import { focusFirst } from "../lib/spatial";

/** Backdrop + muted preview + scrims, the bottom-left text block (children) and anything beside it (the review quote). */
export function DetailsHero({ url, focal, preview, aside, children }: { url: string | null; focal: Parameters<typeof Backdrop>[0]["focal"]; preview: string | null; aside?: ReactNode; children: ReactNode }) {
  return (
    <div className="details-hero">
      <Backdrop url={url} focal={focal} />
      <PreviewVideo url={preview} />
      <div className="scrim-left" />
      <div className="scrim-bottom" />
      <div className="details-text">{children}</div>
      {aside}
    </div>
  );
}

/** Clearlogo as an off-white silhouette, else the typeset title (tall / narrow / broken logos fall back to the title). */
export function HeroLogo({ url, name, owner, big = false }: { url: string | null | undefined; name: string; owner: string; big?: boolean }) {
  const [failed, setFailed] = useState<string | null>(null);
  if (url && failed !== url) {
    return (
      <img
        className={`${big ? "title-logo big" : "title-logo"} ${logoClass(owner, "white")}`}
        src={url}
        alt={name}
        onError={() => setFailed(url)}
        onLoad={(e) => {
          const img = e.currentTarget;
          // Tall/narrow logos render tiny and unreadable: typeset the title instead
          if (img.naturalHeight > img.naturalWidth * 0.6) setFailed(url);
        }}
      />
    );
  }
  return <h1 className={big ? "title-text big" : "title-text"}>{name}</h1>;
}

/** The synopsis: clamped, a tap / OK opens the full text. */
export function HeroOverview({ text, open, onToggle }: { text: string | null | undefined; open: boolean; onToggle: () => void }) {
  if (!text) return null;
  return (
    <button className={`overview${open ? " open" : ""}`} data-nav onClick={onToggle}>
      {text}
    </button>
  );
}

/** Back to this page reopens the season he picked (?season=, as a link into it does). */
export function rememberSeason(key: string) {
  const [path, q] = location.hash.split("?");
  const u = new URLSearchParams(q ?? "");
  u.set("season", key);
  history.replaceState(history.state, "", `${path}?${u}`);
}

/** The ?season= this page was opened with (a chip pick replaces it in place). */
export const askedSeason = (): string | null => new URLSearchParams(location.hash.split("?")[1] ?? "").get("season");

/** Season chips; [children] = the page's own chips after them (download, subtitles, delete). A season changes on click. */
export function SeasonChips({ seasons, current, onPick, children }: { seasons: readonly { key: string; name: string }[]; current: string | null; onPick: (key: string) => void; children?: ReactNode }) {
  return (
    <div className="chips">
      {seasons.map((s) => (
        <button key={s.key} data-nav className={`chip${s.key === current ? " on" : ""}`} onClick={() => onPick(s.key)}>
          {s.name}
        </button>
      ))}
      {children}
    </div>
  );
}

/** One tile of the episode list: a placeholder slot, or an episode with art + text (phone) / art alone (wide). */
export type EpisodeEntry =
  | { key: string; next: boolean; slot: ReactNode }
  | {
      key: string;
      next: boolean;
      /** The 16:9 tile; [phone] = the small list still, else the strip tile. */
      card: (phone: boolean) => ReactNode;
      name: string;
      meta: string;
      overview?: string | null;
      onOpen: () => void;
      /** Phone only: a control at the end of the row (IPTV's watched toggle). */
      extra?: ReactNode;
    };

const Pill = () => <span className="ep-next-pill">Next</span>;

/**
 * The season's episodes. Phone: a vertical list, still + name/runtime/overview side by side. Wide: a sideways strip of
 * 16:9 tiles. The "Next" pill toggles a class on an always-present wrapper, so its arrival never remounts a focused tile.
 * [entries] null = still loading (skeletons).
 */
export function EpisodeList({ phone, entries }: { phone: boolean; entries: readonly EpisodeEntry[] | null }) {
  if (phone)
    return (
      <div className="ep-list">
        {(entries ?? []).map((r) => {
          if ("slot" in r)
            return (
              <div key={r.key} className={`ep-slot${r.next ? " is-next" : ""}`}>
                {r.slot}
                {r.next && <Pill />}
              </div>
            );
          return (
            <div key={r.key} className={`ep-row${r.next ? " is-next" : ""}`}>
              {r.next && <Pill />}
              {r.card(true)}
              <button className="ep-text" onClick={r.onOpen}>
                <span className="ep-name">{r.name}</span>
                <span className="ep-meta">{r.meta}</span>
                {r.overview && <span className="ep-overview">{r.overview}</span>}
              </button>
              {r.extra}
            </div>
          );
        })}
        {!entries && Array.from({ length: 3 }, (_, i) => <div key={i} className="ep-row card-skeleton" />)}
      </div>
    );
  return (
    <div className="row-scroll">
      {(entries ?? []).map((r) => (
        <div key={r.key} className={`ep-slot${r.next ? " is-next" : ""}`}>
          {"slot" in r ? r.slot : r.card(false)}
          {r.next && <Pill />}
        </div>
      ))}
      {!entries && Array.from({ length: 5 }, (_, i) => <div key={i} className="card card-skeleton" />)}
    </div>
  );
}

export interface Person {
  key: string;
  name: string;
  role?: string | null;
  img?: string | null;
}

/** The "Cast" row. */
export function PeopleRow({ people }: { people: readonly Person[] }) {
  if (!people.length) return null;
  return (
    <section className="row">
      <h2 className="row-title">Cast</h2>
      <div className="row-scroll">
        {people.map((p) => (
          <div key={p.key} className="person">
            {p.img ? <img src={p.img} alt="" loading="lazy" /> : <div className="person-blank" />}
            <div className="person-text">
              <span>{p.name}</span>
              {p.role && <span className="dim">{p.role}</span>}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

/** The full trailer over the page; Esc / Close / a tap outside closes it (document runs before the app's window-level Esc). */
export function TrailerModal({ src, onClose, onTimeUpdate, onError }: { src: string; onClose: () => void; onTimeUpdate: React.ReactEventHandler<HTMLVideoElement>; onError: () => void }) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      close.current();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return (
    <div className="trailer-modal" onClick={onClose}>
      <video src={src} autoPlay controls onEnded={onClose} onTimeUpdate={onTimeUpdate} onError={onError} onClick={(e) => e.stopPropagation()} />
      <button className="btn modal-close" data-nav data-autofocus onClick={onClose}>
        Close
      </button>
    </div>
  );
}

/**
 * The series' primary button choice, made ONCE per page: the first render where [ready] is true runs [compute]. Late answers
 * still fill the tiles but never relabel the button, move the Next pill or switch the season under him. "Nothing to play"
 * is decided again as answers land (a button appearing relabels nothing). When it settles on nothing, focus that sat on the
 * waiting button moves on, never to nothing.
 */
export function useFrozenPrimary<T>(id: string, ready: boolean, compute: () => T | null): { primary: T | null; decided: boolean } {
  const frozen = useRef<{ id: string; v: T | null } | null>(null);
  if (ready && (frozen.current?.id !== id || frozen.current.v === null)) frozen.current = { id, v: compute() };
  const decided = frozen.current?.id === id;
  const primary = decided ? frozen.current!.v : null;
  useLayoutEffect(() => {
    if (decided && !primary && document.activeElement === document.body) focusFirst(document.querySelector(".details") ?? document);
  }, [decided, primary]);
  return { primary, decided };
}
