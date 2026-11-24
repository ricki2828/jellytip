// "Hide this channel" from the Live TV guide (TV 7a58289c / 79553994 / bb68bfdb): owner only, one channel at a time, with an
// 8 s Undo. Server (owner only, 404 otherwise): PUT v1/iptv/providers/{pid}/groups/{catId}/channels {hide: {sid: bool}}. It
// hides the channel in that provider for everyone who uses it, like the pruning screen. Pure (test/hideChannel.test.ts).

export const UNDO_MS = 8_000;

export interface HideTarget {
  pid: string;
  cat: string;
  sid: string;
}

interface ChannelLike {
  id: string;
  providerId: number | string;
  group: string;
}

/** A channel just hidden: where it sat, and the guide group it was hidden from (Undo puts it back only there). */
export interface HiddenChannel<C extends ChannelLike = ChannelLike> {
  ch: C;
  index: number;
  group: string | null;
}

/** The call's ids for [ch] when the person owns its provider; null = not offered (shared provider, unknown group, not loaded). */
export function hideTarget(ch: ChannelLike, owned: ReadonlySet<string> | null): HideTarget | null {
  const pid = String(ch.providerId ?? "");
  if (!pid || !owned || !owned.has(pid)) return null;
  const cat = ch.group.startsWith(`${pid}:`) ? ch.group.slice(pid.length + 1) : "";
  const sid = ch.id.startsWith(`${pid}:`) ? ch.id.slice(pid.length + 1) : "";
  return cat && sid ? { pid, cat, sid } : null;
}

/** Put [h] back where it was (Undo); unchanged when it is already there. */
export function restoreHidden<C extends ChannelLike>(list: C[], h: HiddenChannel<C>): C[] {
  if (list.some((c) => c.id === h.ch.id)) return list;
  const out = list.slice();
  out.splice(Math.max(0, Math.min(h.index, out.length)), 0, h.ch);
  return out;
}

/** Where focus goes when [id] leaves the list: the next row, else the one above; null = none left. */
export function neighbourOf(ids: string[], id: string): string | null {
  const i = ids.indexOf(id);
  if (i < 0) return null;
  return ids[i + 1] ?? ids[i - 1] ?? null;
}
