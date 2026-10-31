// Collections hub and single collections. A 404 (a server without the routes) marks it unavailable: nothing is shown for it.
import { useEffect, useState } from "react";
import { companionCall } from "./companionCall";
import { parseHub, type CollectionSection } from "../lib/collections";
import { parseRows, type ServerRow } from "../lib/rows";
import { registerTechs } from "../lib/techBadges";
import { registerLogoStyles } from "../lib/logoStyle";

const EVENT = "jellytip-collections";
const FRESH_MS = 10 * 60_000;
let hub: CollectionSection[] | null = null;
let unavailable = false;
let at = 0;
let inflight: Promise<void> | null = null;

export function loadHub(force = false): Promise<void> {
  if (inflight) return inflight;
  if (unavailable || (!force && hub && Date.now() - at < FRESH_MS)) return Promise.resolve();
  inflight = companionCall("GET", "v1/collections")
    .then((a) => {
      if (a.status === 404) unavailable = true;
      else if (a.status === 200) {
        hub = parseHub(a.data);
        at = Date.now();
      }
      window.dispatchEvent(new Event(EVENT));
    })
    .finally(() => (inflight = null));
  return inflight;
}

window.addEventListener("jellytip-session", () => {
  hub = null;
  unavailable = false;
  at = 0;
  window.dispatchEvent(new Event(EVENT));
});

export const hubNow = () => hub;
export const hubUnavailable = () => unavailable;
/** Ids of collections the hub has (for "See all" on a Home row); null until known. */
export const hubIds = (): Set<string> | null => (hub ? new Set(hub.flatMap((s) => s.items.map((i) => i.id))) : null);
export const onHubChange = (cb: () => void) => {
  window.addEventListener(EVENT, cb);
  return () => window.removeEventListener(EVENT, cb);
};

/** One collection as a row (the exact `?rows=1` row shape); null when it isn't there. */
export async function collectionRow(id: string): Promise<ServerRow | null> {
  const a = await companionCall("GET", `v1/collections/${encodeURIComponent(id)}`);
  if (a.status !== 200 || !a.data || typeof a.data !== "object") return null;
  const raw = a.data as Record<string, unknown>;
  const row = (raw.row && typeof raw.row === "object" ? raw.row : raw) as Record<string, unknown>;
  registerTechs(raw.techs ?? row.techs);
  registerLogoStyles(raw.logoStyles ?? row.logoStyles);
  return parseRows([{ id, title: id, ...row }])?.[0] ?? null;
}

/** Re-render when the hub arrives (rows whose id is in it get a "See all" link). */
export function useHubTick() {
  const [, tick] = useState(0);
  useEffect(() => onHubChange(() => tick((n) => n + 1)), []);
}
