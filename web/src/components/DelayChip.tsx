// "Delayed 30 s · Adjust": shows how far live information is held back. Adjust opens a small popover (steppers, "That just happened", reset).
// In replay (catch-up, start-over, a recording) live information is off, and the chip says so instead.
import { useEffect, useRef } from "react";
import { DEFAULT_DELAY_SEC } from "../lib/delayMath.ts";
import "./DelayChip.css";

const STEPS = [-10, -5, 5, 10];

export interface DelayChipProps {
  delaySec: number;
  replay: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSet: (sec: number) => void;
  onCalibrate: () => void;
  canCalibrate: boolean;
}

export function DelayChip({ delaySec, replay, open, onOpenChange, onSet, onCalibrate, canCalibrate }: DelayChipProps) {
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (open) box.current?.querySelector<HTMLElement>(".delay-pop [data-nav]")?.focus();
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) onOpenChange(false); };
    document.addEventListener("pointerdown", away, true);
    return () => document.removeEventListener("pointerdown", away, true);
  }, [open, onOpenChange]);
  if (replay) return <span className="delay-chip replay" role="status">Replay · live updates off</span>;
  const stop = (e: React.SyntheticEvent) => e.stopPropagation();
  return (
    <span className="delay-chip-wrap" ref={box} onClick={stop} onPointerDown={stop} onDoubleClick={stop}>
      <span className="delay-chip" role="status">
        Delayed {delaySec} s
        <span aria-hidden>·</span>
        <button type="button" data-nav aria-expanded={open} aria-haspopup="dialog" title="Line the live updates up with the picture" onClick={() => onOpenChange(!open)}>
          Adjust
        </button>
      </span>
      {open && (
        <div className="delay-pop" role="dialog" aria-label="Adjust delay">
          <p className="delay-pop-val">Delayed {delaySec} s</p>
          <div className="delay-pop-steps">
            {STEPS.map((d) => (
              <button key={d} type="button" className="btn small" data-nav onClick={() => onSet(delaySec + d)}>
                {d > 0 ? `+${d}` : `−${-d}`}
              </button>
            ))}
          </div>
          <button type="button" className="btn small" data-nav disabled={!canCalibrate} onClick={onCalibrate}>
            That just happened
          </button>
          {!canCalibrate && <p className="delay-pop-hint">Available right after a goal, try or wicket shows up.</p>}
          <button type="button" className="btn small" data-nav onClick={() => onSet(DEFAULT_DELAY_SEC)}>
            Reset to {DEFAULT_DELAY_SEC} s
          </button>
          <button type="button" className="btn small" data-nav onClick={() => onOpenChange(false)}>
            Close
          </button>
        </div>
      )}
    </span>
  );
}
