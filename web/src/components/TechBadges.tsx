// Tiny monochrome premium-format badges for a tile's bottom-right corner (≤ 2: best video, best object audio).
// Data only from payloads already loaded (lib/techBadges registry); nothing is fetched per tile.
import { tileBadges } from "../lib/techBadges";

export function TechBadges({ tech }: { tech: readonly string[] }) {
  const b = tileBadges(tech);
  if (!b.length) return null;
  return (
    <span className="tech-badges" aria-label={b.join(", ")}>
      {b.map((x) => (
        <span key={x}>{x}</span>
      ))}
    </span>
  );
}
