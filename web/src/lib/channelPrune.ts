// Channel pruning inside a group (pure; tested in web/test/iptv.test.ts). Contract: ~/ai/plans/jellytip-live-sport.md
// "Channel pruning inside groups". GET …/providers/{pid}/groups/{catId}/channels → {channels:[{sid, name, logo, num, hidden,
// sports, sportShare, lastSport?, quality?}], policy}; PUT {hide:{sid:bool}, policy?}.

export type SportsLevel = "yes" | "sometimes" | "no" | "unknown";
export type Policy = "all" | "sports" | "sports-sometimes" | null;

export interface PruneChannel {
  sid: string;
  name: string;
  logo: string | null;
  num: number | null;
  hidden: boolean;
  sports: SportsLevel;
  /** 0..1 */
  share: number | null;
  lastSport: string | null;
}

export interface PruneList {
  channels: PruneChannel[];
  policy: Policy;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const LEVELS: SportsLevel[] = ["yes", "sometimes", "no", "unknown"];

export function parsePrune(raw: unknown): PruneList | null {
  const r = obj(raw);
  if (!r || !Array.isArray(r.channels)) return null;
  const seen = new Set<string>();
  const channels: PruneChannel[] = [];
  for (const x of r.channels) {
    const c = obj(x);
    const sid = c?.sid != null ? String(c.sid) : null;
    if (!c || !sid || seen.has(sid)) continue;
    seen.add(sid);
    channels.push({
      sid,
      name: str(c.name) ?? sid,
      logo: str(c.logo),
      num: typeof c.num === "number" ? c.num : null,
      hidden: c.hidden === true,
      sports: LEVELS.includes(c.sports as SportsLevel) ? (c.sports as SportsLevel) : "unknown",
      share: typeof c.sportShare === "number" && isFinite(c.sportShare) ? c.sportShare : null,
      lastSport: str(c.lastSport),
    });
  }
  const p = r.policy;
  return { channels, policy: p === "all" || p === "sports" || p === "sports-sometimes" ? p : null };
}

export type Chip = "all" | "yes" | "sometimes" | "no" | "unknown" | "hidden";
export const CHIPS: [Chip, string][] = [
  ["all", "All"],
  ["yes", "Sports"],
  ["sometimes", "Sometimes"],
  ["no", "No sport"],
  ["unknown", "Unknown"],
  ["hidden", "Hidden"],
];

/** The channels a search text and a chip leave in view. */
export function filterChannels(list: PruneChannel[], q: string, chip: Chip): PruneChannel[] {
  const t = q.trim().toLowerCase();
  return list.filter((c) => (chip === "all" ? true : chip === "hidden" ? c.hidden : c.sports === chip) && (!t || c.name.toLowerCase().includes(t)));
}

export const counts = (list: PruneChannel[]) => ({ total: list.length, hidden: list.filter((c) => c.hidden).length });

/** "Sport 82%" / "Sometimes · NFL last week" / "No sport" / "Not known". */
export function sportBadge(c: Pick<PruneChannel, "sports" | "share" | "lastSport">): string {
  const pct = c.share != null ? Math.round(c.share * 100) : null;
  switch (c.sports) {
    case "yes":
      return pct != null ? `Sport ${pct}%` : "Sport";
    case "sometimes":
      return c.lastSport ? `Sometimes · ${c.lastSport} last week` : pct != null ? `Sometimes · ${pct}%` : "Sometimes";
    case "no":
      return "No sport";
    default:
      return "Not known";
  }
}

/** The channels with their hidden flag set for the picked sids (what the list shows once a bulk edit is saved). */
export const withHidden = (list: PruneChannel[], sids: Set<string>, hidden: boolean): PruneChannel[] => list.map((c) => (sids.has(c.sid) ? { ...c, hidden } : c));

export const POLICIES: [Exclude<Policy, null> | "manual", string][] = [
  ["all", "All channels"],
  ["sports", "Sports channels only"],
  ["sports-sometimes", "Sports + sometimes sports"],
  ["manual", "Manual"],
];

/** Visible slice of a fixed-height list: first/last index to render plus a little overscan. */
export function windowSlice(scrollTop: number, viewport: number, rowH: number, total: number, overscan = 8): { from: number; to: number } {
  const from = Math.max(0, Math.floor(scrollTop / rowH) - overscan);
  const to = Math.min(total, Math.ceil((scrollTop + viewport) / rowH) + overscan);
  return { from, to };
}

// ---------- "Refresh guide now" ----------

/** "Last updated 2 h ago" / "just now"; null when there is no run time. `at` is epoch seconds (or ms). */
export function lastUpdated(at: number | null | undefined, now: number): string | null {
  if (!at || at <= 0) return null;
  const t = at > 1e12 ? at / 1000 : at;
  const s = Math.max(0, now - t);
  if (s < 90) return "Last updated just now";
  if (s < 3600) return `Last updated ${Math.round(s / 60)} min ago`;
  if (s < 86400 * 2) return `Last updated ${Math.round(s / 3600)} h ago`;
  return `Last updated ${Math.round(s / 86400)} days ago`;
}

/** The 429 text: "Try again in 7 min" from `retryAfter_s` (seconds). */
export function retryText(retryAfterS: unknown): string {
  const s = typeof retryAfterS === "number" && isFinite(retryAfterS) && retryAfterS > 0 ? retryAfterS : 600;
  const m = Math.max(1, Math.ceil(s / 60));
  return `Try again in ${m} min`;
}
