// "Delayed 30 s · Adjust": shows how far live information is held back; Adjust is the "that just happened" calibration tap.
// In replay (catch-up, start-over, a recording) live information is off, and the chip says so instead.
import "./DelayChip.css";

export function DelayChip({ delaySec, replay, onAdjust }: { delaySec: number; replay: boolean; onAdjust: () => void }) {
  if (replay) return <span className="delay-chip replay" role="status">Replay · live updates off</span>;
  return (
    <span className="delay-chip" role="status">
      Delayed {delaySec} s
      <span aria-hidden>·</span>
      <button type="button" data-nav title="Tap right after something happens on screen to line the updates up with the picture" onClick={onAdjust}>
        Adjust
      </button>
    </span>
  );
}
