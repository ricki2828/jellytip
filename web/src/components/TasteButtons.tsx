// Thumbs up / down and "Not interested" for a title: on details, and in the tile menu (right-click / long-press on a
// tile). Only shown when the server has personalisation (usePrefs().available).
import { useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { personalOn, setNotInterested, setThumb, usePrefs, useSignal } from "../api/me";
import { go } from "../lib/router";

const Thumb = ({ down }: { down?: boolean }) => (
  <svg viewBox="0 0 24 24" aria-hidden className={`thumb-icon${down ? " down" : ""}`}>
    <path d="M7 10v10H4V10zM7 10l4-7c1.4 0 2.3 1.2 2 2.5L12.3 9H18a2 2 0 0 1 2 2.3l-1.2 7A2 2 0 0 1 16.8 20H7" />
  </svg>
);

/** Brief confirmation shared by details and the tile menu. */
export function flashNote(text: string) {
  window.dispatchEvent(new CustomEvent("jellytip-note", { detail: text }));
}

export function TasteButtons({ item, compact = false }: { item: Item; compact?: boolean }) {
  const { available } = usePrefs();
  const sig = useSignal(item.Id);
  const [busy, setBusy] = useState(false);
  if (!available) return null;
  const run = async (fn: () => Promise<string | null>, ok: string) => {
    setBusy(true);
    const e = await fn();
    setBusy(false);
    flashNote(e ?? ok);
  };
  const thumb = (v: 1 | -1) => run(() => setThumb(item.Id, sig.thumb === v ? 0 : v), sig.thumb === v ? "Rating cleared" : v === 1 ? "More like this" : "Fewer like this");
  return (
    <>
      <button className={`btn${compact ? " small" : ""}${sig.thumb === 1 ? " on" : ""}`} data-nav disabled={busy} aria-pressed={sig.thumb === 1} aria-label="Thumbs up" title="Thumbs up" onClick={() => thumb(1)}>
        <Thumb />
      </button>
      <button className={`btn${compact ? " small" : ""}${sig.thumb === -1 ? " on" : ""}`} data-nav disabled={busy} aria-pressed={sig.thumb === -1} aria-label="Thumbs down" title="Thumbs down" onClick={() => thumb(-1)}>
        <Thumb down />
      </button>
      <button
        className={`btn${compact ? " small" : ""}`}
        data-nav
        disabled={busy}
        aria-pressed={!!sig.notInterested}
        onClick={() => run(() => setNotInterested(item.Id, !sig.notInterested), sig.notInterested ? "Back in your rows" : "Hidden from your rows and picks")}
      >
        {sig.notInterested ? "Interested again" : "Not interested"}
      </button>
    </>
  );
}

// ---------- tile menu ----------

interface MenuAt {
  item: Item;
  x: number;
  y: number;
}

/** Open the tile menu for an item at a screen point (right-click, long-press, or the menu key on a focused tile). */
export function openTileMenu(item: Item, x: number, y: number) {
  window.dispatchEvent(new CustomEvent<MenuAt>("jellytip-tile-menu", { detail: { item, x, y } }));
}

/** Mounted once (App): the menu itself plus the short confirmation note. */
export function TileMenuHost() {
  const [at, setAt] = useState<MenuAt | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const onMenu = (e: Event) => {
      opener.current = document.activeElement as HTMLElement | null;
      setAt((e as CustomEvent<MenuAt>).detail);
    };
    let t: number | undefined;
    const onNote = (e: Event) => {
      setNote((e as CustomEvent<string>).detail);
      window.clearTimeout(t);
      t = window.setTimeout(() => setNote(null), 2600);
    };
    // Any navigation closes the menu
    const onNav = () => setAt(null);
    window.addEventListener("jellytip-tile-menu", onMenu);
    window.addEventListener("jellytip-note", onNote);
    window.addEventListener("hashchange", onNav);
    return () => {
      window.removeEventListener("hashchange", onNav);
      window.removeEventListener("jellytip-tile-menu", onMenu);
      window.removeEventListener("jellytip-note", onNote);
    };
  }, []);

  useEffect(() => {
    if (!at) return;
    box.current?.querySelector<HTMLElement>("[data-nav]")?.focus();
    const close = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      setAt(null);
      opener.current?.focus();
    };
    document.addEventListener("keydown", close, true);
    return () => document.removeEventListener("keydown", close, true);
  }, [at]);

  const W = 220;
  return (
    <>
      {at && (
        <div className="tile-menu-back" onClick={() => setAt(null)} onContextMenu={(e) => (e.preventDefault(), setAt(null))}>
          <div
            ref={box}
            className="tile-menu"
            role="menu"
            aria-label={at.item.Name}
            style={{ left: Math.max(8, Math.min(at.x, window.innerWidth - W - 8)), top: Math.max(8, Math.min(at.y, window.innerHeight - 220)), width: W }}
            onClick={(e) => e.stopPropagation()}
          >
            <p className="tile-menu-title">{at.item.Name}</p>
            <div className="tile-menu-buttons" onClick={() => window.setTimeout(() => setAt(null), 150)}>
              <button className="btn small" data-nav onClick={() => go(`/item/${at.item.Id}`)}>
                Open
              </button>
              <TasteButtons item={at.item} compact />
            </div>
          </div>
        </div>
      )}
      {note && (
        <p className="taste-note" role="status">
          {note}
        </p>
      )}
    </>
  );
}

const LONG_PRESS_MS = 550;

/**
 * For a tile: right-click, long-press (touch) or the menu key opens the tile menu when personalisation is on.
 * `swallowClick()` tells the tile's click handler a long-press just happened (so it doesn't also open the title).
 */
export function useTileMenu(item: Item | undefined) {
  const timer = useRef<number | undefined>(undefined);
  const start = useRef<{ x: number; y: number } | null>(null);
  const fired = useRef(false);
  const clear = () => {
    window.clearTimeout(timer.current);
    start.current = null;
  };
  const swallowClick = () => {
    const f = fired.current;
    fired.current = false;
    return f;
  };
  if (!item || !personalOn()) return { swallowClick, props: {} };
  return {
    swallowClick,
    props: {
      onContextMenu: (e: React.MouseEvent) => {
        e.preventDefault();
        openTileMenu(item, e.clientX, e.clientY);
      },
      onKeyDown: (e: React.KeyboardEvent) => {
        if (e.key === "ContextMenu" || (e.key === "F10" && e.shiftKey)) {
          e.preventDefault();
          const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
          openTileMenu(item, r.left + 12, r.bottom - 12);
        }
      },
      onPointerDown: (e: React.PointerEvent) => {
        if (e.pointerType !== "touch") return;
        fired.current = false;
        start.current = { x: e.clientX, y: e.clientY };
        const { clientX, clientY } = e;
        timer.current = window.setTimeout(() => {
          fired.current = true;
          start.current = null;
          openTileMenu(item, clientX, clientY);
        }, LONG_PRESS_MS);
      },
      onPointerMove: (e: React.PointerEvent) => {
        if (start.current && Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > 10) clear();
      },
      onPointerUp: clear,
      onPointerCancel: clear,
    },
  };
}
