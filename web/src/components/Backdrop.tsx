import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Focal } from "../api/companion";
import { focalOrigin, focalPosition } from "../lib/art";

interface Layer {
  url: string;
  focal: Focal | null;
  w: number;
  h: number;
}

/**
 * Full-bleed cover image cropped around the companion's focal point (no cut heads), optional slow Ken Burns zoom.
 * The next image is preloaded and crossfaded over the current one, so changing items never flashes black.
 */
export function Backdrop({ url, focal, kenBurns = false }: { url: string | null; focal: Focal | null; kenBurns?: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 });
  const [layers, setLayers] = useState<Layer[]>([]);

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (!url) return;
    let alive = true;
    const img = new Image();
    img.decoding = "async";
    img.fetchPriority = "high"; // ahead of the tile images queued at the same moment
    img.onload = () => {
      if (!alive) return;
      setLayers((prev) => {
        const top = prev[prev.length - 1];
        if (top?.url === url) return prev.map((l, i) => (i === prev.length - 1 ? { ...l, focal } : l));
        return [...prev.slice(-1), { url, focal, w: img.naturalWidth, h: img.naturalHeight }];
      });
    };
    img.src = url;
    return () => {
      alive = false;
      img.onload = null;
    };
  }, [url, focal]);

  return (
    <div className="backdrop" ref={box}>
      {layers.map((l, i) => (
        <img
          key={l.url}
          src={l.url}
          alt=""
          className={`backdrop-img in${i === layers.length - 1 ? " top" : ""}${kenBurns ? " kb" : ""}`}
          style={{ objectPosition: focalPosition(l.focal, size.w, size.h, l.w, l.h), transformOrigin: focalOrigin(l.focal, size.w, size.h, l.w, l.h) }}
        />
      ))}
    </div>
  );
}
