// Admin integration credentials (plan: ~/ai/plans/jellytip-live-sport.md "integration credentials"). GET /v1/admin/integrations →
// [{service, configured, checkedOk?, updatedAt}]; PUT /v1/admin/integrations/{service} {clientId, clientSecret}. Pure; tested in
// web/test/iptv.test.ts. The secret is never read back.

export type Service = "yahoo" | "reddit";

export interface IntegrationStatus {
  service: Service;
  configured: boolean;
  checkedOk: boolean | null;
  updatedAt: number | null;
}

export const SERVICES: { key: Service; title: string; purpose: string; createUrl: string; createLabel: string; hint: string }[] = [
  {
    key: "yahoo",
    title: "Yahoo Fantasy",
    purpose: "Lets people link their Yahoo Fantasy leagues for live scoring on NFL games.",
    createUrl: "https://developer.yahoo.com/apps/create",
    createLabel: "developer.yahoo.com/apps/create",
    hint: "Create an app with the Fantasy Sports read permission, then paste its Client ID and Client Secret here.",
  },
  {
    key: "reddit",
    title: "Reddit",
    purpose: "Powers the match chat in the Live panel (game threads and comments).",
    createUrl: "https://www.reddit.com/prefs/apps",
    createLabel: "reddit.com/prefs/apps",
    hint: "Create an app of type “script”, then paste its Client ID (under the app name) and Secret here.",
  },
];

export function parseIntegrations(raw: unknown): IntegrationStatus[] {
  const list = Array.isArray(raw) ? raw : raw && typeof raw === "object" && Array.isArray((raw as { integrations?: unknown }).integrations) ? (raw as { integrations: unknown[] }).integrations : [];
  const out: IntegrationStatus[] = [];
  for (const x of list) {
    const o = x && typeof x === "object" ? (x as Record<string, unknown>) : null;
    if (!o || (o.service !== "yahoo" && o.service !== "reddit")) continue;
    out.push({
      service: o.service,
      configured: o.configured === true,
      checkedOk: typeof o.checkedOk === "boolean" ? o.checkedOk : null,
      updatedAt: typeof o.updatedAt === "number" && isFinite(o.updatedAt) ? o.updatedAt : null,
    });
  }
  return out;
}

/** "Configured · checked OK" / "Configured · the check failed" / "Configured" / "Not configured". */
export function statusText(s: Pick<IntegrationStatus, "configured" | "checkedOk"> | undefined): string {
  if (!s || !s.configured) return "Not configured";
  if (s.checkedOk === true) return "Configured · checked OK";
  if (s.checkedOk === false) return "Configured · the check failed";
  return "Configured";
}
