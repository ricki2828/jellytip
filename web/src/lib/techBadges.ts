// Premium-format badges (pure parts tested in web/test/techBadges.test.ts). Contract "Tech badges" in
// ~/ai/plans/jellytip-10x.md: values 4K, DV, HDR10+, HDR, ATMOS, DTSX, TRUEHD, DTSHDMA, from `tech` on
// /v1/items/{id}/backdrops and `techs` maps (item id → values) on the rows / specials / carousels payloads (same
// placement as logoStyles). Missing = nothing drawn. Tiles show at most two: best video, best audio.

const VIDEO = ["DV", "HDR10+", "HDR"] as const;
const AUDIO = ["ATMOS", "DTSX", "TRUEHD", "DTSHDMA"] as const;
const KNOWN = new Set<string>(["4K", ...VIDEO, ...AUDIO]);

const LABEL: Record<string, string> = { DTSX: "DTS:X", TRUEHD: "TrueHD", DTSHDMA: "DTS-HD MA" };
const label = (v: string) => LABEL[v] ?? v;

/** Known values only, upper-cased, deduplicated; anything else (junk, a newer server's value) drops out. */
export function parseTech(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  for (const x of raw) {
    const v = typeof x === "string" ? x.toUpperCase() : "";
    if (KNOWN.has(v) && !out.includes(v)) out.push(v);
  }
  return out;
}

/** Tile badges: best video (with 4K folded in: "4K DV", "4K HDR") then best object audio (Atmos / DTS:X); ≤ 2. */
export function tileBadges(tech: readonly string[]): string[] {
  const s = new Set(tech);
  const out: string[] = [];
  const v = VIDEO.find((t) => s.has(t));
  if (v) out.push(s.has("4K") ? `4K ${v}` : v);
  else if (s.has("4K")) out.push("4K");
  const a = (["ATMOS", "DTSX"] as const).find((t) => s.has(t));
  if (a) out.push(label(a));
  return out;
}

const LONG: Record<string, string> = { DV: "Dolby Vision", ATMOS: "Dolby Atmos" };

/** Details info line: every value, video then audio, in the contract's order (room for the long names). */
export function detailBadges(tech: readonly string[]): string[] {
  const s = new Set(tech);
  return ["4K", ...VIDEO, ...AUDIO].filter((t) => s.has(t)).map((t) => LONG[t] ?? label(t));
}

/** True when the list says anything about the picture (then the file's own 4K / HDR / DV guesses are redundant). */
export const hasVideoTech = (tech: readonly string[]) => tech.some((t) => t === "4K" || (VIDEO as readonly string[]).includes(t));

// ---------- what the server has said, per item ----------

const techs = new Map<string, string[]>();
if (typeof window !== "undefined") window.addEventListener("jellytip-session", () => techs.clear());

/** Remember a `techs` map (item id → values) or one id + `tech` list from any payload; junk is ignored. */
export function registerTechs(map: unknown) {
  if (!map || typeof map !== "object" || Array.isArray(map)) return;
  for (const [id, v] of Object.entries(map as Record<string, unknown>)) {
    const t = parseTech(v);
    if (t.length) techs.set(id, t);
    else if (Array.isArray(v)) techs.delete(id);
  }
}

/** Known formats for an item ([] = none or not said). */
export const techOf = (id: string | null | undefined): string[] => (id ? techs.get(id) ?? [] : []);
