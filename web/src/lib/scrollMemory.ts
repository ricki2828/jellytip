// Where the person was on a screen, so Back lands exactly there (Ricki 21-Nov, phone: "it puts me somewhere different").
// Pure parts: row keys, the anchor row, and the corrected scroll. The router (lib/router.ts) reads and applies them.
//
// Vertical: a raw scrollY lands somewhere else when anything above changes height after Back (Continue watching and Next up
// are re-read; a row fills in late). So the entry also keeps an anchor: the first row on screen and how far from the top
// it sat; Back scrolls so that row is at the same place again. Horizontal: each row's scrollLeft, by row key.

/** Row keys from their titles: stable across a re-render (an index isn't: thin rows are dropped). Repeats get #2, #3; untitled rows their index. */
export function rowKeys(titles: (string | null | undefined)[]): string[] {
  const seen = new Map<string, number>();
  return titles.map((t, i) => {
    const base = t?.replace(/\s+/g, " ").trim();
    if (!base) return `#${i}`;
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    return n === 1 ? base : `${base}#${n}`;
  });
}

export interface RowBox {
  key: string;
  top: number;
  bottom: number;
}

/** The first row still on screen below [viewTop] (the fixed header's bottom): what the person was looking at. */
export function pickAnchor(rows: RowBox[], viewTop = 0): { key: string; top: number } | null {
  const r = rows.find((x) => x.bottom > viewTop + 8);
  return r ? { key: r.key, top: Math.round(r.top) } : null;
}

/** The scrollY that puts the anchor row back at its saved distance from the top. */
export const anchorScroll = (scrollY: number, topNow: number, savedTop: number): number => Math.max(0, Math.round(scrollY + topNow - savedTop));

/** Only rows the person actually moved (scrollLeft > 0) are kept, and at most 40, so history.state stays small. */
export function rowScrolls(rows: { key: string; left: number }[]): Record<string, number> | undefined {
  const out: Record<string, number> = {};
  let n = 0;
  for (const r of rows) {
    if (r.left > 0 && n < 40) {
      out[r.key] = Math.round(r.left);
      n++;
    }
  }
  return n ? out : undefined;
}
