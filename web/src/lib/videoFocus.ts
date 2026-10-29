// One video playing at a time on a page: the home hero trailer, a trailer break, a closet picks clip.
// Components that want to play claim; the most recent claim still held plays, everyone else pauses. Releasing hands
// playback back to the previous claimant (or, with none left, to the hero, which never claims).
import { useEffect, useState } from "react";

let claims: string[] = [];
const listeners = new Set<() => void>();
const owner = () => claims[claims.length - 1] ?? null;
/** Who holds playback now (null = nobody: the hero may play). */
export const videoOwner = owner;
const notify = () => listeners.forEach((l) => l());

/** Take playback (again): also used when a block that already holds a claim gets focus, to win it back. */
export function claimVideo(id: string) {
  if (owner() === id) return;
  claims = [...claims.filter((c) => c !== id), id];
  notify();
}

export function releaseVideo(id: string) {
  if (!claims.includes(id)) return;
  claims = claims.filter((c) => c !== id);
  notify();
}

/** True while some OTHER component holds playback (so this one should pause). */
export function useVideoBlocked(id: string): boolean {
  const isBlocked = () => owner() !== null && owner() !== id;
  const [blocked, setBlocked] = useState(isBlocked);
  useEffect(() => {
    const on = () => setBlocked(isBlocked());
    listeners.add(on);
    on();
    return () => {
      listeners.delete(on);
      releaseVideo(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
  return blocked;
}
