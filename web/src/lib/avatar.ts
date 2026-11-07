import { useSyncExternalStore } from "react";
import { JF_BASE, getSession, jf, qs } from "../api/jellyfin";
import { AVATAR_QUALITY, AVATAR_SIZE, decodeFailMessage, squareCrop } from "./avatarCrop";

/** The signed-in user's PrimaryImageTag (undefined = no photo, null = not fetched yet). Changing it re-renders every avatar. */
let tag: string | undefined | null = null;
let forUser = "";
const subs = new Set<() => void>();
const emit = () => subs.forEach((f) => f());

function sync() {
  const id = getSession()?.userId ?? "";
  if (id !== forUser) {
    forUser = id;
    tag = null;
    if (id) void refreshAvatarTag();
  }
}

export async function refreshAvatarTag(): Promise<void> {
  const id = getSession()?.userId;
  if (!id) return;
  try {
    const u = await jf<{ PrimaryImageTag?: string }>("/Users/Me");
    if (getSession()?.userId !== id) return;
    tag = u.PrimaryImageTag;
  } catch {
    if (tag === null) tag = undefined;
  }
  emit();
}

function subscribe(f: () => void) {
  subs.add(f);
  window.addEventListener("jellytip-session", f);
  return () => {
    subs.delete(f);
    window.removeEventListener("jellytip-session", f);
  };
}

/** URL of the signed-in user's photo, or null. Re-renders on upload/remove/account switch. */
export function useAvatarUrl(size = 96): string | null {
  const t = useSyncExternalStore(subscribe, () => (sync(), tag));
  const id = getSession()?.userId;
  return id && t ? `${JF_BASE}/Users/${id}/Images/Primary${qs({ tag: t, width: size, quality: 90 })}` : null;
}

/** Decode, center-crop to a square and resize to 512x512 JPEG. Returns the blob and a data URL for preview. */
export async function prepareAvatar(file: File): Promise<{ blob: Blob; dataUrl: string }> {
  let src: CanvasImageSource & { width: number; height: number };
  try {
    src = await createImageBitmap(file);
  } catch {
    try {
      src = await new Promise<HTMLImageElement>((res, rej) => {
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = () => (URL.revokeObjectURL(url), res(img));
        img.onerror = () => (URL.revokeObjectURL(url), rej(new Error("decode")));
        img.src = url;
      });
    } catch {
      throw new Error(decodeFailMessage(file));
    }
  }
  const w = (src as { naturalWidth?: number }).naturalWidth || src.width;
  const h = (src as { naturalHeight?: number }).naturalHeight || src.height;
  if (!w || !h) throw new Error(decodeFailMessage(file));
  const { sx, sy, side } = squareCrop(w, h);
  const c = document.createElement("canvas");
  c.width = c.height = AVATAR_SIZE;
  const ctx = c.getContext("2d");
  if (!ctx) throw new Error("Couldn't process the image in this browser.");
  ctx.drawImage(src, sx, sy, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE);
  const blob = await new Promise<Blob | null>((r) => c.toBlob(r, "image/jpeg", AVATAR_QUALITY));
  if (!blob) throw new Error("Couldn't encode the photo.");
  return { blob, dataUrl: c.toDataURL("image/jpeg", AVATAR_QUALITY) };
}

async function toBase64(blob: Blob): Promise<string> {
  const buf = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Jellyfin wants the base64 text of the image as the body, Content-Type = the image's type. */
export async function uploadAvatar(blob: Blob): Promise<void> {
  const id = getSession()?.userId;
  if (!id) throw new Error("Not signed in");
  await jf(`/Users/${id}/Images/Primary`, { method: "POST", headers: { "Content-Type": "image/jpeg" }, body: await toBase64(blob) });
  await refreshAvatarTag();
}

export async function removeAvatar(): Promise<void> {
  const id = getSession()?.userId;
  if (!id) throw new Error("Not signed in");
  await jf(`/Users/${id}/Images/Primary`, { method: "DELETE" });
  tag = undefined;
  emit();
}
