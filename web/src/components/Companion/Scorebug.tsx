// One-line pill in the video's top-right safe area: score, clock, period. Event-driven: it shows when the revealed score / period / a key
// moment changes, hides after 8 s unless pinned (the pill is a button: select it to pin or unpin). Lives inside the video wrap, so it
// moves with the picture when the panel squeezes it. Re-renders itself at 1 Hz only while visible; nothing above it re-renders.
import { memo, useEffect, useState } from "react";
import { scorebugText, scorebugVisible, tickClock } from "../../lib/companion";
import type { FeedView } from "./useLiveFeed";
import "./Companion.css";

const PIN_KEY = "jellytip.scorebugPin";
const readPin = () => {
  try {
    return localStorage.getItem(PIN_KEY) === "1";
  } catch {
    return false;
  }
};

export const Scorebug = memo(function Scorebug({ view, panelOpen }: { view: FeedView; panelOpen: boolean }) {
  const [pinned, setPinned] = useState(readPin);
  const [now, setNow] = useState(() => Date.now());
  const clock = tickClock(view.clock, view.sport, view.clockRunning, view.shownAtMs != null ? (now - view.shownAtMs) / 1000 : 0);
  const text = scorebugText({ ...view, clock });
  const visible = !!text && scorebugVisible({ nowMs: now, changedAtMs: view.changedAtMs, pinned, panelOpen });
  // 1 Hz, and only while the pill is on screen or about to be
  useEffect(() => {
    setNow(Date.now());
    if (!text || panelOpen || (!pinned && view.changedAtMs == null && !view.clockRunning)) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [view.changedAtMs, view.clockRunning, pinned, panelOpen, !!text]);
  if (!text || !visible) return null;
  const toggle = () => {
    const n = !pinned;
    setPinned(n);
    try {
      localStorage.setItem(PIN_KEY, n ? "1" : "0");
    } catch {
      /* private mode: pinned for this session */
    }
  };
  return (
    <button type="button" className={`cp-bug${pinned ? " pinned" : ""}`} data-nav aria-pressed={pinned} title={pinned ? "Unpin the score" : "Keep the score on screen"} onClick={toggle}>
      <b>{text.teams[0]}</b>
      <span className="cp-bug-score">
        {text.score[0]} – {text.score[1]}
      </span>
      <b>{text.teams[1]}</b>
      {text.when && <span className="cp-bug-when">{text.when}</span>}
    </button>
  );
});
