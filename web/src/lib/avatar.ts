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

/** Re-reads the user's PrimaryImageTag. False when the read failed (the shown avatar is then left as it was). */
export async function refreshAvatarTag(): Promise<boolean> {
  const id = getSession()?.userId;
  if (!id) return false;
  try {
    const u = await jf<{ PrimaryImageTag?: string }>("/Users/Me");
    if (getSession()?.userId !== id) return false;
    tag = u.PrimaryImageTag;
    emit();
    return true;
  } catch {
    if (tag === null) {
      tag = undefined;
      emit();
    }
    return false;
  }
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
  return id && t ? `${JF_BASE}/UserImage${qs({ userId: id, tag: t, width: size, quality: 90 })}` : null;
}

type Drawable = CanvasImageSource & { width: number; height: number };

/** Natural size via an <img> (header decode only), so the real decode below can be done at the target size. */
function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((res, rej) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => (URL.revokeObjectURL(url), res(img));
    img.onerror = () => (URL.revokeObjectURL(url), rej(new Error("decode")));
    img.src = url;
  });
}

/** Decode scaled so the short side is >= AVATAR_SIZE: a 48 MP photo never becomes a ~190 MB full-size bitmap. */
async function decode(file: File): Promise<{ src: Drawable; w: number; h: number; close: () => void }> {
  let img: HTMLImageElement | null = null;
  try {
    img = await loadImage(file);
  } catch {
    /* fall through to a plain bitmap decode */
  }
  if (!img) {
    try {
      const bm = await createImageBitmap(file);
      return { src: bm, w: bm.width, h: bm.height, close: () => bm.close() };
    } catch {
      throw new Error(decodeFailMessage(file));
    }
  }
  const w = img.naturalWidth, h = img.naturalHeight;
  if (!w || !h) throw new Error(decodeFailMessage(file));
  const k = Math.min(1, AVATAR_SIZE / Math.min(w, h));
  try {
    const bm = await createImageBitmap(file, {
      resizeWidth: Math.max(1, Math.round(w * k)),
      resizeHeight: Math.max(1, Math.round(h * k)),
      resizeQuality: "high",
      imageOrientation: "from-image",
    });
    return { src: bm, w: bm.width, h: bm.height, close: () => bm.close() };
  } catch {
    // resize options unsupported: draw the <img> itself (the browser downsamples while drawing)
    return { src: img, w, h, close: () => {} };
  }
}

/** Decode, center-crop to a square and resize to 512x512 JPEG. Returns the blob and a data URL for preview. */
export async function prepareAvatar(file: File): Promise<{ blob: Blob; dataUrl: string }> {
  const { src, w, h, close } = await decode(file);
  const c = document.createElement("canvas");
  try {
    const { sx, sy, side } = squareCrop(w, h);
    c.width = c.height = AVATAR_SIZE;
    const ctx = c.getContext("2d");
    if (!ctx) throw new Error("Couldn't process the image in this browser.");
    ctx.drawImage(src, sx, sy, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE);
    close();
    const blob = await new Promise<Blob | null>((r) => c.toBlob(r, "image/jpeg", AVATAR_QUALITY));
    if (!blob) throw new Error("Couldn't encode the photo.");
    return { blob, dataUrl: c.toDataURL("image/jpeg", AVATAR_QUALITY) };
  } finally {
    close();
    c.width = c.height = 0;
  }
}

async function toBase64(blob: Blob): Promise<string> {
  const buf = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Current route (Jellyfin 10.9+, verified on 12.1): /UserImage?userId=. Jellyfin wants the base64 text of the image as the body, Content-Type = the image's type.
 * Returns false when the upload succeeded but the new tag could not be read (one retry runs after 2 s). */
export async function uploadAvatar(blob: Blob): Promise<boolean> {
  const s = getSession();
  if (!s) throw new Error("Not signed in");
  const body = await toBase64(blob);
  const now = getSession();
  if (!now || now.userId !== s.userId || now.token !== s.token) throw new Error("Signed-in account changed; photo not saved.");
  await jf(`/UserImage${qs({ userId: s.userId })}`, { method: "POST", headers: { "Content-Type": "image/jpeg" }, body });
  if (await refreshAvatarTag()) return true;
  setTimeout(() => void refreshAvatarTag(), 2000);
  return false;
}

export async function removeAvatar(): Promise<void> {
  const s = getSession();
  if (!s) throw new Error("Not signed in");
  await jf(`/UserImage${qs({ userId: s.userId })}`, { method: "DELETE" });
  if (getSession()?.userId !== s.userId) return;
  tag = undefined;
  emit();
}
