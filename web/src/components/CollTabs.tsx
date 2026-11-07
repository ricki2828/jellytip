// Movies | TV Shows | All switch, shared by the Collections hub and Search. Choice persists via lib/collections (loadTab/saveTab).
import { useState } from "react";
import { loadTab, saveTab, type CollTab } from "../lib/collections";
import "../screens/Collections.css";

export const COLL_TAB_LABELS = [["movie", "Movies"], ["tv", "TV Shows"], ["all", "All"]] as const;

/** Tab state that is remembered across screens and sessions. */
export function useCollTab(): [CollTab, (t: CollTab) => void] {
  const [tab, setState] = useState<CollTab>(loadTab);
  return [tab, (t) => { saveTab(t); setState(t); }];
}

export function CollTabs({ tab, onChange }: { tab: CollTab; onChange: (t: CollTab) => void }) {
  return (
    <div className="coll-seg" role="tablist" aria-label="Type">
      {COLL_TAB_LABELS.map(([t, label]) => (
        <button key={t} role="tab" aria-selected={tab === t} className={`coll-seg-btn${tab === t ? " on" : ""}`} data-nav onClick={() => onChange(t)}>{label}</button>
      ))}
    </div>
  );
}
