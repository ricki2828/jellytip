// Build identity (which build is running) and the pure helpers around it. No DOM, so it is unit-tested directly.

export const BASE_VERSION = "0.1.0";

export interface BuildInfo {
  sha: string;
  /** ISO timestamp, "" when unknown (dev). */
  builtAt: string;
}

const SHA_RE = /^[0-9a-z]{4,40}$/i;

/** A sha safe for a quoted HTTP header and a filename-ish label: hex-ish token or "dev". */
export function cleanSha(v: unknown): string {
  return typeof v === "string" && (SHA_RE.test(v.trim()) || v.trim() === "dev") ? v.trim() : "dev";
}

export const BUILD: BuildInfo = {
  sha: cleanSha(typeof __BUILD_SHA__ !== "undefined" ? __BUILD_SHA__ : "dev"),
  builtAt: typeof __BUILD_TIME__ !== "undefined" && !Number.isNaN(Date.parse(__BUILD_TIME__)) ? __BUILD_TIME__ : "",
};

/** The client Version sent to Jellyfin: "0.1.0+abc1234". */
export const versionString = (sha: string = BUILD.sha): string => `${BASE_VERSION}+${cleanSha(sha)}`;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "6 Nov 18:20" in the viewer's local time; "" for a missing/invalid timestamp. */
export function formatBuiltAt(iso: string): string {
  const t = Date.parse(iso);
  if (!iso || Number.isNaN(t)) return "";
  const d = new Date(t);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Settings → About line: "Version 0.1.0 · build abc1234 · built 6 Nov 18:20". */
export function aboutLine(b: BuildInfo = BUILD): string {
  const built = formatBuiltAt(b.builtAt);
  return [`Version ${BASE_VERSION}`, `build ${cleanSha(b.sha)}`, built && `built ${built}`].filter(Boolean).join(" · ");
}
