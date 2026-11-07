import { useEffect, useRef, useState } from "react";
import { prepareAvatar, removeAvatar, uploadAvatar, useAvatarUrl } from "../lib/avatar";
import { flashNote } from "./TasteButtons";
import "./ProfilePhotoSheet.css";

/** Profile photo sheet: choose (camera on phones), round preview, Save / Cancel, Remove. */
export function ProfilePhotoSheet({ onClose }: { onClose: () => void }) {
  const current = useAvatarUrl(192);
  const [picked, setPicked] = useState<{ blob: Blob; dataUrl: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Back (hardware / on-screen) dispatches Escape: close the sheet and consume it
    const esc = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopImmediatePropagation();
      onClose();
    };
    document.addEventListener("keydown", esc, true);
    return () => document.removeEventListener("keydown", esc, true);
  }, [onClose]);

  useEffect(() => {
    box.current?.querySelector<HTMLElement>("button")?.focus();
  }, [picked]);

  const onFile = async (f?: File) => {
    if (!f) return;
    setBusy(true);
    try {
      setPicked(await prepareAvatar(f));
    } catch (e) {
      flashNote(e instanceof Error ? e.message : "Couldn't read that image.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  };

  const save = async () => {
    if (!picked) return;
    setBusy(true);
    try {
      await uploadAvatar(picked.blob);
      flashNote("Profile photo updated");
      onClose();
    } catch {
      flashNote("Couldn't save the photo. Try again.");
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await removeAvatar();
      flashNote("Profile photo removed");
      onClose();
    } catch {
      flashNote("Couldn't remove the photo. Try again.");
      setBusy(false);
    }
  };

  const shown = picked?.dataUrl ?? current;
  return (
    <div className="pp-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="pp" ref={box} role="dialog" aria-modal="true" aria-label="Profile photo">
        <h2 className="pp-title">Profile photo</h2>
        <div className="pp-preview" aria-hidden="true">
          {shown ? <img src={shown} alt="" /> : <span>No photo</span>}
        </div>
        <input ref={input} type="file" accept="image/*" hidden onChange={(e) => void onFile(e.target.files?.[0])} />
        {picked ? (
          <div className="pp-row">
            <button className="pp-btn primary" data-nav disabled={busy} onClick={() => void save()}>{busy ? "Saving…" : "Save"}</button>
            <button className="pp-btn" data-nav disabled={busy} onClick={() => setPicked(null)}>Cancel</button>
          </div>
        ) : (
          <div className="pp-row">
            <button className="pp-btn primary" data-nav disabled={busy} onClick={() => input.current?.click()}>Choose photo</button>
            {current && <button className="pp-btn" data-nav disabled={busy} onClick={() => void remove()}>Remove photo</button>}
            <button className="pp-btn" data-nav onClick={onClose}>Close</button>
          </div>
        )}
      </div>
    </div>
  );
}
