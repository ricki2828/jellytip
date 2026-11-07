import { companionCall } from "./companionCall";
import { parsePoll, parseStart, type SyncPoll, type SyncResult } from "../lib/subSync";

const POLL_MS = 1500;
const GIVE_UP_MS = 11 * 60_000; // the server times a job out at 10 min

export async function startAutoMatch(itemId: string, streamIndex: number, mediaSourceId: string | undefined, signal?: AbortSignal) {
  const r = await companionCall("POST", "v1/subs/sync", { itemId, streamIndex, mediaSourceId }, { signal });
  return parseStart(r.status, r.data);
}

export async function pollAutoMatch(jobId: string, signal?: AbortSignal): Promise<SyncPoll> {
  const r = await companionCall("GET", `v1/subs/sync/${jobId}`, undefined, { signal });
  return parsePoll(r.status, r.data);
}

/** Start and wait. `onProgress` gets the server's remaining-seconds estimate. Resolves to a result or a message. */
export async function autoMatch(itemId: string, streamIndex: number, mediaSourceId: string | undefined, onProgress: (etaSec: number | null) => void, signal: AbortSignal): Promise<{ result: SyncResult } | { error: string }> {
  const s = await startAutoMatch(itemId, streamIndex, mediaSourceId, signal);
  if ("error" in s || "result" in s) return s;
  const t0 = Date.now();
  let misses = 0;
  while (!signal.aborted && Date.now() - t0 < GIVE_UP_MS) {
    await new Promise((res) => setTimeout(res, POLL_MS));
    if (signal.aborted) break;
    const p = await pollAutoMatch(s.jobId, signal);
    if (p.state === "done") return { result: p.result };
    if (p.state === "failed") {
      if (p.error === "Couldn't check the match" && ++misses < 4) continue; // a blip on the phone's network, not a verdict
      return { error: p.error };
    }
    misses = 0;
    onProgress(p.etaSec);
  }
  return { error: "Timed out while matching this one" };
}
