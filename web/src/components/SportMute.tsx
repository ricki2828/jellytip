// "Not interested in <league>" / "<sport>" on live and upcoming sport tiles: right-click, long-press, the menu key or M
// opens a small sheet; the choice is saved to prefs sport.muted and the live views look again.
import { useEffect, useRef, useState } from "react";
import { prefsNow, usePrefs } from "../api/me";
import { setMuted } from "../api/sportTaste";
import { muteChoices } from "../lib/sportMute";
import { SheetClose } from "./BackArrow";
import { flashNote } from "./TasteButtons";

interface Target {
  title: string;
  league?: string | null;
  sportName?: string | null;
}

const EVENT = "jellytip-sport-menu";
const LONG_PRESS_MS = 550;
const open = (t: Target) => window.dispatchEvent(new CustomEvent<Target>(EVENT, { detail: t }));

/** Handlers for a tile; nothing when personalisation is off or the tile names neither league nor sport. */
export function useSportMute(t: Target) {
  const { available } = usePrefs({ defer: true });
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
  if (!available || !(t.league || t.sportName)) return { swallowClick, props: {} };
  return {
    swallowClick,
    props: {
      onContextMenu: (e: React.MouseEvent) => {
        e.preventDefault();
        open(t);
      },
      onKeyDown: (e: React.KeyboardEvent) => {
        if (e.key === "ContextMenu" || (e.key === "F10" && e.shiftKey) || (e.key === "m" && !e.ctrlKey && !e.metaKey && !e.altKey)) {
          e.preventDefault();
          open(t);
        }
      },
      onPointerDown: (e: React.PointerEvent) => {
        if (e.pointerType !== "touch") return;
        fired.current = false;
        start.current = { x: e.clientX, y: e.clientY };
        timer.current = window.setTimeout(() => {
          fired.current = true;
          start.current = null;
          open(t);
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

/** Mounted once (App). */
export function SportMuteHost() {
  const [t, setT] = useState<Target | null>(null);
  const [busy, setBusy] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const on = (e: Event) => {
      opener.current = document.activeElement as HTMLElement | null;
      setT((e as CustomEvent<Target>).detail);
    };
    window.addEventListener(EVENT, on);
    return () => window.removeEventListener(EVENT, on);
  }, []);
  const close = () => {
    setT(null);
    opener.current?.focus({ preventScroll: true });
  };
  useEffect(() => {
    if (!t) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        close();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, [t]);
  if (!t) return null;
  const choices = muteChoices(t, prefsNow()?.sport.muted ?? { sports: [], leagues: [], tags: [] });
  return (
    <div className="livetv-sheet-back" onClick={close}>
      <div className="livetv-sheet" role="dialog" aria-label={t.title} onClick={(e) => e.stopPropagation()}>
        <SheetClose onClose={close} />
        <p className="iptv-hint">{t.title}</p>
        {choices.length === 0 && <p className="iptv-hint">Already muted. Change it in Settings → Live sport.</p>}
        <div className="buttons">
          {choices.map((c, i) => (
            <button
              key={c.kind}
              className="btn"
              data-nav
              data-autofocus={i === 0 || undefined}
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                const err = await setMuted(c.kind, c.name, true);
                setBusy(false);
                flashNote(err ?? `Hidden from live sport: ${c.name}. Undo in Settings.`);
                close();
              }}
            >
              {c.label}
            </button>
          ))}
          <button className="btn" data-nav data-autofocus={choices.length === 0 || undefined} onClick={close}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

/** A tile button that also offers "Not interested" (right-click, long-press, menu key / M). */
export function MuteButton({ target, onClick, children, ...rest }: { target: Target } & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "target">) {
  const m = useSportMute(target);
  return (
    <button {...rest} {...m.props} onClick={(e) => !m.swallowClick() && onClick?.(e)}>
      {children}
    </button>
  );
}
