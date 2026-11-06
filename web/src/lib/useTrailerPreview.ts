// Browser side of trailer previews: waits out the debounce, checks the gate, resolves the source (cached per
// session), and hands back a playable URL. Pure rules live in trailerPreview.ts.
import { useEffect, useState } from "react";
import { localTrailers } from "../api/jellyfin";
import { prefsNow } from "../api/me";
import { streamUrl, vodItem } from "../api/iptv";
import { trailerStreamUrl } from "../api/playback";
import { acquirePreview, iptvPlayable, previewGate, readEnv, settle, targetKey, TrailerCache, type PreviewTarget, type Trigger } from "./trailerPreview";

export const TRAILER_DELAY_MS = 2500;

const cache = new TrailerCache(2);
window.addEventListener("jellytip-session", () => cache.clear()); // per user (and token in the URL)

async function resolve(t: Exclude<PreviewTarget, { kind: "direct" }>): Promise<string | null> {
  const key = targetKey(t);
  if (!cache.shouldFetch(key)) return cache.get(key);
  try {
    if (t.kind === "jf") {
      const tr = (await localTrailers(t.id))[0];
      const url = tr ? trailerStreamUrl(tr.Id) : null;
      cache.record(key, url, true); // the library answers once
      return url;
    }
    const a = await vodItem(t.id);
    if (a.status !== 200 || !a.data) {
      cache.record(key, null); // may be a hiccup: one re-check on a later focus
      return null;
    }
    const raw = a.data.trailer;
    const url = iptvPlayable(raw, streamUrl);
    // A path the companion has not cached yet is a miss we ask again once; a web link will never play inline
    cache.record(key, url, !!raw && !url);
    return url;
  } catch {
    cache.record(key, null);
    return null;
  }
}

/**
 * The URL to preview for `target`, or null. Starts after `delay` of rest on the same target and only when the
 * gate allows (prefs, rotating mix, touch, reduced motion, Save-Data, hidden tab). Changing target cancels the
 * pending start, so titles passed quickly cost nothing.
 */
export function useTrailerPreview(target: PreviewTarget | null, o: { trigger: Trigger; rotating?: boolean; delay?: number } = { trigger: "focus" }): string | null {
  const [url, setUrl] = useState<string | null>(null);
  const key = target ? targetKey(target) : null;
  useEffect(() => {
    setUrl(null);
    if (!target) return;
    let alive = true;
    const cancel = settle(o.delay ?? TRAILER_DELAY_MS, () => {
      const g = previewGate({ autoplayPref: prefsNow()?.playback.trailerAutoplay, env: readEnv(), trigger: o.trigger, rotating: o.rotating });
      if (!g.ok) return;
      if (target.kind === "direct") setUrl(target.url);
      else void resolve(target).then((u) => alive && setUrl(u));
    });
    return () => {
      alive = false;
      cancel();
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
