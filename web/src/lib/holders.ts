// Who holds the provider's connection when /v1/iptv/play answers 409 max_connections (pure; tested in
// web/test/iptv.test.ts). Newer servers send {kind: "recording"|"viewer", recordingId, title, channel, endsAt};
// older ones only {kind: "watch"|"recording", label, userName}. Every new field is optional.

export interface Blocker {
  kind: "recording" | "viewer";
  recordingId?: number | string;
  title?: string;
  channel?: string;
  /** Unix seconds (padded stop for a recording). */
  endsAt?: number;
  who?: string;
}

const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : undefined);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) && v > 0 ? v : undefined);

/** First holder worth telling the person about (a recording wins: it is the one they can stop); null when none parse. */
export function parseBlocker(data: unknown): Blocker | null {
  const d = data as { connection?: { active?: unknown }; holders?: unknown } | null;
  const list = Array.isArray(d?.connection?.active) ? (d?.connection?.active as unknown[]) : Array.isArray(d?.holders) ? (d?.holders as unknown[]) : [];
  const all: Blocker[] = [];
  for (const h of list) {
    if (!h || typeof h !== "object") continue;
    const o = h as Record<string, unknown>;
    const rid = typeof o.recordingId === "number" || typeof o.recordingId === "string" ? o.recordingId : undefined;
    all.push({
      kind: o.kind === "recording" ? "recording" : "viewer",
      recordingId: rid,
      title: str(o.title) ?? str(o.label),
      channel: str(o.channel),
      endsAt: num(o.endsAt),
      who: str(o.userName) ?? str(o.user),
    });
  }
  return all.find((b) => b.kind === "recording" && b.recordingId != null) ?? all[0] ?? null;
}

export const clock = (unix: number) => new Date(unix * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

/** The pop-up sentence. */
export function blockerText(b: Blocker): string {
  if (b.kind === "recording") return `Recording ${b.title ?? "a programme"} is using your stream${b.endsAt ? ` until ${clock(b.endsAt)}` : ""}.`;
  const what = b.title ?? b.channel;
  return what ? `${what} is being watched on another device.` : "Your stream is being watched on another device.";
}

/** True when the pop-up can offer "Stop recording & watch". */
export const canStop = (b: Blocker | null): b is Blocker & { recordingId: number | string } => !!b && b.kind === "recording" && b.recordingId != null;

/** When a running recording really ends: the padded stop if the server sends it. */
export const endsAtOf = (r: { stop: number; stopPadded?: number }) => r.stopPadded ?? r.stop;
