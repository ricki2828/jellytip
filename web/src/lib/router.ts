// Tiny hash router: static hosting under /jellytip/ needs no server-side fallback.
import { useEffect, useState } from "react";
import { createNavStack, type NavEntry } from "./navStack";

export interface Route {
  name: string;
  params: Record<string, string>;
  query: URLSearchParams;
}

const ID_RE = /^[0-9a-f]{32}$/i;

function parse(): Route {
  const raw = location.hash.replace(/^#/, "") || "/home";
  const [path, q = ""] = raw.split("?");
  const seg = path.split("/").filter(Boolean);
  const query = new URLSearchParams(q);
  switch (seg[0]) {
    case "item":
    case "play":
    case "person":
      // Ids go into API paths: anything but a Jellyfin id falls back to Home
      if (!ID_RE.test(seg[1] ?? "")) return { name: "home", params: {}, query };
      return { name: seg[0], params: { id: seg[1].toLowerCase() }, query };
    case "collection":
      // TMDB collection ids are numeric
      if (!/^\d{1,9}$/.test(seg[1] ?? "")) return { name: "home", params: {}, query };
      return { name: "collection", params: { id: seg[1] }, query };
    case "discover":
    case "movies":
    case "shows":
    case "search":
    case "downloads":
    case "home":
      return { name: seg[0], params: {}, query };
    default:
      return { name: "home", params: {}, query };
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState(parse);
  useEffect(() => {
    const on = () => setRoute(parse());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  return route;
}

// ---------- history: depth per entry, scroll + focus restored on Back (rules in lib/navStack.ts) ----------

const nav = createNavStack(history);
nav.init();
let restoring = false;
let lastFocus: string | undefined;
let saveTimer: number | null = null;

const ROOTS = new Set(["home", "movies", "shows", "search", "downloads", "discover"]);
/** Root tabs (Home, Movies, Shows, Search, Downloads, admins' Discover) have no back arrow; everything else does. */
export const isRootRoute = (name: string) => ROOTS.has(name);

function saveNow() {
  saveTimer = null;
  if (restoring) return;
  try {
    nav.save(Math.round(window.scrollY), lastFocus);
  } catch {
    // Safari throttles history.replaceState; the next save gets it
  }
}
const saveSoon = () => {
  if (saveTimer == null) saveTimer = window.setTimeout(saveNow, 400);
};
window.addEventListener("scroll", saveSoon, { passive: true });
document.addEventListener("focusin", (e) => {
  const el = e.target as HTMLElement | null;
  lastFocus = el?.getAttribute?.("data-nav") != null ? el.getAttribute("aria-label") ?? el.textContent?.trim().slice(0, 80) ?? undefined : undefined;
  saveSoon();
});

/** Back to an entry: wait for the screen to be tall enough (rows load in), then scroll and refocus the same tile. */
function restore(e: NavEntry) {
  restoring = true;
  const y = e.scrollY ?? 0;
  let tries = 0;
  const tick = () => {
    const room = document.documentElement.scrollHeight - window.innerHeight;
    if (room + 2 < y && tries++ < 40) {
      window.setTimeout(tick, 100);
      return;
    }
    window.scrollTo(0, y);
    if (e.focus && !matchMedia("(hover: none) and (pointer: coarse)").matches) {
      const el = [...document.querySelectorAll<HTMLElement>("[data-nav]")].find(
        (n) => (n.getAttribute("aria-label") ?? n.textContent?.trim().slice(0, 80)) === e.focus,
      );
      el?.focus({ preventScroll: true });
    }
    restoring = false;
  };
  window.setTimeout(tick, 0);
}

// Registered before any useRoute listener, so screens render knowing whether this was a Back
window.addEventListener("hashchange", () => {
  if (saveTimer != null) window.clearTimeout(saveTimer);
  saveTimer = null;
  const e = nav.onHashChange();
  if (e) restore(e);
  else restoring = false;
});

/** True while a Back / Forward is putting the scroll position back (the app must not reset it to the top). */
export const isRestoring = () => restoring;

export function go(path: string, replace = false) {
  const h = `#${path}`;
  if (h === location.hash) return;
  if (saveTimer != null) saveNow(); // leave the current entry with its final scroll / focus
  if (replace) {
    nav.markReplace();
    location.replace(h);
  } else location.hash = path;
}

let rootBack: () => void = () => undefined;
/** What Back does on Home with nothing in-app behind it (the phone app minimises; the website stays put). */
export function onRootBack(fn: () => void) {
  rootBack = fn;
}

/** Back within the app: the previous in-app screen, else `fallback` (deep link), else (already there) the root action. */
export function back(fallback = "/home") {
  if (nav.canGoBack()) history.back();
  else if (location.hash.replace(/^#/, "") !== fallback && !(fallback === "/home" && parse().name === "home")) go(fallback, true);
  else rootBack();
}

/**
 * The one Back used by Android's back button and the phone back arrow: an open sheet / modal / unmuted video gets it
 * first (they listen for Escape on document and consume it), then back().
 */
export function systemBack() {
  const ev = new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true });
  document.dispatchEvent(ev); // unconsumed, it reaches the window-level Escape → back()
}
