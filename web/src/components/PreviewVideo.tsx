// Muted backdrop trailer for details pages. Holds the single preview slot, pauses in a background tab, drops itself
// when it ends or fails (the still backdrop stays underneath).
import { useEffect, useRef, useState } from "react";
import { usePreviewLifecycle } from "../lib/useTrailerPreview";

export function PreviewVideo({ url }: { url: string | null }) {
  const [stopped, setStopped] = useState<string | null>(null);
  useEffect(() => {
    if (!url) setStopped(null);
  }, [url]);
  const live = url && stopped !== url ? url : null;
  const { hidden } = usePreviewLifecycle("details", live, () => setStopped(url));
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (hidden) el.pause();
    else el.play().catch(() => undefined);
  }, [hidden, live]);
  if (!live) return null;
  const stop = () => setStopped(live);
  return <video ref={ref} key={live} className="hero-trailer" src={live} autoPlay muted playsInline onEnded={stop} onError={stop} aria-hidden />;
}
