import { useEffect, useState } from "react";
import { useFantasy } from "../api/fantasy";
import { hasLiveTv } from "../api/iptv";
import { afterStartup } from "../lib/startup";
import { useRecs } from "../api/recStore";
import { shown, unwatchedCount } from "../lib/recs";
import { useUpcoming } from "../api/upcomingStore";
import type { LiveFlags } from "../lib/navModel";

/** Live TV (the Guide) shows only for someone with opted-in channels (their own provider or one shared with them). */
export function useLiveTv(): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    let alive = true;
    // After the first screen's requests: the Guide tab can appear a moment later
    const check = () => void afterStartup().then(hasLiveTv).then((v) => alive && setOn(v));
    check();
    window.addEventListener("jellytip-iptv", check);
    return () => {
      alive = false;
      window.removeEventListener("jellytip-iptv", check);
    };
  }, []);
  return on;
}

/** What decides the Live room's sub-tabs, plus the count of recordings ready to watch. */
export function useLiveFlags(): LiveFlags & { ready: number } {
  const liveTv = useLiveTv();
  const fantasy = useFantasy(true, true);
  const fantasyLinked = !!(fantasy?.providers.yahoo.linked || fantasy?.providers.sleeper.linked || fantasy?.leagues.length);
  const recs = useRecs();
  const hasRecs = !!recs && shown(recs).length > 0;
  const ready = recs ? unwatchedCount(recs) : 0;
  const hasUpcoming = (useUpcoming()?.length ?? 0) > 0;
  return { liveTv, fantasyLinked, hasRecs, hasUpcoming, ready };
}
