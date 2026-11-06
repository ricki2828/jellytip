// Browser side of trailer previews: waits out the debounce, checks the gate, resolves the source (cached per
// session), and hands back a playable URL. Pure rules live in trailerPreview.ts.
import { useEffect, useState } from "react";
import { jf, uid, type Item } from "../api/jellyfin";
import { prefsNow } from "../api/me";
import { streamUrl, vodItem } from "../api/iptv";
import { trailerStreamUrl } from "../api/playback";
import { acquirePreview, previewGate, readEnv, settle, type PreviewTarget, type Trigger } from "./trailerPreview";
import { createResolver } from "./trailerResolve";

export const TRAILER_DELAY_MS = 2500;

const resolver = createResolver({
  // jf() throws on failure, so a 401 or a network blip is a transient miss, never "this title has no trailer"
  jf: async (id) => {
    const t = (await jf<Item[]>(`/Users/${uid()}/Items/${id}/LocalTrailers`))[0];
    return t ? trailerStreamUrl(t.Id) : null;
  },
  iptv: async (id) => {
    const a = await vodItem(id);
    return { status: a.status, trailer: a.status === 200 ? a.data?.trailer : null };
  },
  rel: streamUrl,
});
window.addEventListener("jellytip-session", () => resolver.reset()); // per user (and token in the URL); in-flight asks are orphaned

type Connection = EventTarget & { saveData?: boolean };

/**
 * The URL to preview for `target`, or null. Starts after `delay` of rest on the same target and only when the
 * gate allows (prefs, rotating mix, touch, reduced motion, Save-Data, hidden tab). Changing target cancels the
 * pending start, so titles passed quickly cost nothing. A tab that was hidden at the start retries when it is
 * shown; Save-Data or the autoplay pref turning off stops a preview that is playing.
 */
export function useTrailerPreview(target: PreviewTarget | null, o: { trigger: Trigger; rotating?: boolean; delay?: number } = { trigger: "focus" }): string | null {
  const [url, setUrl] = useState<string | null>(null);
  const key = target ? (target.kind === "direct" ? `direct:${target.key}` : `${target.kind}:${target.id}`) : null;
  useEffect(() => {
    setUrl(null);
    if (!target) return;
    let started = false;
    let rested = false;
    let cancelFetch: (() => void) | null = null;
    const gate = () => previewGate({ autoplayPref: prefsNow()?.playback.trailerAutoplay, env: readEnv(), trigger: o.trigger, rotating: o.rotating });
    const begin = () => {
      if (started || !gate().ok) return;
      started = true;
      if (target.kind === "direct") setUrl(target.url);
      else cancelFetch = resolver.start(target, setUrl);
    };
    const stop = () => {
      cancelFetch?.();
      cancelFetch = null;
      started = false;
      setUrl(null);
    };
    const cancelRest = settle(o.delay ?? TRAILER_DELAY_MS, () => {
      rested = true;
      begin();
    });
    const recheck = () => {
      if (!rested) return;
      const g = gate();
      if (g.ok) begin();
      else if (started && g.reason !== "hidden") stop(); // hidden only pauses the video (PreviewVideo / hero)
    };
    const conn = (navigator as Navigator & { connection?: Connection }).connection;
    document.addEventListener("visibilitychange", recheck);
    window.addEventListener("jellytip-prefs", recheck);
    conn?.addEventListener?.("change", recheck);
    return () => {
      cancelRest();
      cancelFetch?.();
      document.removeEventListener("visibilitychange", recheck);
      window.removeEventListener("jellytip-prefs", recheck);
      conn?.removeEventListener?.("change", recheck);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, o.rotating, o.trigger]);
  return url;
}

/** While `url` is set: holds the single preview slot (a later preview stops this one via `stop`) and reports a hidden tab so the caller can pause. */
export function usePreviewLifecycle(id: string, url: string | null, stop: () => void): { hidden: boolean } {
  const [hidden, setHidden] = useState(() => typeof document !== "undefined" && document.visibilityState === "hidden");
  useEffect(() => {
    const on = () => setHidden(document.visibilityState === "hidden");
    document.addEventListener("visibilitychange", on);
    return () => document.removeEventListener("visibilitychange", on);
  }, []);
  useEffect(() => {
    if (!url) return;
    return acquirePreview(id, stop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, url]);
  return { hidden };
}
