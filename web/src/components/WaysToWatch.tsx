// Details "Ways to watch": one row per way (availabilityOf), best first, each with its existing action.
import { useEffect, useState } from "react";
import { downloadsAvailable, getDownload, subscribe } from "../lib/downloads";
import type { Way } from "../lib/availability";
import { AvailBadge } from "./AvailBadge";
import { FailNote } from "./FailNote";
import "./WaysToWatch.css";

/** True while a finished offline copy of `itemId` is on this device (phone app only; false elsewhere). */
export function useDownloaded(itemId: string | null | undefined): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    if (!downloadsAvailable || !itemId) return setOn(false);
    let alive = true;
    const load = () => void getDownload(itemId).then((r) => alive && setOn(r?.state === "done"), () => undefined);
    load();
    const off = subscribe(load);
    return () => {
      alive = false;
      off();
    };
  }, [itemId]);
  return on;
}

/** Button label per kind. */
const ACTION: Record<Way["kind"], string | null> = { library: "Play", downloaded: "Play offline", iptv: "Play via IPTV", request: "Request", unavailable: null };

export function WaysToWatch({ ways, onAct }: { ways: readonly Way[]; onAct: (w: Way) => void }) {
  return (
    <section className="row ways" aria-label="Ways to watch">
      <h2 className="row-title">Ways to watch</h2>
      <ul className="ways-list">
        {ways.map((w, i) => {
          const label = ACTION[w.kind];
          return (
            <li key={w.key} className={`way way-${w.kind}`}>
              <div className="way-text">
                <span className="way-title">
                  {w.title} <AvailBadge badge={w.badge} />
                </span>
                {(w.quality.length > 0 || w.language) && (
                  <span className="way-meta">{[...w.quality, w.language].filter(Boolean).join("  ·  ")}</span>
                )}
                {w.detail && <span className="way-meta dim">{w.detail}</span>}
                {w.iptvId && <FailNote id={w.iptvId} className="way-meta fail-note" />}
              </div>
              {label && (
                <button className={`btn${i === 0 ? " primary" : ""}`} data-nav onClick={() => onAct(w)} aria-label={`${label}: ${w.title}`}>
                  {label}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
