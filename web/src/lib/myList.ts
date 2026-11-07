// My list as the server builds it for Home (family "mylist": library favourites + IPTV favourites, newest first), one row per media type.
import { serverRows } from "../api/companion";
import type { ServerRow } from "./rows";

export const pickMyList = (rows: ServerRow[] | null | undefined, kind: "movie" | "tv"): ServerRow | null =>
  rows?.find((r) => r.family === "mylist" && r.mediaType === kind && (r.itemIds.length > 0 || r.iptvItems.length > 0)) ?? null;

/** The person's My list for this page's kind, from the (cached) Home rows; null when empty or unavailable. */
export async function myListRow(kind: "movie" | "tv"): Promise<ServerRow | null> {
  const row = pickMyList(await serverRows("home").catch(() => null), kind);
  return row ? { ...row, title: kind === "movie" ? "My list · Films" : "My list · Shows" } : null;
}
