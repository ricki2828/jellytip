export type SeasonResult = { seasonId: string; state: "done" | "failed"; summary: string; total: number; remaining: number };
export type Progress = { queued: number; running: number; finished: number; total: number; results: SeasonResult[] };
export type Reply = { status: number; data: unknown };

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
const obj = (v: unknown): Record<string, unknown> => v && typeof v === "object" ? v as Record<string, unknown> : {};
const count = (v: unknown) => typeof v === "number" && Number.isFinite(v) ? v : 0;

export function resultText(p: Progress): string {
  if (!p.total) return "This series has no seasons.";
  if (p.queued || p.running) return `Subtitle search: ${p.queued} queued, ${p.running} running, ${p.finished} finished of ${p.total}.`;
  const failed = p.results.filter((r) => r.state === "failed");
  const found = p.results.reduce((n, r) => n + Math.max(0, r.total - r.remaining), 0);
  if (failed.length) return `Subtitle search finished: ${p.total - failed.length} seasons completed, ${failed.length} failed. ${failed.map((r) => r.summary).filter(Boolean).join("; ")}`;
  if (!found) return `Subtitle search finished for ${p.total} seasons. All episodes already had subtitles or providers found none. ${p.results.map((r) => r.summary).filter(Boolean).join("; ")}`;
  return `Subtitle search finished for ${p.total} seasons. ${found} episodes processed. ${p.results.map((r) => r.summary).filter(Boolean).join("; ")}`;
}

/** Two workers keep every season in the queue; 429 retains its slot and retries with bounded backoff. */
export async function runSeasonSubtitles(ids: string[], language: string, call: (method: "GET" | "POST", path: string, body?: unknown) => Promise<Reply>, update: (p: Progress) => void, pause = wait): Promise<Progress> {
  const pending = [...new Set(ids)];
  const p: Progress = { queued: pending.length, running: 0, finished: 0, total: pending.length, results: [] };
  const emit = () => update({ ...p, results: [...p.results] });
  emit();
  async function worker() {
    while (pending.length) {
      const seasonId = pending.shift()!;
      p.queued--; p.running++; emit();
      let result: SeasonResult = { seasonId, state: "failed", summary: "Subtitle service unavailable", total: 0, remaining: 0 };
      for (let attempt = 0; attempt < 12; attempt++) {
        const start = await call("POST", "v1/subtitles/season", { seasonId, language });
        if (start.status === 429) { await pause(Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5))); continue; }
        if (start.status !== 202 || typeof obj(start.data).jobId !== "string") {
          result.summary = start.status === 401 || start.status === 403 ? "Admin authorization required" : `Subtitle service error (${start.status || "offline"})`;
          break;
        }
        const jobId = obj(start.data).jobId as string;
        for (let poll = 0; poll < 450; poll++) {
          await pause(4000);
          const status = await call("GET", `v1/subtitles/jobs/${encodeURIComponent(jobId)}`);
          if (status.status !== 200) {
            if (status.status === 401 || status.status === 403) { result.summary = "Admin authorization required"; break; }
            if (poll < 5) continue;
            result.summary = `Lost contact with subtitle service (${status.status || "offline"})`; break;
          }
          const data = obj(status.data);
          if (data.state === "done" || data.state === "failed") {
            result = { seasonId, state: data.state, summary: typeof data.summary === "string" ? data.summary : typeof data.message === "string" ? data.message : "", total: count(data.total), remaining: count(data.remaining) };
            break;
          }
          if (poll === 449) result.summary = "Subtitle search is still running on the server";
        }
        break;
      }
      if (result.summary === "Subtitle service unavailable") result.summary = "Subtitle service remained busy after retries";
      p.running--; p.finished++; p.results.push(result); emit();
    }
  }
  await Promise.all(Array.from({ length: Math.min(2, pending.length) }, () => worker()));
  return p;
}
