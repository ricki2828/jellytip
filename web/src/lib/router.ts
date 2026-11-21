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
  const els = [...document.querySelectorAll<HTMLElement>("#root .row-scroll")].filter((e) => e.offsetParent);
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
    // A row still waiting to be put back (it hasn't loaded yet) keeps its place in the entry instead of being forgotten
    const moved = rowScrolls(rows.map((r) => ({ key: r.key, left: r.el.scrollLeft })));
    const all = pendingRows.size ? { ...Object.fromEntries(pendingRows), ...moved } : moved;
    nav.save(Math.round(window.scrollY), lastFocus, {
      rows: all,
      anchor: pickAnchor(anchorCandidates(rows).map((r) => ({ key: r.key, ...rect(r.box) })), 0, window.innerHeight) ?? undefined,
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

/** Row offsets from the entry being restored that aren't applied yet (their row hasn't loaded or isn't wide enough). */
const pendingRows = new Map<string, number>();
const ROW_CLAMP_AFTER_MS = 1500;

/**
 * Back to an entry. Vertical: scroll so the anchor (row or card) sits where it was, else the raw scrollY; when the page is still
 * too short, scroll as far as it goes (that also pulls in a lazily loaded next page). It keeps correcting until the layout has been
 * still for half a second, up to 5 s, and the person's first touch, wheel, key or press ends it. Horizontal: each moved row gets
 * its scrollLeft once it is wide enough; that goes on after a touch (unless the touch is on that row) and, for a row that never
 * gets wide enough, ends at its widest after 1.5 s. Offsets not applied by the end stay in the entry (pendingRows).
 */
function restore(e: NavEntry) {
  restoring = true;
  const gen = ++restoreGen;
  const t0 = Date.now();
  pendingRows.clear();
  for (const [k, v] of Object.entries(e.rows ?? {})) pendingRows.set(k, v);
  let still = 0;
  let vertical = true;
  let focused = false;
  const STOP = ["touchstart", "wheel", "keydown", "pointerdown"] as const;
  const endVertical = () => {
    vertical = false;
    restoring = false; // saves resume (and carry pendingRows)
  };
  const done = () => {
    for (const ev of STOP) window.removeEventListener(ev, cancel, true);
    if (gen !== restoreGen) return;
    restoreGen++;
    endVertical();
    stopRestore = null;
  };
  const cancel = (ev: Event) => {
    // the person took over: stop moving the page; a touch on a row that is still waiting cancels that row too
    const row = (ev.target as HTMLElement | null)?.closest?.(".row-scroll");
    if (row) {
      const hit = screenRows().find((r) => r.el === row);
      if (hit) pendingRows.delete(hit.key);
    }
    endVertical();
  };
  for (const ev of STOP) window.addEventListener(ev, cancel, { capture: true, passive: true });
  stopRestore = done;
  const tick = () => {
    if (gen !== restoreGen) return;
    let moved = false;
    const rows = screenRows();
    const age = Date.now() - t0;
    for (const [key, left] of pendingRows) {
      const r = rows.find((x) => x.key === key);
      if (!r) continue;
      const max = r.el.scrollWidth - r.el.clientWidth;
      // a row still showing its skeleton cards isn't its real width yet: never clamp to that
      if (max >= left - 1 || (age > ROW_CLAMP_AFTER_MS && max > 0 && !r.el.querySelector(".card-skeleton"))) {
        r.el.scrollLeft = Math.min(left, max);
        pendingRows.delete(key);
        moved = true;
      }
    }
    let placed = true;
    if (vertical) {
      const a = e.anchor ? anchorCandidates(rows).find((x) => x.key === e.anchor!.key) : undefined;
      const target = a ? anchorScroll(window.scrollY, a.box.getBoundingClientRect().top, e.anchor!.top) : e.scrollY ?? 0;
      const room = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      const want = Math.min(target, room); // too short yet: as far as it goes, so a lazy next page loads in
      if (Math.abs(window.scrollY - want) >= 1) {
        window.scrollTo(0, want);
        moved = true;
      }
      placed = room + 2 >= target && Math.abs(window.scrollY - target) < 1;
      if (!focused && e.focus && !matchMedia("(hover: none) and (pointer: coarse)").matches) {
        const el = [...document.querySelectorAll<HTMLElement>("[data-nav]")].find((n) => (n.getAttribute("aria-label") ?? n.textContent?.trim().slice(0, 80)) === e.focus);
        if (el) {
          el.focus({ preventScroll: true });
          focused = true;
        }
      }
    }
    still = placed && !moved ? still + 1 : 0;
    if (vertical && still >= SETTLED_TICKS) endVertical();
    if ((!vertical && !pendingRows.size) || age > RESTORE_MS) return done();
    window.setTimeout(tick, 100);
  };
  window.setTimeout(tick, 0);
}

// Registered before any useRoute listener, so screens render knowing whether this was a Back
window.addEventListener("hashchange", () => {
  stopRestore?.(); // a restore still settling belongs to the screen being left
  pendingRows.clear();
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
