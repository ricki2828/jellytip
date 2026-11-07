// Home "My Channels" row: which channels show and what each card says (pure; tested in web/test/channelsRow.test.ts).
import { CHANNELS, channelViable, isIptvId, itemLabel, meanAff, poolOf, schedule, tuneTarget, type ChanItem, type ChannelDef, type Target } from "./channels.ts";

export interface CardModel {
  def: ChannelDef;
  nowTitle: string;
  nowSub: string;
  progress: number;
  /** "Next: <title> at <time>"; null when the channel has only one slot to show. */
  next: { title: string; atMs: number } | null;
  /** Library item whose artwork the card shows, or an IPTV art URL. */
  artId: string | null;
  artUrl: string | null;
  /** Where Tune in goes (lib/channels.tunePath input). */
  target: Target;
}

/** Channels with enough playable items and not hidden, in the given order (the built-in channels unless told otherwise). */
export function visibleChannels(loads: Record<string, ChanItem[] | undefined>, hidden: string[], defs: ChannelDef[] = CHANNELS): ChannelDef[] {
  return defs.filter((c) => {
    const items = loads[c.id];
    return !!items && !hidden.includes(c.id) && channelViable(items, c);
  });
}

/** What the card for `def` shows at `nowMs`; null when the channel has nothing scheduled. */
export function cardModel(def: ChannelDef, items: ChanItem[], nowMs: number): CardModel | null {
  const { slots, offsetS, progress } = schedule(poolOf(items, def), def.id, nowMs, 3);
  const target = tuneTarget(slots, offsetS);
  if (!slots.length || !target) return null;
  const cur = slots[0].item;
  const lab = itemLabel(cur);
  const nxt = slots[1];
  const iptv = isIptvId(cur.Id);
  return {
    def,
    nowTitle: lab.title,
    nowSub: lab.sub,
    progress: Math.max(0, Math.min(1, progress)),
    next: nxt ? { title: itemLabel(nxt.item).title, atMs: nxt.startMs } : null,
    artId: iptv ? null : cur.SeriesId ?? cur.Id,
    artUrl: cur.Art ?? null,
    target,
  };
}

/**
 * Channels ordered by how well what is on now (at `nowMs`) fits the person's taste, best first; a channel whose item has no
 * affinity counts as neutral (0.5), and ties keep the given order. Nothing carrying an affinity at all → the order untouched.
 * Callers order on load, not on every clock tick, so cards do not jump while someone is looking.
 */
export function orderByAffinity(defs: ChannelDef[], loads: Record<string, ChanItem[] | undefined>, nowMs: number): ChannelDef[] {
  const score = (d: ChannelDef): number | null => {
    const items = loads[d.id];
    if (!items) return null;
    const { slots } = schedule(poolOf(items, d), d.id, nowMs, 1);
    return slots[0]?.item.Aff ?? meanAff(items) ?? null;
  };
  const scored = defs.map((d, i) => ({ d, i, a: score(d) }));
  if (!scored.some((x) => x.a != null)) return defs;
  return scored.sort((p, q) => (q.a ?? 0.5) - (p.a ?? 0.5) || p.i - q.i).map((x) => x.d);
}
