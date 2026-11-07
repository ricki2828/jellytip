// The person's taste profile (GET /v1/me/taste), read once and shared. A server without it (404) or a signed-out person
// means null and the app behaves as it did before profiles. The unreachable case is not remembered, so the next ask retries.
import { useEffect, useState } from "react";
import { companionCall } from "./companionCall";
import { getSession } from "./jellyfin";
import { parseProfile, type TasteProfile } from "../lib/tasteProfile";

const TTL_MS = 30 * 60 * 1000;
/** Longest anything waits on it: a slow companion must not hold up the channels. */
const WAIT_MS = 3000;

export interface ProfileAnswer {
  profile: TasteProfile | null;
  /** false = the server could not be reached in time (the answer may differ next time). */
  settled: boolean;
}

let cache: { u: string; at: number; ans: ProfileAnswer } | null = null;
let inflight: { u: string; p: Promise<ProfileAnswer> } | null = null;

async function fetchProfile(): Promise<ProfileAnswer> {
  const a = await companionCall("GET", "v1/me/taste");
  if (a.status === 200) return { profile: parseProfile((a.data as { profile?: unknown } | null)?.profile ?? a.data), settled: true };
  if (a.status === 0) return { profile: null, settled: false };
  return { profile: null, settled: true }; // 404/501: no taste on this server; 401: signed out
}

/** The profile, from memory when fresh. Never rejects. */
export function tasteProfile(fresh = false): Promise<ProfileAnswer> {
  const u = getSession()?.userId ?? "";
  if (!u) return Promise.resolve({ profile: null, settled: true });
  if (!fresh && cache && cache.u === u && Date.now() - cache.at < TTL_MS) return Promise.resolve(cache.ans);
  if (inflight && inflight.u === u) return inflight.p;
  const p = fetchProfile()
    .then((ans) => {
      if (ans.settled && (getSession()?.userId ?? "") === u) cache = { u, at: Date.now(), ans };
      return ans;
    })
    .catch((): ProfileAnswer => ({ profile: null, settled: false }))
    .finally(() => {
      if (inflight?.p === p) inflight = null;
    });
  inflight = { u, p };
  return p;
}

/** The profile, waiting at most WAIT_MS for it. */
export const tasteProfileSoon = (): Promise<ProfileAnswer> => Promise.race([tasteProfile(), new Promise<ProfileAnswer>((r) => setTimeout(() => r({ profile: null, settled: false }), WAIT_MS))]);

window.addEventListener("jellytip-session", () => {
  cache = null;
  inflight = null;
});

/** undefined while asking; null when there is no profile. */
export function useTasteProfile(): TasteProfile | null | undefined {
  const [p, setP] = useState<TasteProfile | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    let retry: number | undefined;
    const run = () =>
      void tasteProfileSoon().then((a) => {
        if (!alive) return;
        setP(a.profile);
        if (!a.settled) retry = window.setTimeout(run, 15_000); // slow or unreachable: ask again shortly
      });
    run();
    window.addEventListener("jellytip-session", run);
    return () => {
      alive = false;
      window.clearTimeout(retry);
      window.removeEventListener("jellytip-session", run);
    };
  }, []);
  return p;
}
