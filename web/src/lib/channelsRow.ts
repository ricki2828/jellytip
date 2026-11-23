// Home "My Channels" row: which channels show and what each card says (pure; tested in web/test/channelsRow.test.ts).
import { CHANNELS, channelViable, isIptvId, itemLabel, meanAff, poolOf, schedule, tuneTarget, type ChanItem, type ChannelDef, type Target } from "./channels.ts";

export interface CardModel {
  def: ChannelDef;
  nowTitle: string;
  nowSub: string;
  /** When what is on now ends (for "25 min left"). */
  nowEndMs: number;
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
    nowEndMs: slots[0].endMs,
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

// ---------- Home row: numbers, tile lines, placement (Ricki 23-Nov, TV 268dcba9) ----------

/** Channel numbers, as on the TV: 101 for the first channel in the My Channels order, then up. */
export const channelNumber = (index: number): number => 101 + index;

const pad2 = (n: number) => String(n).padStart(2, "0");
/** "21:30", local time. */
export const hhmm = (ms: number): string => {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

/** "ending" / "25 min left" / "1 h 05 left" (the TV's leftText). */
export function leftText(endMs: number, nowMs: number): string {
  const mins = Math.max(0, Math.floor((endMs - nowMs) / 60_000));
  if (mins < 1) return "ending";
  if (mins < 60) return `${mins} min left`;
  return `${Math.floor(mins / 60)} h ${pad2(mins % 60)} left`;
}

/** The tile's second line: "S2E4 · The Episode · 25 min left" (or "1999 · 1 h 05 left"). */
export const subLine = (m: Pick<CardModel, "nowSub" | "nowEndMs">, nowMs: number): string => [m.nowSub, leftText(m.nowEndMs, nowMs)].filter(Boolean).join(" · ");

/** "Next 21:30 · Title"; "" when nothing follows. */
export const nextLine = (next: CardModel["next"]): string => (next ? `Next ${hhmm(next.atMs)} · ${next.title}` : "");

/** The rows Home draws before its own (server) rows, in order. My Channels sits directly under Continue watching, else first. */
export type LeadRow = "continue" | "mychannels" | "reminders" | "recordings" | "livesport" | "upcoming";
export function homeLeadRows(hasContinue: boolean): LeadRow[] {
  const rest: LeadRow[] = ["reminders", "recordings", "livesport", "upcoming"];
  return hasContinue ? ["continue", "mychannels", ...rest] : ["mychannels", ...rest];
}

/** A channel that failed to load (network, 5xx: never "missing") is asked again after 5 s, 10 s, 20 s… up to 5 min. */
export const channelRetryMs = (failures: number): number => Math.min(5 * 60_000, 5_000 * 2 ** Math.max(0, failures - 1));

/**
 * The channel order for the session (Ricki's Back rule): channels already placed keep their places, channels new to the
 * session (a late load, a retry that came good) go at the end in the given (affinity) order. `prev` = the ids placed so far,
 * including ones not in `ranked` right now (hidden, still loading): they keep their place for when they come back.
 * Returns the channels in order and the ids to remember.
 */
export function mergeChannelOrder(prev: readonly string[] | null, ranked: readonly ChannelDef[]): { order: ChannelDef[]; ids: string[] } {
  if (!prev || !prev.length) return { order: [...ranked], ids: ranked.map((d) => d.id) };
  const byId = new Map(ranked.map((d) => [d.id, d] as const));
  const known = new Set(prev);
  const kept = prev.map((id) => byId.get(id)).filter((d): d is ChannelDef => !!d);
  const added = ranked.filter((d) => !known.has(d.id));
  return { order: [...kept, ...added], ids: [...prev, ...added.map((d) => d.id)] };
}
