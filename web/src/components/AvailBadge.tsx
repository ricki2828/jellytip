// The one small availability chip: Library / IPTV / Downloaded / Request (words from lib/availability).
import type { Way } from "../lib/availability";

export function AvailBadge({ badge }: { badge: Way["badge"] }) {
  if (!badge) return null;
  return <span className={`avail-badge avail-${badge.toLowerCase()}`}>{badge}</span>;
}
