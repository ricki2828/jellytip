import { useEffect, useRef, useState } from "react";
import { HttpError } from "../api/jellyfin";
import { prepareAvatar, removeAvatar, uploadAvatar, useAvatarUrl } from "../lib/avatar";
import { flashNote } from "./TasteButtons";
import "./ProfilePhotoSheet.css";

function failText(e: unknown, fallback: string): string {
  if (e instanceof HttpError) {
    if (e.status === 401) return "Signed out — sign in again";
    if (e.status === 403) return "Your account can't change its photo";
    if (e.status === 413) return "That photo is too large";
  }
  return e instanceof Error && !(e instanceof HttpError) ? e.message : fallback;
}

/** Profile photo sheet: choose or take a photo, round preview, Save / Cancel, Remove. */
export function ProfilePhotoSheet({ onClose }: { onClose: () => void }) {
  const current = useAvatarUrl(192);
  const [picked, setPicked] = useState<{ blob: Blob; dataUrl: string } | null>(null);
  const [busy, setBusyState] = useState(false);
  const busyRef = useRef(false);
  const choose = useRef<HTMLInputElement>(null);
  const camera = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLDivElement>(null);

  // Busy buttons stay enabled (aria-disabled + ignored clicks) so focus never falls out of the sheet; the sheet itself takes focus
  const setBusy = (b: boolean) => {
    busyRef.current = b;
    setBusyState(b);
    if (b) box.current?.focus();
  };

  useEffect(() => {
    // Back (hardware / on-screen) dispatches Escape: close the sheet and consume it; while a request is in flight it only consumes
    const esc = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (!busyRef.current) onClose();
    };
    document.addEventListener("keydown", esc, true);
    return () => document.removeEventListener("keydown", esc, true);
  }, [onClose]);

  useEffect(() => {
    if (!busyRef.current) box.current?.querySelector<HTMLElement>("button")?.focus();
  }, [picked]);

  const onFile = async (f?: File) => {
    if (!f || busyRef.current) return;
    setBusy(true);
    try {
      setPicked(await prepareAvatar(f));
    } catch (e) {
      flashNote(failText(e, "Couldn't read that image."));
    } finally {
      setBusy(false);
      if (choose.current) choose.current.value = "";
      if (camera.current) camera.current.value = "";
      box.current?.querySelector<HTMLElement>("button")?.focus();
    }
  };

  const save = async () => {
    if (!picked || busyRef.current) return;
    setBusy(true);
    try {
      const shown = await uploadAvatar(picked.blob);
      flashNote(shown ? "Profile photo updated" : "Saved — it may take a moment to appear");
      busyRef.current = false;
      onClose();
    } catch (e) {
      flashNote(failText(e, "Couldn't save the photo. Try again."));
      setBusy(false);
      box.current?.querySelector<HTMLElement>("button")?.focus();
    }
  };

  const remove = async () => {
    if (busyRef.current) return;
    setBusy(true);
    try {
      await removeAvatar();
      flashNote("Profile photo removed");
      busyRef.current = false;
      onClose();
    } catch (e) {
      flashNote(failText(e, "Couldn't remove the photo. Try again."));
      setBusy(false);
      box.current?.querySelector<HTMLElement>("button")?.focus();
    }
  };

  const shown = picked?.dataUrl ?? current;
  const dis = { "aria-disabled": busy || undefined } as const;
  return (
    <div className="pp-scrim" onMouseDown={(e) => e.target === e.currentTarget && !busyRef.current && onClose()}>
      <div className="pp" ref={box} tabIndex={-1} role="dialog" aria-modal="true" aria-label="Profile photo" aria-busy={busy}>
        <h2 className="pp-title">Profile photo</h2>
        <div className="pp-preview" aria-hidden="true">
          {shown ? <img src={shown} alt="" /> : <span>No photo</span>}
        </div>
        <div className="pp-status" role="status" aria-live="polite">{busy ? (picked ? "Saving…" : "Working…") : ""}</div>
        <input ref={choose} type="file" accept="image/*" hidden onChange={(e) => void onFile(e.target.files?.[0])} />
        <input ref={camera} type="file" accept="image/*" capture="user" hidden onChange={(e) => void onFile(e.target.files?.[0])} />
        {picked ? (
          <div className="pp-row">
            <button className="pp-btn primary" data-nav {...dis} onClick={() => void save()}>Save</button>
            <button className="pp-btn" data-nav {...dis} onClick={() => !busyRef.current && setPicked(null)}>Cancel</button>
          </div>
        ) : (
          <div className="pp-row">
            <button className="pp-btn primary" data-nav {...dis} onClick={() => !busyRef.current && choose.current?.click()}>Choose photo</button>
            <button className="pp-btn" data-nav {...dis} onClick={() => !busyRef.current && camera.current?.click()}>Take photo</button>
            {current && <button className="pp-btn" data-nav {...dis} onClick={() => void remove()}>Remove photo</button>}
            <button className="pp-btn" data-nav {...dis} onClick={() => !busyRef.current && onClose()}>Close</button>
          </div>
        )}
      </div>
    </div>
  );
}
