// Home hero slides (TV port: JellytipHeroCarousel.slides / keep, fe6568ad + 8d4f6ea0). His top live game is ONE slide of the normal
// hero, not a takeover: the game first (at most one), then the active journey's step, then the featured mix. Pure; tested in
// web/test/homeHero.test.ts.

export type HeroSlotKind = "game" | "journey" | "mix";

export interface HeroSlot {
  /** Stable across rebuilds: the slide on screen keeps its place when a game starts / ends or the mix is rebuilt. */
  key: string;
  kind: HeroSlotKind;
  /** The mix slide's index (kind "mix"). */
  index?: number;
}

/** Game, then journey, then the mix; a key seen twice is kept once (the first). */
export function heroSlots(gameKey: string | null, journeyKey: string | null, mixKeys: readonly string[]): HeroSlot[] {
  const out: HeroSlot[] = [];
  const seen = new Set<string>();
  const add = (s: HeroSlot) => {
    if (seen.has(s.key)) return;
    seen.add(s.key);
    out.push(s);
  };
  if (gameKey) add({ key: `m:${gameKey}`, kind: "game" });
  if (journeyKey) add({ key: `j:${journeyKey}`, kind: "journey" });
  mixKeys.forEach((k, index) => add({ key: `t:${k}`, kind: "mix", index }));
  return out;
}

/**
 * Which slide to show after the slides changed. Before the hero has turned (or been stepped) the first slide shows, so his
 * game / journey arriving a moment after the mix still leads (hero first). After that the slide on screen keeps its place by
 * key, so a slide arriving or leaving never swaps what he is looking at; a slide that went away hands over to the one now in
 * its position (clamped), as on the TV.
 */
export function keepSlide(slots: readonly HeroSlot[], key: string | null, index: number, rotated: boolean): number {
  if (!slots.length) return 0;
  if (!rotated) return 0;
  const at = key == null ? -1 : slots.findIndex((s) => s.key === key);
  if (at >= 0) return at;
  return Math.min(Math.max(index, 0), slots.length - 1);
}

/** The next slide, wrapping. */
export const nextSlide = (index: number, size: number): number => (size <= 0 ? 0 : (index + 1) % size);
