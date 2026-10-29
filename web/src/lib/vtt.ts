export interface Cue {
  start: number;
  end: number;
  text: string;
}

function time(s: string): number {
  const p = s.trim().replace(",", ".").split(":");
  let t = 0;
  for (const part of p) t = t * 60 + parseFloat(part);
  return t;
}

/** Minimal WebVTT parser: timing lines + text, tags stripped (italics kept as <i>). */
export function parseVtt(src: string): Cue[] {
  const cues: Cue[] = [];
  for (const block of src.replace(/\r/g, "").split(/\n{2,}/)) {
    const lines = block.split("\n");
    const i = lines.findIndex((l) => l.includes("-->"));
    if (i < 0) continue;
    const [a, b] = lines[i].split("-->");
    const text = lines
      .slice(i + 1)
      .join("\n")
      .replace(/<(?!\/?i>)[^>]+>/g, "")
      .replace(/\{\\[^}]*\}/g, "")
      .trim();
    if (text) cues.push({ start: time(a), end: time(b.trim().split(/\s+/)[0]), text });
  }
  return cues.sort((x, y) => x.start - y.start);
}

/** Cues active at media time t with the subtitle delay applied (positive delay = subtitles later). */
export function activeCues(cues: Cue[], t: number, delay: number): Cue[] {
  const at = t - delay;
  // binary search for the last cue starting at or before `at`, then walk back over overlaps
  let lo = 0;
  let hi = cues.length - 1;
  let idx = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (cues[mid].start <= at) {
      idx = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  const out: Cue[] = [];
  for (let k = idx; k >= 0 && k > idx - 8; k--) if (cues[k].end > at) out.unshift(cues[k]);
  return out;
}
