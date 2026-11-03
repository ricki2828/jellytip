import { scrub } from "./scrub.ts";

const secretKey = /(?:url|token|credential|password|authorization|auth|cookie|secret)/i;

export function clean(data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) {
    if (secretKey.test(k)) { out[k] = "[redacted]"; continue; }
    out[k] = typeof v === "string" ? scrub(v) : Array.isArray(v) ? v.map((item) => typeof item === "string" ? scrub(item) : item && typeof item === "object" ? clean(item as Record<string, unknown>) : item) : v && typeof v === "object" ? clean(v as Record<string, unknown>) : v;
  }
  return out;
}
