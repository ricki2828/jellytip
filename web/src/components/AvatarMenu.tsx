import { useEffect, useRef, useState } from "react";
import { getSession, logout } from "../api/jellyfin";
import { isNative } from "../lib/native";

/** The avatar button and its menu: Settings, Taste, Live TV setup, Downloads (app), Admin tools, Sign out. */
export function AvatarMenu({ active }: { active: boolean }) {
  const s = getSession();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const btn = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const down = (e: MouseEvent | TouchEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    // Back (hardware / on-screen) dispatches Escape on document first: closing the menu consumes it
    const esc = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopImmediatePropagation();
      setOpen(false);
      btn.current?.focus();
    };
    document.addEventListener("mousedown", down);
    document.addEventListener("touchstart", down);
    document.addEventListener("keydown", esc, true);
    // Open: focus the first item so keys and the D-pad land inside
    box.current?.querySelector<HTMLElement>("[role=menuitem]")?.focus();
    return () => {
      document.removeEventListener("mousedown", down);
      document.removeEventListener("touchstart", down);
      document.removeEventListener("keydown", esc, true);
    };
  }, [open]);

  // A route change (hash) closes it
  useEffect(() => {
    const close = () => setOpen(false);
    window.addEventListener("hashchange", close);
    return () => window.removeEventListener("hashchange", close);
  }, []);

  const items = () => [...(box.current?.querySelectorAll<HTMLElement>("[role=menuitem]") ?? [])];
  const onKey = (e: React.KeyboardEvent) => {
    if (!open) return;
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp" && e.key !== "Home" && e.key !== "End") return;
    e.preventDefault();
    e.stopPropagation();
    const list = items();
    const i = list.indexOf(document.activeElement as HTMLElement);
    const next = e.key === "Home" ? 0 : e.key === "End" ? list.length - 1 : e.key === "ArrowDown" ? (i + 1) % list.length : (i - 1 + list.length) % list.length;
    list[next]?.focus();
  };

  const initial = (s?.userName?.trim()[0] ?? "?").toUpperCase();
  return (
    <div className="avatar-wrap" ref={box} onKeyDown={onKey}>
      <button
        ref={btn}
        className={`avatar-btn${active ? " active" : ""}`}
        data-nav
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account menu, ${s?.userName ?? ""}`}
        onClick={() => setOpen((o) => !o)}
      >
        {initial}
      </button>
      {open && (
        <div className="avatar-menu" role="menu" aria-label="Account" onClick={(e) => (e.target as HTMLElement).closest("a, button") && setOpen(false)}>
          <a role="menuitem" href="#/settings" data-nav>Settings</a>
          <a role="menuitem" href="#/taste" data-nav>Taste</a>
          <a role="menuitem" href="#/iptv" data-nav>Live TV setup</a>
          {isNative && <a role="menuitem" href="#/downloads" data-nav>Downloads</a>}
          {s?.isAdmin && (
            <>
              <div className="avatar-sep" role="separator" />
              <a role="menuitem" href="#/discover" data-nav>Add titles</a>
              <a role="menuitem" href="#/manage" data-nav>Library tools</a>
            </>
          )}
          <div className="avatar-sep" role="separator" />
          <button role="menuitem" data-nav onClick={() => logout()}>
            Sign out<span className="avatar-name">{s?.userName}</span>
          </button>
        </div>
      )}
    </div>
  );
}
