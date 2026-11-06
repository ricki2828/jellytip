// The page's single freshness display. Owns its own clock so a tick re-renders only this, not the whole page.
import { useEffect, useState } from "react";
import { fantasyMissing, retryFantasyRefresh } from "../api/fantasy";
import type { FantasyLive } from "../lib/fantasy";
import { ageLabel } from "../lib/fantasyGame";
import { snapshotStale, sourceAgeSeconds } from "../lib/fantasyPage";

export function FantasyFreshness({ live, failed, lastCheck }: { live: FantasyLive; failed: boolean; lastCheck: number | null }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(t);
  }, []);
  // A fresh snapshot or check shows its age right away
  useEffect(() => setNow(Date.now()), [live.updatedAt, lastCheck, failed]);
  const age = sourceAgeSeconds(live.updatedAt, now);
  const stale = snapshotStale(live, failed, now);
  const hasScores = live.leagues.length > 0;
  return <>
    <div className="fantasy-freshness">
      <p className={stale ? "fantasy-sum-age stale" : "fantasy-sum-age"}>{ageLabel(age) ?? "Update time unavailable"}{live.updatedAt != null && ` · ${new Date(live.updatedAt * 1000).toLocaleString()}`}</p>
      <p>Last successful check: {lastCheck != null ? new Date(lastCheck).toLocaleTimeString() : "Not yet checked"}</p>
    </div>
    {stale && !fantasyMissing() && <div className="fantasy-stale" role="status"><p>{failed ? "The latest check failed." : age == null ? "The source update time is unavailable." : "Live player data is older than 90 seconds."} {hasScores ? "Showing the last available scores." : "Scores are currently unavailable."}</p><button type="button" onClick={() => void retryFantasyRefresh()}>Retry</button></div>}
  </>;
}
