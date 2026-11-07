// Playback time shown outside the player root: the element's own timeupdate drives these small children, so a tick
// re-renders a scrubber or a clock rather than the whole player (and the live drawer under it).
import { useEffect, useState, type RefObject } from "react";
import { Scrubber } from "./Scrubber";

/** The video element's current time, re-read on timeupdate/seeked. Only the component that calls this re-renders. */
export function useVideoTime(videoRef: RefObject<HTMLVideoElement | null>): number {
  const [time, setTime] = useState(() => videoRef.current?.currentTime ?? 0);
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    const read = () => setTime(v.currentTime);
    read();
    v.addEventListener("timeupdate", read);
    v.addEventListener("seeked", read);
    return () => {
      v.removeEventListener("timeupdate", read);
      v.removeEventListener("seeked", read);
    };
  }, [videoRef]);
  return time;
}

export function TimeBarScrubber({ videoRef, max, fmt, onActivity, onSeek }: { videoRef: RefObject<HTMLVideoElement | null>; max: number; fmt: (s: number) => string; onActivity?: () => void; onSeek: (to: number) => void }) {
  const time = useVideoTime(videoRef);
  return <Scrubber time={time} max={max} fmt={fmt} onActivity={onActivity} onSeek={onSeek} />;
}

export function TimeBarClock({ videoRef, fmt, length, seekable, growing }: { videoRef: RefObject<HTMLVideoElement | null>; fmt: (s: number) => string; length: number; seekable: boolean; growing: boolean }) {
  const time = useVideoTime(videoRef);
  return (
    <span className="clock">
      {fmt(time)}
      {seekable ? ` / ${fmt(length)}${growing ? "+" : ""}` : ""}
    </span>
  );
}
