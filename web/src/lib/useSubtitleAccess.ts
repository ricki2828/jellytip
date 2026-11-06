import { useEffect, useState } from "react";
import { getSession } from "../api/jellyfin";
import { loadSubtitlePermission } from "../api/subtitles";
import { canManageSubtitles, needsPolicyLookup } from "./remoteSubtitles";

/** Same gate as the TV app: administrator, or the "Allow subtitle management" policy. Re-evaluates on session change. */
export function useCanManageSubtitles(): boolean {
  const [, bump] = useState(0);
  useEffect(() => {
    const on = () => bump((n) => n + 1);
    window.addEventListener("jellytip-session", on);
    if (needsPolicyLookup(getSession())) void loadSubtitlePermission();
    return () => window.removeEventListener("jellytip-session", on);
  }, []);
  return canManageSubtitles(getSession());
}
