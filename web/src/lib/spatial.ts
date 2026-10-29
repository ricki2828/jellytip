// Arrow-key focus movement between [data-nav] elements (buttons/links), so the app works like the TV one from a keyboard.
// Esc goes back. Screens that need the keys themselves (player) listen on document and call preventDefault.

import { back } from "./router";

type Dir = "left" | "right" | "up" | "down";
const KEYS: Record<string, Dir> = { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" };

function candidates(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>("[data-nav]")).filter((el) => {
    if ((el as HTMLButtonElement).disabled) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  });
}

function pick(from: DOMRect, dir: Dir, els: HTMLElement[], current: Element | null): HTMLElement | null {
  const cx = from.left + from.width / 2;
  const cy = from.top + from.height / 2;
  let best: HTMLElement | null = null;
  let bestScore = Infinity;
  for (const el of els) {
    if (el === current) continue;
    const r = el.getBoundingClientRect();
    const ex = r.left + r.width / 2;
    const ey = r.top + r.height / 2;
    let main: number;
    let cross: number;
    switch (dir) {
      case "right":
        main = r.left - from.right + 1;
        cross = Math.abs(ey - cy);
        if (r.left < from.right - 4 || main < -4) continue;
        break;
      case "left":
        main = from.left - r.right + 1;
        cross = Math.abs(ey - cy);
        if (r.right > from.left + 4 || main < -4) continue;
        break;
      case "down":
        main = r.top - from.bottom + 1;
        cross = r.right < from.left ? from.left - r.right : r.left > from.right ? r.left - from.right : 0;
        if (r.top < from.bottom - 4) continue;
        break;
      case "up":
        main = from.top - r.bottom + 1;
        cross = r.right < from.left ? from.left - r.right : r.left > from.right ? r.left - from.right : 0;
        if (r.bottom > from.top + 4) continue;
        break;
    }
    // Horizontal moves stay in their row; vertical moves prefer the nearest row, then the closest column
    const score = dir === "left" || dir === "right" ? main + cross * 4 : main * 2 + cross + Math.abs(ex - cx) * 0.05;
    if (score < bestScore) {
      bestScore = score;
      best = el;
    }
  }
  return best;
}

export function focusFirst(root: ParentNode = document) {
  const el = root.querySelector<HTMLElement>("[data-autofocus]") ?? root.querySelector<HTMLElement>("[data-nav]");
  el?.focus({ preventScroll: false });
}

export function installSpatialNav() {
  window.addEventListener("keydown", (e) => {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    const target = e.target as HTMLElement | null;
    const typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
    if (e.key === "Escape") {
      if (typing) {
        target!.blur();
        return;
      }
      e.preventDefault();
      back();
      return;
    }
    const dir = KEYS[e.key];
    if (!dir) return;
    if (typing && (dir === "left" || dir === "right")) return;
    const els = candidates();
    if (els.length === 0) return;
    const active = document.activeElement as HTMLElement | null;
    e.preventDefault();
    if (!active || active === document.body || !active.hasAttribute("data-nav")) {
      (els.find((el) => el.hasAttribute("data-autofocus")) ?? els[0]).focus();
      return;
    }
    const next = pick(active.getBoundingClientRect(), dir, els, active);
    if (next) {
      next.focus({ preventScroll: true });
      next.scrollIntoView({ block: dir === "up" || dir === "down" ? "center" : "nearest", inline: "nearest", behavior: "smooth" });
    } else if (dir === "up") {
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  });
}
