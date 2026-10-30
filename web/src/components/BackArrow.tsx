// The back arrow for screens and overlays: the same round arrow as the phone nav's. `onClick` defaults to the app's one Back
// (an open sheet first, then the previous screen); a close "×" for sheets is SheetClose.
import { useEffect } from "react";
import { systemBack } from "../lib/router";

export function BackArrow({ onClick, className = "" }: { onClick?: () => void; className?: string }) {
  return (
    <button type="button" className={`page-back ${className}`.trim()} data-nav onClick={onClick ?? systemBack} aria-label="Back" title="Back">
      <svg viewBox="0 0 24 24" aria-hidden>
        <path d="M14.5 5.5 8 12l6.5 6.5" />
      </svg>
    </button>
  );
}

/** Close "×" in the corner of a sheet. */
export function SheetClose({ onClose }: { onClose: () => void }) {
  return (
    <button type="button" className="sheet-x" data-nav onClick={onClose} aria-label="Close" title="Close">
      <span aria-hidden>×</span>
    </button>
  );
}

/** Escape / Backspace (and so Android's back button, which sends Escape) closes this overlay first, before any navigation. */
export function useEscapeClose(onClose: () => void, active = true) {
  useEffect(() => {
    if (!active) return;
    const on = (e: KeyboardEvent) => {
      if (e.key !== "Escape" && e.key !== "Backspace") return;
      if ((e.target as HTMLElement | null)?.tagName === "INPUT") return;
      e.preventDefault();
      e.stopPropagation();
      onClose();
    };
    document.addEventListener("keydown", on, true);
    return () => document.removeEventListener("keydown", on, true);
  }, [onClose, active]);
}
