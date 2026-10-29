// Where specials go among a page's normal rows (pure; tested in web/test/specialsPlacement.test.ts).
// `position` N = before the Nth normal row (0 = first); anything past the last row goes at the end. Ties keep the
// server's order.

export interface Placeable {
  id: string;
  position: number;
}

/** slots[i] = specials before row i (i < rowCount); slots[rowCount] = specials after the last row. */
export function placeSpecials<T extends Placeable>(rowCount: number, specials: T[]): T[][] {
  const slots: T[][] = Array.from({ length: rowCount + 1 }, () => []);
  const seen = new Set<string>();
  for (const s of specials) {
    if (seen.has(s.id)) continue; // a duplicate id would render twice
    seen.add(s.id);
    const p = Number.isFinite(s.position) && s.position > 0 ? Math.floor(s.position) : 0;
    slots[Math.min(p, rowCount)].push(s);
  }
  return slots;
}
