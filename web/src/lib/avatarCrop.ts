/** Center-crop geometry for profile photos: the largest centered square of a w x h image. */
export const AVATAR_SIZE = 512;
export const AVATAR_QUALITY = 0.85;

export interface SquareCrop {
  sx: number;
  sy: number;
  side: number;
}

export function squareCrop(w: number, h: number): SquareCrop {
  const side = Math.floor(Math.min(w, h));
  return { sx: Math.floor((w - side) / 2), sy: Math.floor((h - side) / 2), side };
}

/** Message for an image the browser cannot decode (HEIC on most desktops, corrupt files). */
export function decodeFailMessage(file: { type?: string; name?: string }): string {
  const heic = /hei[cf]/i.test(file.type ?? "") || /\.hei[cf]$/i.test(file.name ?? "");
  return heic ? "This browser can't read HEIC photos. Pick a JPEG or PNG instead." : "Couldn't read that image. Try a JPEG or PNG.";
}
