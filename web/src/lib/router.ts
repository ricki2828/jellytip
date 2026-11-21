// Tiny hash router: static hosting under /jellytip/ needs no server-side fallback.
import { useEffect, useState } from "react";
import { createNavStack, type NavEntry } from "./navStack";
import { anchorScroll, pickAnchor, rowKeys, rowScrolls } from "./scrollMemory";

import { parseHash, type Route } from "./routeParse";
export type { Route };

const parse = (): Route => parseHash(location.hash);

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

const ROOTS = new Set(["home", "movies", "shows", "search", "ask", "downloads", "fantasy", "discover", "livetv", "recordings", "upcoming", "collections", "inbox", "channels", "live", "games", "reminders"]);
/** Root tabs (Home, Movies, Shows, Search, Downloads, admins' Discover, Live TV) have no back arrow; everything else does. */
export const isRootRoute = (name: string) => ROOTS.has(name);

/** The screen's horizontal rows (each `.row-scroll`) keyed by their row title (lib/scrollMemory.rowKeys). */
function screenRows(): { key: string; el: HTMLElement; box: HTMLElement }[] {
  const els = [...document.querySelectorAll<HTMLElement>("main .row-scroll, #root .row-scroll")].filter((e, i, a) => a.indexOf(e) === i && e.offsetParent);
  const boxes = els.map((e) => (e.closest(".row, section") as HTMLElement | null) ?? e);
  const keys = rowKeys(boxes.map((b) => b.querySelector(".row-title")?.textContent ?? b.getAttribute("aria-label")));
  return els.map((el, i) => ({ key: keys[i], el, box: boxes[i] }));
}

/**
 * What the anchor can be: fixture cards first (Game Centre: the exact card, wherever its section moves it after a refresh), then
 * the rows. pickAnchor takes the first one on screen in this order.
 */
function anchorCandidates(rows: { key: string; box: HTMLElement }[]): { key: string; box: HTMLElement }[] {
  const cards = [...document.querySelectorAll<HTMLElement>("[data-fid]")].filter((e) => e.offsetParent).map((e) => ({ key: `fid:${e.dataset.fid}`, box: e }));
  return [...cards, ...rows];
}

function saveNow() {
  saveTimer = null;
  if (restoring) return;
  try {
    const rows = screenRows();
    nav.save(Math.round(window.scrollY), lastFocus, {
      rows: rowScrolls(rows.map((r) => ({ key: r.key, left: r.el.scrollLeft }))),
      anchor: pickAnchor(anchorCandidates(rows).map((r) => ({ key: r.key, ...rect(r.box) }))) ?? undefined,
    });
  } catch {
    // Safari throttles history.replaceState; the next save gets it
  }
}
const rect = (el: HTMLElement) => {
  const b = el.getBoundingClientRect();
  return { top: b.top, bottom: b.bottom };
};
const saveSoon = () => {
  if (saveTimer == null) saveTimer = window.setTimeout(saveNow, 400);
};
// Capture: a row's own horizontal scroll doesn't bubble, but it does pass through document in the capture phase
document.addEventListener("scroll", saveSoon, { capture: true, passive: true });
// Any tap flushes a pending save first, so a link that skips go() (a bare href="#/…") still leaves with the latest place
document.addEventListener("click", () => saveTimer != null && saveNow(), true);
document.addEventListener("focusin", (e) => {
  const el = e.target as HTMLElement | null;
  lastFocus = el?.getAttribute?.("data-nav") != null ? el.getAttribute("aria-label") ?? el.textContent?.trim().slice(0, 80) ?? undefined : undefined;
  saveSoon();
});

const RESTORE_MS = 5000;
const SETTLED_TICKS = 5;
let restoreGen = 0;
let stopRestore: (() => void) | null = null;

/**
 * Back to an entry: put each moved row back to its scrollLeft once it is wide enough, and the page so the anchor row sits where
 * it was (else the raw scrollY once the page is tall enough). Rows fill in late and rows above can change height, so it keeps
 * correcting until the layout has been still for half a second, up to 5 s, and stops at once when the person scrolls or presses.
 */
function restore(e: NavEntry) {
  restoring = true;
  const gen = ++restoreGen;
  const t0 = Date.now();
  const rowsLeft = new Map(Object.entries(e.rows ?? {}));
  let still = 0;
  let focused = false;
  const done = () => {
    for (const ev of STOP) window.removeEventListener(ev, cancel, true);
    if (gen !== restoreGen) return;
    restoreGen++;
    restoring = false;
    stopRestore = null;
  };
  const cancel = () => done();
  const STOP = ["touchstart", "wheel", "keydown", "pointerdown"] as const;
  for (const ev of STOP) window.addEventListener(ev, cancel, { capture: true, passive: true });
  stopRestore = done;
  const tick = () => {
    if (gen !== restoreGen) return;
    let moved = false;
    const rows = screenRows();
    for (const [key, left] of rowsLeft) {
      const r = rows.find((x) => x.key === key);
      if (r && r.el.scrollWidth - r.el.clientWidth >= left - 1) {
        r.el.scrollLeft = left;
        rowsLeft.delete(key);
        moved = true;
      }
    }
    const a = e.anchor ? anchorCandidates(rows).find((x) => x.key === e.anchor!.key) : undefined;
    const target = a ? anchorScroll(window.scrollY, a.box.getBoundingClientRect().top, e.anchor!.top) : e.scrollY ?? 0;
    const room = document.documentElement.scrollHeight - window.innerHeight;
    if (Math.abs(window.scrollY - target) > 2 && room + 2 >= target) {
      window.scrollTo(0, target);
      moved = true;
    }
    if (!focused && e.focus && !matchMedia("(hover: none) and (pointer: coarse)").matches) {
      const el = [...document.querySelectorAll<HTMLElement>("[data-nav]")].find((n) => (n.getAttribute("aria-label") ?? n.textContent?.trim().slice(0, 80)) === e.focus);
      if (el) {
        el.focus({ preventScroll: true });
        focused = true;
      }
    }
    const placed = !rowsLeft.size && Math.abs(window.scrollY - target) <= 2;
    still = placed && !moved ? still + 1 : 0;
    if (still >= SETTLED_TICKS || Date.now() - t0 > RESTORE_MS) return done();
    window.setTimeout(tick, 100);
  };
  window.setTimeout(tick, 0);
}

// Registered before any useRoute listener, so screens render knowing whether this was a Back
window.addEventListener("hashchange", () => {
  stopRestore?.(); // a restore still settling belongs to the screen being left
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
