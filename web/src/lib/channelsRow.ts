// Home "My Channels" row: which channels show and what each card says (pure; tested in web/test/channelsRow.test.ts).
import { CHANNELS, channelViable, isIptvId, itemLabel, poolOf, schedule, tuneTarget, type ChanItem, type ChannelDef } from "./channels.ts";

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
  target: { id: string; startS: number; thenId: string | null; title: string };
}

/** Channels with enough playable items and not hidden, in the built-in order. */
export function visibleChannels(loads: Record<string, ChanItem[] | undefined>, hidden: string[]): ChannelDef[] {
  return CHANNELS.filter((c) => {
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
