// "Delete from server" for library titles: pure helpers (contract: ~/ai/state/jellytip-delete-contract.md). No DOM, no network.

export type DeleteKind = "movie" | "series" | "season" | "episode";

export interface DeleteInfo {
  canDelete: boolean;
  reason?: string;
  title: string;
  kind: DeleteKind | null;
  bytes: number | null;
  via: "radarr" | "sonarr" | "jellyfin" | null;
  note?: string;
}

export function sizeText(bytes: number | null | undefined): string | null {
  if (!bytes || bytes <= 0) return null;
  const gb = bytes / 1024 ** 3;
  if (gb >= 1) return `${gb >= 10 ? gb.toFixed(0) : gb.toFixed(1)} GB`;
  return `${Math.max(1, Math.round(bytes / 1024 ** 2))} MB`;
}

/** What goes, in words: shown in the confirm dialog. */
export function whatGoes(kind: DeleteKind | null): string {
  switch (kind) {
    case "movie":
      return "the film and its file";
    case "series":
      return "the whole series: every season and episode file";
    case "season":
      return "this season: every episode file in it";
    case "episode":
      return "this episode's file";
    default:
      return "this title's files";
  }
}

/** Parse the delete-info reply; anything unexpected (404 from an older server, 401, bad shape) = hide the action. */
export function parseInfo(status: number, data: unknown): DeleteInfo | null {
  if (status !== 200 || !data || typeof data !== "object") return null;
  const d = data as Partial<DeleteInfo>;
  if (d.canDelete !== true) return null;
  return {
    canDelete: true,
    title: String(d.title ?? ""),
    kind: (d.kind as DeleteKind) ?? null,
    bytes: typeof d.bytes === "number" ? d.bytes : null,
    via: d.via ?? null,
    note: d.note,
  };
}

export function parseResult(status: number, data: unknown): { ok: true; freedBytes: number } | { ok: false; error: string } {
  const d = (data && typeof data === "object" ? data : {}) as { ok?: boolean; freedBytes?: number; error?: string };
  if (status === 200 && d.ok) return { ok: true, freedBytes: Number(d.freedBytes) || 0 };
  if (status === 403) return { ok: false, error: "Only admins can delete from the server." };
  if (status === 0) return { ok: false, error: "The server can't be reached." };
  return { ok: false, error: d.error || `Server said ${status}` };
}

// Ids removed this session: rows drop them at once (the server drops them from its own lists too).
const gone = new Set<string>();
const norm = (id: string) => id.replace(/-/g, "").toLowerCase();
export const markDeleted = (id: string) => void gone.add(norm(id));
export const isDeleted = (id: string) => gone.has(norm(id));
export const DELETED_EVENT = "jellytip-item-deleted";
