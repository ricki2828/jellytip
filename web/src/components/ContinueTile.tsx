// A Continue Watching tile: the usual card plus a "…" button and an actions menu (right-click, long-press, menu key / M,
// or the button). Works with keyboard, remote and touch.
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { Item } from "../api/jellyfin";
import { Card } from "./Card";
import { IptvCard } from "./IptvTile";
import { continuePath, continueTitle } from "../lib/continue";
import { actionsFor, type ActionId, type RowEntry } from "../lib/continueRow";
import { go } from "../lib/router";

const LONG_PRESS_MS = 550;
const LABELS: Record<ActionId, string> = { remove: "Hide from Continue Watching", watched: "Mark as watched", restart: "Start from beginning" };

export function restartPath(e: RowEntry): string {
  if (e.iptv) return continuePath({ ...e.iptv, pos: 0 });
  return `/play/${e.lib!.Id}?start=0`;
}

interface Props {
  entry: RowEntry;
  autoFocus?: boolean;
  onFocusItem?: (item: Item) => void;
  onAction: (a: ActionId, e: RowEntry, wrap: HTMLElement | null) => void;
}

/** Events from the menu bubble through its portal to the tile: they are not the tile's gestures. */
const fromMenu = (ev: { target: EventTarget }) => (ev.target as HTMLElement | null)?.closest?.(".tile-menu-back") != null;

export function ContinueTile({ entry, autoFocus, onFocusItem, onAction }: Props) {
  const wrap = useRef<HTMLSpanElement>(null);
  const [at, setAt] = useState<{ x: number; y: number } | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const start = useRef<{ x: number; y: number } | null>(null);
  const fired = useRef(false);
  const clear = () => {
    window.clearTimeout(timer.current);
    start.current = null;
  };
  useEffect(() => clear, []);
  const acts = actionsFor(entry);
  const e = entry;
  const name = e.lib ? (e.lib.Type === "Episode" ? `${e.lib.SeriesName ?? ""} ${e.lib.Name}` : e.lib.Name) : continueTitle(e.iptv!);

  // Capture phase: this tile's menu replaces the generic tile menu the card would open for the same gestures
  const stop = (ev: { preventDefault(): void; stopPropagation(): void }) => {
    ev.preventDefault();
    ev.stopPropagation();
  };
  const handlers = {
    onContextMenuCapture: (ev: React.MouseEvent) => {
      if (fromMenu(ev)) return;
      stop(ev);
      setAt({ x: ev.clientX, y: ev.clientY });
    },
    onKeyDownCapture: (ev: React.KeyboardEvent) => {
      if (fromMenu(ev)) return;
      if (ev.target !== ev.currentTarget && (ev.target as HTMLElement).closest(".continue-more")) return;
      if (ev.key === "ContextMenu" || (ev.key === "F10" && ev.shiftKey) || ((ev.key === "m" || ev.key === "M") && !ev.ctrlKey && !ev.metaKey && !ev.altKey)) {
        stop(ev);
        const r = (wrap.current ?? (ev.currentTarget as HTMLElement)).getBoundingClientRect();
        setAt({ x: r.left + 12, y: r.bottom - 12 });
      }
    },
    onPointerDownCapture: (ev: React.PointerEvent) => {
      if (fromMenu(ev) || ev.pointerType !== "touch" || (ev.target as HTMLElement).closest(".continue-more")) return;
      fired.current = false;
      start.current = { x: ev.clientX, y: ev.clientY };
      const { clientX, clientY } = ev;
      timer.current = window.setTimeout(() => {
        fired.current = true;
        start.current = null;
        setAt({ x: clientX, y: clientY });
      }, LONG_PRESS_MS);
    },
    onPointerMove: (ev: React.PointerEvent) => {
      if (start.current && Math.hypot(ev.clientX - start.current.x, ev.clientY - start.current.y) > 10) clear();
    },
    onPointerUp: clear,
    onPointerCancel: clear,
    // A long-press must not also open the title
    onClickCapture: (ev: React.MouseEvent) => {
      if (!fromMenu(ev) && fired.current) {
        fired.current = false;
        stop(ev);
      }
    },
  };

  const label = e.label;
  return (
    <span ref={wrap} className="continue-wrap" {...handlers}>
      {e.lib ? (
        <Card item={e.lib} tag={label} autoFocus={autoFocus} onFocusItem={onFocusItem} />
      ) : (
        <IptvCard
          item={{ id: e.iptv!.id, title: continueTitle(e.iptv!), year: null, quality: [], tech: e.iptv!.tech, poster: e.iptv!.poster, backdrop: e.iptv!.backdrop, progress: e.pos > 0 && e.dur > 0 ? { position: e.pos, duration: e.dur } : null }}
          tag={label}
          autoFocus={autoFocus}
          onOpen={() => go(continuePath(e.iptv!))}
        />
      )}
      {/* Not a spatial-nav stop (left/right moves tile to tile): the menu opens with the menu key / M, long-press, right-click or this button for mouse and touch */}
      <button
        className="continue-more"
        tabIndex={-1}
        aria-label={`More options for ${name}`}
        aria-haspopup="menu"
        title="More options"
        onClick={(ev) => {
          const r = ev.currentTarget.getBoundingClientRect();
          setAt({ x: r.left, y: r.bottom + 4 });
        }}
      >
        …
      </button>
      {at && (
        <ActionMenu
          at={at}
          title={name}
          acts={acts}
          onClose={() => setAt(null)}
          onPick={(a) => {
            setAt(null);
            if (a === "restart") go(restartPath(e));
            else onAction(a, e, wrap.current);
          }}
          returnTo={wrap}
        />
      )}
    </span>
  );
}

function ActionMenu({ at, title, acts, onClose, onPick, returnTo }: { at: { x: number; y: number }; title: string; acts: ReturnType<typeof actionsFor>; onClose: () => void; onPick: (a: ActionId) => void; returnTo: React.RefObject<HTMLElement | null> }) {
  const box = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLElement | null>(document.activeElement as HTMLElement | null);
  // The parent re-renders freely: keep the latest callbacks in refs so the effect (and its focus grab) runs once per open
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const returnRef = useRef(returnTo);
  returnRef.current = returnTo;
  useEffect(() => {
    box.current?.querySelector<HTMLElement>("button")?.focus();
    const cycle = (dir: 1 | -1) => {
      const bs = [...(box.current?.querySelectorAll<HTMLElement>("button") ?? [])];
      const i = bs.indexOf(document.activeElement as HTMLElement);
      bs[(i + dir + bs.length) % bs.length]?.focus();
    };
    const key = (ev: KeyboardEvent) => {
      if (ev.key === "Escape" || ev.key === "Backspace") {
        ev.preventDefault();
        ev.stopPropagation();
        closeRef.current();
        (opener.current && document.contains(opener.current) ? opener.current : returnRef.current.current?.querySelector<HTMLElement>("button"))?.focus();
      } else if (ev.key === "ArrowDown" || ev.key === "ArrowUp" || ev.key === "Tab") {
        // Focus stays inside the menu (the page's tile navigation would walk away from it)
        ev.preventDefault();
        ev.stopPropagation();
        cycle(ev.key === "ArrowDown" || (ev.key === "Tab" && !ev.shiftKey) ? 1 : -1);
      }
    };
    const hash = () => closeRef.current();
    document.addEventListener("keydown", key, true);
    window.addEventListener("hashchange", hash);
    return () => {
      document.removeEventListener("keydown", key, true);
      window.removeEventListener("hashchange", hash);
    };
  }, []);
  const W = 250;
  const order: ActionId[] = ["watched", "restart", "remove"];
  return createPortal(
    <div className="tile-menu-back" onClick={onClose} onContextMenu={(ev) => (ev.preventDefault(), onClose())}>
      <div
        ref={box}
        className="tile-menu continue-menu"
        role="menu"
        aria-label={title}
        style={{ left: Math.max(8, Math.min(at.x, window.innerWidth - W - 8)), top: Math.max(8, Math.min(at.y, window.innerHeight - 190)), width: W }}
        onClick={(ev) => ev.stopPropagation()}
      >
        <p className="tile-menu-title">{title}</p>
        <div className="tile-menu-buttons">
          {order.filter((a) => acts[a]).map((a) => (
            <button key={a} className="btn small" role="menuitem" data-nav onClick={() => onPick(a)}>
              {LABELS[a]}
            </button>
          ))}
        </div>
      </div>
    </div>,
    document.body,
  );
}
