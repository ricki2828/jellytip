// Focal crops for object-fit: cover backdrops (pure; tested in web/test/focal.test.ts). Same maths as the TV
// focalAlignment, plus the head clamp: the companion's `headTop` line (top of the highest face, with room for hair)
// never leaves the top of the frame, and the Ken Burns zoom never pushes it out either.

export interface FocalPoint {
  x: number;
  y: number;
  face: boolean;
  headTop?: number | null;
}

interface Fit {
  x: number;
  y: number;
  sh: number;
}

/** Offsets (px, both ≤ 0) of the scaled image inside the container, and its scaled height. */
export function focalFit(focal: FocalPoint, cw: number, ch: number, iw: number, ih: number): Fit {
  const scale = Math.max(cw / iw, ch / ih);
  const sw = iw * scale;
  const sh = ih * scale;
  const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);
  const x = clamp(focal.x * (cw - sw), Math.min(cw - sw, 0), 0);
  let y = 0;
  if (focal.y >= 0.25) {
    const headroom = focal.face ? 0.14 : 0.2;
    const desired = 0.4 * ch - focal.y * sh;
    const limit = -(Math.max(focal.y - headroom, 0) * sh);
    y = Math.max(desired, limit);
  }
  if (focal.headTop != null) y = Math.max(y, -focal.headTop * sh); // the whole head stays in
  y = clamp(y, Math.min(ch - sh, 0), 0);
  return { x: x + 0, y: y + 0, sh }; // + 0: no "-0px"
}

/**
 * CSS object-position keeping the focal point in frame: horizontally proportional, vertically at 40% of the container
 * but never cropping into the head.
 */
export function focalPosition(focal: FocalPoint | null, cw: number, ch: number, iw: number, ih: number): string {
  if (!focal || !cw || !ch || !iw || !ih) return "50% 30%";
  const { x, y } = focalFit(focal, cw, ch, iw, ih);
  return `${Math.round(x)}px ${Math.round(y)}px`;
}

/** Ken Burns zoom origin: at 40% down, or higher when the head line sits above that (zooming pushes points above the origin up). */
export function focalOrigin(focal: FocalPoint | null, cw = 0, ch = 0, iw = 0, ih = 0): string {
  if (!focal) return "50% 30%";
  const ox = Math.min(Math.max(focal.x, 0.2), 0.8) * 100;
  let oy = 40;
  if (focal.headTop != null && cw && ch && iw && ih) {
    const { y, sh } = focalFit(focal, cw, ch, iw, ih);
    const head = ((y + focal.headTop * sh) / ch) * 100; // head line on screen, % of the container
    oy = Math.max(0, Math.min(oy, head));
  }
  return `${ox}% ${Math.round(oy * 10) / 10}%`;
}
