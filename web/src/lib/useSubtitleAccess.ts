import { useEffect, useState } from "react";
import { getSession, SESSION_META_EVENT } from "../api/jellyfin";
import { loadSubtitlePermission } from "../api/subtitles";
import { canManageSubtitles } from "./remoteSubtitles";

/** Same gate as the TV app: administrator, or the "Allow subtitle management" policy. Re-evaluates on session change. */
export function useCanManageSubtitles(): boolean {
  const [, bump] = useState(0);
  useEffect(() => {
    const on = () => bump((n) => n + 1);
    window.addEventListener("jellytip-session", on);
    window.addEventListener(SESSION_META_EVENT, on);
    void loadSubtitlePermission(); // shared and de-duplicated; a no-op once known
    return () => {
      window.removeEventListener("jellytip-session", on);
      window.removeEventListener(SESSION_META_EVENT, on);
    };
  }, []);
  return canManageSubtitles(getSession());
}
