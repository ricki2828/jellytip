/** Drops the secret part of any stream link inside a string (pure; used before anything is logged or sent). */
export function scrub(v: string): string {
  return v.replace(/(\/v1\/iptv\/s\/|\/s\/)[^\s"'?&)]+/g, "$1…");
}
