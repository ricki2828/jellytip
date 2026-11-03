import { test } from "node:test";
import assert from "node:assert/strict";
import { resultText, runSeasonSubtitles } from "../src/lib/seasonSubtitles.ts";

test("busy seasons stay queued and every season finishes with at most two active jobs", async () => {
  const starts = new Map<string, number>();
  let active = 0;
  let max = 0;
  const progress: string[] = [];
  const end = await runSeasonSubtitles(["a", "b", "c", "c"], "eng", async (method, path, body) => {
    if (method === "POST") {
      const id = (body as { seasonId: string }).seasonId;
      starts.set(id, (starts.get(id) ?? 0) + 1);
      if (id === "b" && starts.get(id) === 1) return { status: 429, data: null };
      active++; max = Math.max(max, active);
      return { status: 202, data: { jobId: id } };
    }
    active--;
    return { status: 200, data: { state: "done", total: 1, remaining: 0, summary: path } };
  }, (p) => progress.push(resultText(p)), async () => {});
  assert.equal(max, 2);
  assert.equal(starts.get("b"), 2);
  assert.deepEqual(end.results.map((r) => r.seasonId).sort(), ["a", "b", "c"]);
  assert.equal(end.finished, 3);
  assert.match(progress[0], /3 queued/);
});

test("empty, already present and mixed failures have readable outcomes", () => {
  assert.match(resultText({ queued: 0, running: 0, finished: 0, total: 0, results: [] }), /no seasons/);
  assert.match(resultText({ queued: 0, running: 0, finished: 1, total: 1, results: [{ seasonId: "a", state: "done", summary: "Already present", total: 0, remaining: 0 }] }), /already had/);
  assert.match(resultText({ queued: 0, running: 0, finished: 2, total: 2, results: [{ seasonId: "a", state: "done", summary: "ok", total: 1, remaining: 0 }, { seasonId: "b", state: "failed", summary: "Provider quota exceeded", total: 0, remaining: 0 }] }), /Provider quota exceeded/);
});
