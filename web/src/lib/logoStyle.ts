// How a clearlogo is drawn (companion `logoStyle`: from /v1/items/{id}/backdrops and the `logoStyles` maps in the
// specials / rows payloads). white = off-white silhouette, mono = greyscale with the shading kept (multi-colour logos
// a silhouette flattens, e.g. Dogman), original = as drawn. Unknown = each place's old look. One helper for hero,
// details, tiles and specials.

export type LogoStyle = "white" | "mono" | "original";

const STYLES: LogoStyle[] = ["white", "mono", "original"];
const styles = new Map<string, LogoStyle>();
window.addEventListener("jellytip-session", () => styles.clear());

/** Remember styles from any payload: a map of item id → style, or one id + style. Junk is ignored. */
export function registerLogoStyles(map: unknown) {
  if (!map || typeof map !== "object") return;
  for (const [id, v] of Object.entries(map as Record<string, unknown>)) if (STYLES.includes(v as LogoStyle)) styles.set(id, v as LogoStyle);
}

/** The item whose Logo image is drawn (an episode / season draws its series'). */
export function logoOwner(item: { Id: string; ImageTags?: Record<string, string>; ParentLogoItemId?: string }): string {
  return item.ImageTags?.Logo ? item.Id : item.ParentLogoItemId ?? item.Id;
}

/** CSS class for a logo of this owner; `fallback` = the place's look when the server hasn't said. */
export function logoClass(ownerId: string, fallback: LogoStyle): string {
  return `logo-${styles.get(ownerId) ?? fallback}`;
}
