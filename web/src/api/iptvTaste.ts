// Thumbs, "Not interested" and Watched for IPTV films / series / episodes (contract: ~/ai/state/jellytip-iptvtaste-contract.md).
// Every call to the server lives here so a change of route or body shape is a change to this file only.
// Thumbs are keyed by TMDB id when known (the rating then carries across IPTV copies and the library copy), else by IPTV id.
import { useEffect, useState } from "react";
import { companionCall } from "./companionCall";
import { iptvPost } from "./iptv";
import { dropPersonalCaches } from "./companion";
import { emitSignal } from "./signals";
import { rememberSignal, signalOf } from "./me";
import { tasteKey } from "../lib/iptvVod";

export interface IptvTasteSubject {
  /** IPTV id (`<pid>:m:<sid>` / `<pid>:s:<sid>`) */
  id: string;
  kind: "movie" | "series";
  tmdbId?: number | null;
}


const body = (t: IptvTasteSubject) => ({ iptvId: t.id, kind: t.kind, ...(t.tmdbId ? { tmdbId: t.tmdbId } : {}) });
const ok = (s: number) => s === 200 || s === 201 || s === 204;
const failText = (s: number) => (s === 409 ? "Open the show once so its episodes are known, then try again." : s === 0 ? "The server can't be reached." : `Server said ${s}`);

/** Show what the server said about this title (details payload) in the buttons. */
export function seedTaste(t: IptvTasteSubject, thumb?: 1 | -1 | 0 | null, notInterested?: boolean) {
  if (thumb === undefined && notInterested === undefined) return; // server silent: keep what /v1/me/reactions said
  const cur = signalOf(tasteKey(t));
  rememberSignal(tasteKey(t), { thumb: thumb === undefined ? cur.thumb : thumb || undefined, notInterested: notInterested === undefined ? cur.notInterested : notInterested || undefined });
}

export const useIptvSignal = (t: IptvTasteSubject) => {
  const [, bump] = useState(0);
  useEffect(() => {
    const on = () => bump((n) => n + 1);
    window.addEventListener("jellytip-signals", on);
    return () => window.removeEventListener("jellytip-signals", on);
  }, []);
  return signalOf(tasteKey(t));
};

/** Thumb up / down (0 clears). Same signals as a library thumb so today's personalisation sees it. */
export async function setIptvThumb(t: IptvTasteSubject, value: 1 | -1 | 0): Promise<string | null> {
  const a = await companionCall("POST", "v1/me/thumbs", { ...body(t), value });
  if (!ok(a.status)) return failText(a.status);
  const cur = signalOf(tasteKey(t));
  rememberSignal(tasteKey(t), { thumb: value === 0 ? undefined : value, notInterested: cur.notInterested });
  if (value !== 0) emitSignal({ type: value === 1 ? "thumbUp" : "thumbDown", at: Date.now(), kind: t.kind, iptvId: t.id, ...(t.tmdbId ? { tmdbId: t.tmdbId } : {}) });
  dropPersonalCaches();
  return null;
}

export async function setIptvNotInterested(t: IptvTasteSubject, on: boolean): Promise<string | null> {
  const a = await companionCall(on ? "POST" : "DELETE", "v1/me/not-interested", body(t));
  if (!ok(a.status)) return failText(a.status);
  const cur = signalOf(tasteKey(t));
  rememberSignal(tasteKey(t), { thumb: cur.thumb, notInterested: on || undefined });
  dropPersonalCaches();
  return null;
}

/** Watched / not watched for a film, a whole series (every known episode) or one episode (`series` = its show's id). */
export async function setIptvPlayed(id: string, played: boolean, series?: string): Promise<string | null> {
  const a = await iptvPost<unknown>("v1/iptv/played", { id, played, ...(series ? { series } : {}) });
  if (!ok(a.status)) return failText(a.status);
  dropPersonalCaches(); // rows trim watched titles; Continue watching is fetched fresh
  window.dispatchEvent(new Event("jellytip-signals")); // More like this refetches without it
  return null;
}
