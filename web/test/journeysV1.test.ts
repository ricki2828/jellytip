// Journeys v1 (spec ~/ai/state/fleet/jellytip-journeys-v1-spec.md) against fixtures/journeys-v1-*.json, copied verbatim from the
// TV (app/src/test/resources/jellytip, 41b3eb51). A one-for-one port of the TV's JourneysV1Test, plus the web's own rules.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import {
  activeSummary, art, askFromReply, facts, askLabel, askText, btnLabel, canAsk, dot, getBody, getIndex, isCurrent, isHomeEmpty, isJourneyId, isNotFound, journeyPath,
  kicker, loadOutcome, meta, missingReason, nowIndex, oneAtATime, parseHome, parseJourney, parseStep, pathButtons, pathWindow, progress, progressLabel, retryMs,
  runtime, skipFor, stable, stepDetailsPath, stepPlayPath, stableHome, statusAfter, stepAction, withSkipped, yourJourneyLine, type JvStep,
} from "../src/lib/journeysV1.ts";
import { parseHash } from "../src/lib/routeParse.ts";
import { parseTarget } from "../src/lib/iptvPlay.ts";

const base = "https://api.example";
const raw = (n: string) => readFileSync(new URL(`./fixtures/${n}`, import.meta.url), "utf8");
const fixture = () => JSON.parse(raw("journeys-v1-fixture.json"));
const home = () => parseHome(fixture().home, base)!;
const journey = () => parseJourney(fixture().journey, base)!;

// The live detail is the TV's file with one change: the IPTV provider id replaced by the placeholder pp000001 (web fixtures never
// carry the real provider id: web-release.sh's secret scan refuses it; same as c5098931). The other two are byte for byte.
test("fixtures are the TV's (live detail: provider id scrubbed only)", () => {
  const md5 = (n: string) => createHash("md5").update(readFileSync(new URL(`./fixtures/${n}`, import.meta.url))).digest("hex");
  assert.equal(md5("journeys-v1-fixture.json"), "7c9a7ece43fab6421025bbd63abcaa57");
  assert.equal(md5("journeys-v1-live-list-20261123.json"), "996cf2bbaec8ed0a5400bbafc056189c");
  assert.equal(md5("journeys-v1-live-detail-20261123.json"), "ab3733fef53a20d2d3f2c4f89633815f"); // TV original 344102daf9a91af3ab8083f608bd4efd
});

test("parses home rows in order and drops bad entries", () => {
  const h = home();
  assert.equal(h.active, "cur:road-to-heat");
  assert.equal(Object.keys(h.journeys).length, 8); // the entry without an id is dropped
  assert.deepEqual(h.yours, ["cur:road-to-heat", "l:sight-and-sound", "d:240"]); // unknown id dropped
  // An id already in "yours" is not drawn again in "start"
  assert.deepEqual(h.start, ["cur:neo-noir-6", "cur:kurosawa-to-leone"]);
  assert.deepEqual(h.finish, ["f:87359", "d:1223"]);
  assert.equal(h.builtAt, 1763900000);
  const s = h.journeys["cur:road-to-heat"];
  assert.equal(s.curated, true);
  assert.equal(s.nowIndex, 2);
  assert.equal(s.nowTitle, "Thief");
  assert.equal(s.backdrop, "https://image.tmdb.org/t/p/w1280/thief.jpg");
  assert.equal(h.journeys["cur:all-done"].nowIndex, null);
  assert.equal(h.journeys["d:240"].poster, null);
  assert.equal(activeSummary(h)?.id, "cur:road-to-heat");
});

test("active must be a known journey", () => {
  const o = fixture().home;
  o.active = "cur:gone";
  assert.equal(parseHome(o, base)!.active, null);
});

test("unreadable bodies are null", () => {
  assert.equal(parseHome("not json", base), null);
  assert.equal(parseHome({}, base), null);
  assert.equal(parseHome(null, base), null);
  assert.equal(parseJourney({ steps: [] }, base), null);
});

test("parses steps and sources", () => {
  const j = journey();
  assert.equal(j.steps.length, 6); // a step without a title is dropped
  assert.equal(j.maxRating, "R");
  assert.equal(j.premise, "Michael Mann's lonely professionals, and the films that made them.");
  assert.equal(j.steps[0].source, "library");
  assert.equal(j.steps[1].source, "iptv");
  assert.equal(j.steps[1].iptv?.id, "v:p1:m:9001");
  // Library ids compare dashless, lower case
  assert.equal(j.steps[2].itemId, "0000000000000000000000000000000b");
  assert.equal(j.steps[3].source, "gettable");
  assert.equal(j.steps[4].rewatch, true);
  assert.equal(j.steps[5].why, null);
  assert.equal(j.steps[5].source, "missing");
});

test("a playable source without its id is not playable", () => {
  assert.equal(parseStep({ n: 1, title: "A", source: "library", itemId: null }, base)!.source, "missing");
  assert.equal(parseStep({ n: 1, title: "A", source: "iptv", iptv: null }, base)!.source, "missing");
  assert.equal(parseStep({ n: 1, title: "A", source: "weird" }, base)!.source, "missing");
});

test("tonight is the first unseen playable step and Get it is the gap", () => {
  const steps = journey().steps;
  assert.equal(nowIndex(steps), 2);
  assert.equal(getIndex(steps), 3);
  assert.deepEqual(steps.map((_, i) => dot(steps, i)), ["done", "done", "now", "gap", "next", "gap"]);
});

test("a missing earlier step offers the next playable one", () => {
  // Thief seen: Manhunter can't play yet, so tonight is Heat, and Get it still points at Manhunter
  const after = journey().steps.map((s, i) => (i === 2 ? { ...s, seen: true } : s));
  assert.equal(nowIndex(after), 4);
  assert.equal(getIndex(after), 3);
  assert.equal(dot(after, 3), "gap");
});

test("a skipped step is passed over", () => {
  const steps = withSkipped(journey().steps, 2);
  assert.equal(nowIndex(steps), 4);
  assert.equal(dot(steps, 2), "skipped");
});

test("every playable step skipped offers the first skipped one again", () => {
  const steps = journey().steps.map((s) => (!s.seen && (s.source === "library" || s.source === "iptv") ? { ...s, skipped: true } : s));
  // Thief (2) and Heat (4) both skipped: Thief again, drawn as tonight
  assert.equal(nowIndex(steps), 2);
  assert.equal(dot(steps, 2), "now");
  assert.equal(dot(steps, 4), "skipped");
});

test("all seen has no tonight", () => {
  const steps = journey().steps.map((s) => ({ ...s, seen: true }));
  assert.equal(nowIndex(steps), null);
  assert.equal(getIndex(steps), null);
});

test("playing a later step skips tonight's", () => {
  const steps = journey().steps;
  assert.equal(skipFor(steps, 4)?.title, "Thief");
  assert.equal(skipFor(steps, 2), null);
  assert.equal(skipFor(steps, 0), null);
});

test("kicker, meta and progress", () => {
  assert.equal(kicker("The road to Heat", 2, 6), "THE ROAD TO HEAT · STEP 3 OF 6");
  assert.equal(meta(journey().steps[2]), "2 h 2 m · ★ 7.4 · Library");
  assert.equal(meta(journey().steps[1]), "2 h 20 m · ★ 7.9 · IPTV");
  assert.equal(meta(journey().steps[4]), "2 h 50 m · ★ 7.9 · Library · Rewatch");
  assert.equal(facts(journey().steps[2]), "2 h 2 m · ★ 7.4");
  assert.equal(runtime(45), "45 m");
  assert.equal(runtime(120), "2 h");
  assert.equal(runtime(null), null);
  const s = home().journeys["cur:road-to-heat"];
  assert.equal(progressLabel(s), "2/6");
  assert.ok(Math.abs(progress(s) - 2 / 6) < 0.001);
  assert.equal(progress({ ...s, total: 0 }), 0);
});

test("path window fits long journeys", () => {
  assert.deepEqual(pathWindow(6, 2, 6), { from: 0, to: 5, before: 0, after: 0 });
  // 99 steps, tonight is #42: one before it, then the next ones
  assert.deepEqual(pathWindow(99, 41), { from: 40, to: 44, before: 40, after: 54 });
  // Near the end the window slides back to stay full
  assert.deepEqual(pathWindow(99, 98), { from: 94, to: 98, before: 94, after: 0 });
  assert.deepEqual(pathWindow(99, 0), { from: 0, to: 4, before: 0, after: 94 });
  // Finished: the last steps
  assert.deepEqual(pathWindow(99, null), { from: 94, to: 98, before: 94, after: 0 });
  assert.equal(pathWindow(0, null).to, -1);
});

test("a refetch keeps cards in place", () => {
  assert.deepEqual(stable(["a", "b", "c"], ["c", "a", "d", "b"]), ["a", "b", "c", "d"]);
  assert.deepEqual(stable(["a", "b", "c"], ["c", "a"]), ["a", "c"]);
  const old = home();
  const fresh = { ...old, yours: [...old.yours].reverse(), start: ["cur:kurosawa-to-leone"] };
  const merged = stableHome(old, fresh);
  assert.deepEqual(merged.yours, old.yours);
  assert.deepEqual(merged.start, ["cur:kurosawa-to-leone"]);
  assert.equal(stableHome(null, fresh), fresh);
});

test("only a 404 is missing; everything else retries with backoff from 5 s to 5 min", () => {
  assert.equal(isNotFound(404), true);
  assert.equal(isNotFound(500), false);
  assert.equal(isNotFound(null), false);
  assert.equal(loadOutcome(200), "ok");
  assert.equal(loadOutcome(404), "missing");
  for (const s of [0, 401, 403, 429, 500, 502, 503]) assert.equal(loadOutcome(s), "retry", String(s));
  assert.equal(retryMs(1), 5_000);
  assert.equal(retryMs(2), 10_000);
  assert.equal(retryMs(3), 20_000);
  assert.equal(retryMs(7), 300_000);
  assert.equal(retryMs(30), 300_000);
});

test("parses the live payloads", () => {
  const h = parseHome(JSON.parse(raw("journeys-v1-live-list-20261123.json")), base)!;
  assert.equal(Object.keys(h.journeys).length, 80);
  assert.equal(h.start.length, 40);
  assert.equal(h.finish.length, 20);
  assert.equal(h.yours.length, 0);
  assert.equal(h.active, null);
  const j = parseJourney(JSON.parse(raw("journeys-v1-live-detail-20261123.json")), base)!;
  assert.equal(j.steps.length, 7);
  // The IPTV step is only {id, kind}: still playable from IPTV
  assert.equal(j.steps[2].source, "iptv");
  assert.equal(j.steps[2].iptv?.id, "pp000001:m:1635672");
  // Tonight agrees with the server's nowIndex
  assert.equal(nowIndex(j.steps), j.summary.nowIndex);
  // The gettable step is seen (Letterboxd): nothing to get
  assert.equal(getIndex(j.steps), null);
});

test("every live id is a valid route and round-trips through the hash", () => {
  const h = parseHome(JSON.parse(raw("journeys-v1-live-list-20261123.json")), base)!;
  for (const id of Object.keys(h.journeys)) {
    assert.ok(isJourneyId(id), id);
    const r = parseHash(`#${journeyPath(id)}`);
    assert.equal(r.name, "journey", id);
    assert.equal(r.params.id, id);
  }
  // raw ":" (not encoded) works too, and v0 / junk ids fall back to the list
  assert.equal(parseHash("#/journeys/cur:road-to-heat").params.id, "cur:road-to-heat");
  assert.equal(parseHash("#/journeys/d%3A240").params.id, "d:240");
  assert.equal(parseHash("#/journeys/f%3A87359").params.id, "f:87359");
  assert.equal(parseHash("#/journeys/l%3Asight-and-sound").params.id, "l:sight-and-sound");
  assert.equal(parseHash("#/journeys").name, "journeys");
  for (const bad of ["d%3A0123456789abcdef0123456789abcdef", "x%3A1", "cur%3A", "f%3Aabc", "cur%3Aa%2Fb", "%E0%A4%A"]) assert.equal(parseHash(`#/journeys/${bad}`).name, "journeys", bad);
});

test("kids home has only what the server sent", () => {
  const h = parseHome(fixture().kidsHome, base)!;
  assert.equal(activeSummary(h), null);
  assert.deepEqual(h.start, ["cur:ghibli-first", "f:10194"]);
  assert.equal(isHomeEmpty(h), false);
});

// ---- 23-Nov: Get it on any gettable step; Play is the way in ----

test("what a tap does on each step", () => {
  const j = journey(); // Samouraï lib, Cercle iptv, Thief lib, Manhunter gettable, Heat lib, Collateral missing
  assert.deepEqual(j.steps.map(stepAction), ["play", "play", "play", "get", "play", "none"]);
  assert.equal(missingReason(j.steps[5]), "Not in the library or on TV, and it can't be fetched");
  assert.equal(missingReason(j.steps[0]), null);
});

test("get body names the chosen step", () => {
  const j = journey();
  assert.deepEqual(getBody(j.steps, 3), { index: 3, tmdbId: j.steps[3].tmdbId! });
  assert.deepEqual(getBody(j.steps, null), {}); // no step = the server's next missing one
  assert.deepEqual(getBody(j.steps, 99), {});
});

test("ask feedback: the 409 reason as is, a network failure is never Blocked", () => {
  assert.equal(askLabel(null), "Get it");
  assert.equal(askLabel({ state: "asking" }), "Asking…");
  const q = askFromReply(200, { queued: true, tmdbId: 11454 });
  assert.equal(q.state, "queued");
  assert.equal(askLabel(q), "On its way");
  const b = askFromReply(409, { reason: "Daily request limit reached" });
  assert.equal(askLabel(b), "Blocked");
  assert.equal(b.reason, "Daily request limit reached");
  assert.equal(askText(b), "Blocked: Daily request limit reached");
  assert.equal(askFromReply(409, null).reason, "Can't get it right now.");
  for (const s of [0, 500, 503, 429]) {
    const e = askFromReply(s, null);
    assert.equal(e.state, "error", String(s));
    assert.equal(askLabel(e), "Try again");
  }
  assert.equal(askFromReply(403, { error: "admin only" }).state, "blocked");
  assert.equal(canAsk(null), true);
  assert.equal(canAsk({ state: "asking" }), false);
  assert.equal(canAsk({ state: "queued" }), false);
  assert.equal(canAsk(b), true);
});

test("path buttons: Play starts it, no Start, no Pause; finished has none", () => {
  assert.deepEqual(pathButtons("none", true, false), ["play", "drop"]);
  assert.deepEqual(pathButtons("active", true, false), ["play", "remove", "drop"]);
  assert.deepEqual(pathButtons("paused", true, false), ["play", "addBack", "drop"]);
  assert.deepEqual(pathButtons("dropped", true, false), ["play", "addBack"]);
  assert.deepEqual(pathButtons("done", false, false), []); // finished: nothing to start or drop
  assert.ok(!pathButtons("done", true, false).includes("drop"));
  assert.deepEqual(pathButtons("active", true, true), ["dropConfirm", "dropCancel"]); // drop only behind a confirm
  assert.equal(btnLabel("play", "Thief"), "▶ Play: Thief");
  assert.equal(btnLabel("remove", null), "Remove from my journeys");
  assert.equal(btnLabel("dropCancel", null), "Keep it");
});

test("status after an action: start / pause / drop, and finished stays finished", () => {
  assert.equal(statusAfter("none", "start"), "active");
  assert.equal(statusAfter("paused", "start"), "active");
  assert.equal(statusAfter("dropped", "start"), "active");
  assert.equal(statusAfter("active", "pause"), "paused");
  assert.equal(statusAfter("none", "pause"), "none"); // only from active (the server answers 409 otherwise)
  assert.equal(statusAfter("active", "drop"), "dropped");
  assert.equal(statusAfter("done", "start"), "done"); // no Start on a finished journey
});

test("your-journey line only once it's yours", () => {
  assert.equal(yourJourneyLine("The road to Heat", "active", 2, 6), "Your journey: The road to Heat · step 3 of 6");
  assert.equal(yourJourneyLine("X", "active", null, 4), "Your journey: X · every step seen");
  assert.equal(yourJourneyLine("X", "none", 0, 4), null);
  assert.equal(yourJourneyLine("X", "paused", 0, 4), null);
});

test("a stale answer is dropped by its id", () => {
  assert.equal(isCurrent("cur:a", "cur:a"), true);
  assert.equal(isCurrent("cur:a", "cur:b"), false);
  assert.equal(isCurrent("cur:a", null), false);
});

test("one play at a time, and the busy flag always clears", async () => {
  const g = oneAtATime();
  let release!: () => void;
  assert.equal(g.run(() => new Promise<void>((r) => (release = r))), true);
  assert.equal(g.busy, true);
  assert.equal(g.run(() => undefined), false); // a second tap while the first resolves does nothing
  release();
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(g.busy, false);
  // a rejected run clears it
  assert.equal(g.run(() => Promise.reject(new Error("x"))), true);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(g.busy, false);
  // a synchronous throw clears it
  assert.equal(g.run(() => { throw new Error("y"); }), true);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(g.busy, false);
});

test("art: absolute TMDB kept, relative resolved against the companion, odd schemes dropped", () => {
  assert.equal(art(base, "https://image.tmdb.org/t/p/w342/a.jpg"), "https://image.tmdb.org/t/p/w342/a.jpg");
  assert.equal(art(base, "/v1/img/x.jpg"), "https://api.example/v1/img/x.jpg");
  assert.equal(art(base, "javascript:alert(1)"), null);
  assert.equal(art(base, "//evil.example/x.jpg"), null);
  assert.equal(art(base, null), null);
});

test("an IPTV step resumes from its progress unless finished", () => {
  const st = (p: unknown): JvStep => parseStep({ n: 1, title: "A", source: "iptv", iptv: { id: "p:m:1", kind: "movie", progress: p } }, base)!;
  assert.equal(st({ position: 600, duration: 6000, played: false }).iptv?.resume, 600);
  assert.equal(st({ position: 600, duration: 6000, played: true }).iptv?.resume, 0);
  assert.equal(st(null).iptv?.resume, 0);
});

test("where a step plays and opens: library /play + /item, IPTV the IPTV player + details, read back by the routes", () => {
  const j = journey();
  assert.equal(stepPlayPath(j.steps[2]), "/play/0000000000000000000000000000000b");
  assert.equal(stepDetailsPath(j.steps[2]), "/item/0000000000000000000000000000000b");
  assert.deepEqual(parseHash(`#${stepPlayPath(j.steps[2])}`).params, { id: "0000000000000000000000000000000b" });
  const live = parseJourney(JSON.parse(raw("journeys-v1-live-detail-20261123.json")), base)!;
  const oldboy = live.steps[2];
  const play = stepPlayPath(oldboy)!;
  const r = parseHash(`#${play}`);
  assert.equal(r.name, "iptvplay");
  assert.deepEqual(parseTarget(r.query), { vod: "pp000001:m:1635672" });
  assert.equal(r.query.get("t"), "Oldboy");
  const d = parseHash(`#${stepDetailsPath(oldboy)}`);
  assert.equal(d.name, "iptvitem");
  assert.equal(d.query.get("id"), "pp000001:m:1635672");
  // resume carried; a series opens its page; gettable / missing go nowhere
  const resumed = parseStep({ n: 1, title: "A", source: "iptv", iptv: { id: "p:m:1", kind: "movie", progress: { position: 600, duration: 6000 } } }, base)!;
  assert.equal(parseHash(`#${stepPlayPath(resumed)}`).query.get("resume"), "600");
  const series = parseStep({ n: 1, title: "S", source: "iptv", iptv: { id: "p:s:1", kind: "series" } }, base)!;
  assert.equal(parseHash(`#${stepPlayPath(series)}`).name, "iptvitem");
  assert.equal(stepPlayPath(j.steps[3]), null);
  assert.equal(stepDetailsPath(j.steps[5]), null);
});
