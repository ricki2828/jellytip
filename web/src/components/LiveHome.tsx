import { useEffect } from "react";
import { hasLiveTv } from "../api/iptv";
import { afterStartup } from "../lib/startup";
import { go } from "../lib/router";
import { liveDefault } from "../lib/navModel";

/** #/live: lands on the Guide when live TV is opted in, else My Channels (replaces the history entry, so Back skips this one). */
export function LiveHome() {
  useEffect(() => {
    let alive = true;
    void afterStartup()
      .then(hasLiveTv)
      .catch(() => false)
      .then((v) => alive && go(`/${liveDefault(v)}`, true));
    return () => {
      alive = false;
    };
  }, []);
  return null;
}
