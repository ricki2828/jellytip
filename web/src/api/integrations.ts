// Admin integration credentials. Missing routes (404) or a non-admin (403) hide the section.
import { companionCall } from "./companionCall";
import { parseIntegrations, type IntegrationStatus, type Service } from "../lib/integrations";

/** null = the section stays hidden (no route, not an admin, unreachable). */
export async function integrations(): Promise<IntegrationStatus[] | null> {
  const a = await companionCall("GET", "v1/admin/integrations");
  return a.status === 200 ? parseIntegrations(a.data) : null;
}

/** Save a service's app credentials; returns the new status or an error text. The values are not kept or logged here. */
export async function saveIntegration(service: Service, clientId: string, clientSecret: string): Promise<{ status: IntegrationStatus } | { error: string }> {
  const a = await companionCall("PUT", `v1/admin/integrations/${service}`, { clientId, clientSecret });
  if (a.status === 200 && a.data && typeof a.data === "object") {
    const o = a.data as { configured?: unknown; checkedOk?: unknown };
    return { status: { service, configured: o.configured !== false, checkedOk: typeof o.checkedOk === "boolean" ? o.checkedOk : null, updatedAt: Math.floor(Date.now() / 1000) } };
  }
  const e = a.data && typeof a.data === "object" ? (a.data as { error?: unknown }).error : null;
  return { error: typeof e === "string" && e ? e : a.status === 0 ? "The server can't be reached." : `Server said ${a.status}` };
}
