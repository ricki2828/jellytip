import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SubtitleError, addedSubtitle, canManageSubtitles, classifyError, defaultSearchLanguage, downloadPath, errorMessage, initialPhase, languageChoices, mapResults,
  needsPolicyLookup, reduce, remapActive, resultFlags, resultMeta, searchPath, sortResults, toSearchLanguage, waitForNewSubtitle, withDeadline, addedMessage, canSearchSeasons,
  type Event, type Phase, type RemoteSubtitle, type StreamLike,
} from "../src/lib/remoteSubtitles.ts";

const ID = "0123456789abcdef0123456789abcdef";
const sub = (o: Partial<RemoteSubtitle> & { id: string }): RemoteSubtitle => ({ name: o.id, provider: "", format: "", author: "", language: "eng", rating: null, downloads: null, perfectMatch: false, hearingImpaired: false, forced: false, aiTranslated: false, machineTranslated: false, ...o });

test("gating: admin or Allow subtitle management; unknown is hidden but looked up once", () => {
  assert.equal(canManageSubtitles(null), false);
  assert.equal(canManageSubtitles({ isAdmin: true }), true);
  assert.equal(canManageSubtitles({ canManageSubtitles: true }), true);
  assert.equal(canManageSubtitles({ isAdmin: false, canManageSubtitles: false }), false);
  assert.equal(canManageSubtitles({}), false);
  assert.equal(needsPolicyLookup({}), true);
  assert.equal(needsPolicyLookup({ isAdmin: true }), false);
  assert.equal(needsPolicyLookup({ canManageSubtitles: false }), false);
  assert.equal(needsPolicyLookup(null), false);
});

test("languages: 2-letter prefs become 3-letter API codes, default and choices", () => {
  assert.equal(toSearchLanguage("en"), "eng");
  assert.equal(toSearchLanguage(" FR "), "fre");
  assert.equal(toSearchLanguage("fra"), "fre");
  assert.equal(toSearchLanguage("xyz"), "xyz");
  assert.equal(toSearchLanguage("English"), null);
  assert.equal(toSearchLanguage("../"), null);
  assert.equal(defaultSearchLanguage(["es", "en"]), "spa");
  assert.equal(defaultSearchLanguage(["??", "de"]), "ger");
  assert.equal(defaultSearchLanguage([]), "eng");
  assert.equal(defaultSearchLanguage(undefined), "eng");
  const c = languageChoices(["mi", "en"], "eng");
  assert.deepEqual(c.slice(0, 2), ["mao", "eng"]);
  assert.equal(new Set(c).size, c.length);
  assert.ok(languageChoices([], "xyz").includes("xyz"));
});

test("request building: encoded segments, bad ids and languages refused", () => {
  assert.equal(searchPath(ID, "en"), `/Items/${ID}/RemoteSearch/Subtitles/eng`);
  assert.equal(searchPath("01234567-89ab-cdef-0123-456789abcdef", "ger"), "/Items/01234567-89ab-cdef-0123-456789abcdef/RemoteSearch/Subtitles/ger");
  assert.throws(() => searchPath("../x", "eng"));
  assert.throws(() => searchPath(ID, "e/n"));
  assert.throws(() => searchPath(ID, ""));
  assert.equal(downloadPath(ID, "a/b+c=d:e?f#g"), `/Items/${ID}/RemoteSearch/Subtitles/a%2Fb%2Bc%3Dd%3Ae%3Ff%23g`);
  assert.throws(() => downloadPath(ID, ""));
  assert.throws(() => downloadPath("nope", "x"));
});

test("result mapping: ids required, fields typed, TV sort (perfect match, rating, downloads)", () => {
  const raw = [
    { Id: "a", Name: "A", ProviderName: "OpenSubtitles", Format: "srt", CommunityRating: 7, DownloadCount: 10 },
    { Name: "no id" },
    { Id: "b", Name: "B", IsHashMatch: true, CommunityRating: 1, DownloadCount: 1, HearingImpaired: true },
    { Id: "c", ProviderName: "Podnapisi", CommunityRating: 9.5, DownloadCount: 5, Forced: true, AiTranslated: true },
    { Id: "d", Name: "D", CommunityRating: 7, DownloadCount: 99 },
    { Id: "e", Name: "E", CommunityRating: "x", DownloadCount: null },
    "junk", null,
  ];
  const r = mapResults(raw);
  assert.deepEqual(r.map((x) => x.id), ["b", "c", "d", "a", "e"]);
  assert.equal(r[1].name, "Podnapisi");
  assert.equal(r[1].forced, true);
  assert.equal(r[3].format, "SRT");
  assert.equal(r[4].rating, null);
  assert.deepEqual(mapResults({ not: "array" }), []);
  assert.deepEqual(mapResults(undefined), []);
});

test("sort is stable for ties and does not mutate", () => {
  const input = [sub({ id: "1", rating: 5 }), sub({ id: "2", rating: 5 }), sub({ id: "3", rating: 5 })];
  const copy = [...input];
  assert.deepEqual(sortResults(input).map((x) => x.id), ["1", "2", "3"]);
  assert.deepEqual(input, copy);
  assert.deepEqual(sortResults([sub({ id: "n" }), sub({ id: "r", rating: 0 })]).map((x) => x.id), ["r", "n"]);
});

test("meta and flags text", () => {
  const r = sub({ id: "x", provider: "OS", format: "SRT", rating: 8.25, downloads: 12345, author: "me", perfectMatch: true, hearingImpaired: true });
  assert.equal(resultMeta(r), "OS · SRT · rating 8.3 · 12,345 downloads · me");
  assert.deepEqual(resultFlags(r), ["Perfect match", "Hearing impaired"]);
  assert.equal(resultMeta(sub({ id: "y" })), "");
});

test("error mapping: 401/403 permission, 404, 5xx, network, passthrough", () => {
  assert.equal(classifyError({ status: 401 }).kind, "forbidden");
  assert.equal(classifyError({ status: 403 }).kind, "forbidden");
  assert.equal(classifyError({ status: 404 }).kind, "notfound");
  assert.equal(classifyError({ status: 500 }).kind, "server");
  assert.equal(classifyError(new TypeError("Failed to fetch")).kind, "offline");
  const e = new SubtitleError("timeout");
  assert.equal(classifyError(e), e);
  assert.match(errorMessage(new SubtitleError("forbidden", 403)), /can't manage subtitles/);
  assert.match(errorMessage(new SubtitleError("timeout")), /70 s/);
  assert.match(errorMessage(new SubtitleError("timeout"), "download"), /60 s/);
  assert.match(errorMessage(new SubtitleError("server", 502)), /502/);
  assert.match(errorMessage(new SubtitleError("offline")), /connection/);
  assert.match(errorMessage(new SubtitleError("notfound"), "download"), /no longer available/);
});

test("withDeadline: success, timeout, cancel, pre-cancelled, error classification", async () => {
  assert.equal(await withDeadline(async () => 7, 1000), 7);
  await assert.rejects(withDeadline((s) => new Promise((_, rej) => s.addEventListener("abort", () => rej(new Error("aborted")))), 20), (e: SubtitleError) => e.kind === "timeout");
  const outer = new AbortController();
  const p = withDeadline((s) => new Promise((_, rej) => s.addEventListener("abort", () => rej(new Error("aborted")))), 5000, outer.signal);
  outer.abort();
  await assert.rejects(p, (e: SubtitleError) => e.kind === "cancelled");
  const done = new AbortController();
  done.abort();
  await assert.rejects(withDeadline(async () => 1, 1000, done.signal), (e: SubtitleError) => e.kind === "cancelled");
  await assert.rejects(withDeadline(async () => { throw { status: 403 }; }, 1000), (e: SubtitleError) => e.kind === "forbidden");
});

const run = (events: Event[], from: Phase = initialPhase) => events.reduce(reduce, from);

test("state machine: search -> results -> download -> added", () => {
  const a = sub({ id: "a" });
  let s = run([{ type: "search", seq: 1, language: "eng" }]);
  assert.deepEqual([s.phase, s.seq], ["searching", 1]);
  s = run([{ type: "found", seq: 1, results: [a] }], s);
  assert.equal(s.phase, "results");
  s = run([{ type: "download", seq: 2, picked: a }], s);
  assert.deepEqual([s.phase, s.seq], ["downloading", 2]);
  s = run([{ type: "added", seq: 2, listed: true }], s);
  assert.equal(s.phase, "added");
  assert.equal(s.phase === "added" && s.listed, true);
});

test("state machine: empty result, errors, retry", () => {
  let s = run([{ type: "search", seq: 1, language: "eng" }, { type: "found", seq: 1, results: [] }]);
  assert.equal(s.phase, "empty");
  s = run([{ type: "search", seq: 2, language: "spa" }, { type: "failed", seq: 2, error: new SubtitleError("forbidden", 403) }]);
  assert.equal(s.phase === "error" && s.action, "search");
  s = run([{ type: "search", seq: 3, language: "spa" }], s);
  assert.equal(s.phase, "searching");
});

test("state machine: cancel only applies to a search; late replies are ignored", () => {
  let s = run([{ type: "search", seq: 1, language: "eng" }, { type: "cancel" }]);
  assert.equal(s.phase, "idle");
  assert.equal(s.phase === "idle" && s.note, "Search cancelled.");
  // late success and late abort error from the cancelled request
  assert.equal(run([{ type: "found", seq: 1, results: [sub({ id: "z" })] }], s).phase, "idle");
  assert.equal(run([{ type: "failed", seq: 1, error: new SubtitleError("cancelled") }], s).phase, "idle");
  // replaced search: the old request's reply cannot overwrite the new one
  s = run([{ type: "search", seq: 1, language: "eng" }, { type: "search", seq: 2, language: "fre" }]);
  assert.equal(run([{ type: "found", seq: 1, results: [sub({ id: "old" })] }], s).phase, "searching");
  assert.equal(run([{ type: "failed", seq: 1, error: new SubtitleError("offline") }], s).phase, "searching");
  // cancel does nothing outside a search
  const res = run([{ type: "search", seq: 1, language: "eng" }, { type: "found", seq: 1, results: [sub({ id: "a" })] }]);
  assert.equal(reduce(res, { type: "cancel" }), res);
  // a search may not start while a download is installing
  const dl = run([{ type: "download", seq: 2, picked: sub({ id: "a" }) }], res);
  assert.equal(reduce(dl, { type: "search", seq: 3, language: "eng" }), dl);
  // download only from results
  assert.equal(run([{ type: "download", seq: 1, picked: sub({ id: "a" }) }]).phase, "idle");
});

test("state machine: failed download keeps the list; a cancelled search error from the runner returns to idle", () => {
  const a = sub({ id: "a" });
  let s = run([{ type: "search", seq: 1, language: "eng" }, { type: "found", seq: 1, results: [a] }, { type: "download", seq: 2, picked: a }, { type: "failed", seq: 2, error: new SubtitleError("notfound", 404) }]);
  assert.equal(s.phase === "error" && s.action, "download");
  assert.equal(s.phase === "error" && s.results.length, 1);
  s = run([{ type: "download", seq: 3, picked: a }], s); // retry from the kept list
  assert.equal(s.phase, "downloading");
  s = run([{ type: "search", seq: 4, language: "eng" }, { type: "failed", seq: 4, error: new SubtitleError("cancelled") }]);
  assert.equal(s.phase, "idle");
});

const st = (Index: number, extra: Partial<StreamLike> = {}): StreamLike => ({ Index, Type: "Subtitle", Language: "eng", Codec: "subrip", DisplayTitle: `T${Index}`, ...extra });

test("added subtitle detection ignores index shifts and prefers the external track", () => {
  const before = [st(2), st(3, { Language: "fre", DisplayTitle: "French" })];
  const after = [st(2), st(3, { Language: "eng", DisplayTitle: "English - SRT - External", IsExternal: true, Path: "/m/x.eng.srt" }), st(4, { Language: "fre", DisplayTitle: "French" })];
  assert.equal(addedSubtitle(before, after)?.Path, "/m/x.eng.srt");
  assert.equal(addedSubtitle(before, before), null);
  assert.equal(addedSubtitle([], [{ Index: 0, Type: "Video" }]), null);
});

test("remapActive follows the track when an external track shifts indices", () => {
  const before = [st(2), st(3, { Language: "fre", DisplayTitle: "French" })];
  const after = [st(2), st(3, { Language: "eng", IsExternal: true, Path: "/x.srt", DisplayTitle: "E" }), st(4, { Language: "fre", DisplayTitle: "French" })];
  assert.equal(remapActive(before, after, 3), 4);
  assert.equal(remapActive(before, after, 2), 2);
  assert.equal(remapActive(before, after, -1), -1);
  assert.equal(remapActive(before, after, 9), -1);
  assert.equal(remapActive(before, [st(2)], 3), -1);
});

test("waitForNewSubtitle polls, survives a blip, and gives up honestly", async () => {
  const before = [st(2)];
  const waits: number[] = [];
  const pause = async (ms: number) => { waits.push(ms); };
  let n = 0;
  const found = await waitForNewSubtitle(async () => { n++; if (n === 1) throw new Error("blip"); if (n === 2) return before; return [...before, st(3, { IsExternal: true, Path: "/n.srt" })]; }, before, pause);
  assert.equal(found.added?.Index, 3);
  assert.equal(n, 3);
  assert.deepEqual(waits, [1500, 1500, 1500]);
  let m = 0;
  const none = await waitForNewSubtitle(async () => { m++; return before; }, before, pause);
  assert.equal(none.added, null);
  assert.equal(m, 4);
});

test("added messages say plainly what happened", () => {
  assert.match(addedMessage(true, "player", "X"), /Pick it under Audio & subtitles/);
  assert.match(addedMessage(true, "page", "X"), /Audio & subtitles menu/);
  assert.match(addedMessage(false, "player", "X"), /Restart playback/);
  assert.match(addedMessage(false, "page", "X"), /Reopen this page/);
});

test("gating split: the policy opens the per-item dialog, only admins get the season chips", () => {
  const policyOnly = { isAdmin: false, canManageSubtitles: true };
  assert.equal(canManageSubtitles(policyOnly), true);
  assert.equal(canSearchSeasons(policyOnly), false);
  assert.equal(canSearchSeasons({ isAdmin: true }), true);
  assert.equal(canSearchSeasons({}), false);
  assert.equal(canSearchSeasons(null), false);
});

test("added detection counts duplicates and ignores paths: a second identical-looking track is new, a re-download is not", () => {
  const ext = (i: number, path?: string) => st(i, { IsExternal: true, DisplayTitle: "English - SRT - External", Path: path });
  assert.equal(addedSubtitle([ext(2, "/a.srt")], [ext(2, "/a.srt"), ext(3, "/b.srt")])?.Index, 3);
  assert.equal(addedSubtitle([ext(2)], [ext(2), ext(3)])?.Index, 3); // server reports no paths
  assert.equal(addedSubtitle([ext(2, "/a.srt")], [ext(2, "/a.srt")]), null); // identical file re-downloaded: nothing new
});

test("remapActive keeps the n-th of several same-looking tracks", () => {
  const ext = (i: number) => st(i, { IsExternal: true, DisplayTitle: "English - External" });
  assert.equal(remapActive([ext(2), ext(3)], [ext(2), ext(3), ext(4)], 3), 3);
  assert.equal(remapActive([st(1, { Language: "fre" }), ext(3)], [st(1, { Language: "fre" }), st(2, { Language: "spa" }), ext(4)], 3), 4);
});

test("polling stops when aborted (dialog closed) but returns a track found before that", async () => {
  const ctl = new AbortController();
  let loads = 0;
  const r = await waitForNewSubtitle(async () => { loads++; return [st(2)]; }, [st(2)], async () => { if (loads === 1) ctl.abort(); }, 4, 1500, ctl.signal);
  assert.equal(r.aborted, true);
  assert.equal(r.added, null);
  assert.equal(loads, 1);
  const pre = new AbortController();
  pre.abort();
  assert.equal((await waitForNewSubtitle(async () => { loads++; return []; }, [], async () => undefined, 4, 1, pre.signal)).aborted, true);
  const found = await waitForNewSubtitle(async () => [st(2), st(3, { IsExternal: true })], [st(2)], async () => undefined, 4, 1, new AbortController().signal);
  assert.equal(found.added?.Index, 3);
});

test("download timeout says it may still complete; unconfirmed vs unchanged messages differ", () => {
  const m = errorMessage(new SubtitleError("timeout"), "download");
  assert.match(m, /may still complete on the server/);
  assert.doesNotMatch(m, /Try again\./);
  assert.match(addedMessage(null, "player", "X"), /couldn't confirm it's listed yet/);
  assert.match(addedMessage(false, "page", "X"), /track list hasn't changed/);
  assert.notEqual(addedMessage(null, "page", "X"), addedMessage(false, "page", "X"));
});

test("state machine: an unconfirmed download (listed: null) is a finished download", () => {
  const a = sub({ id: "a" });
  const s = run([{ type: "search", seq: 1, language: "eng" }, { type: "found", seq: 1, results: [a] }, { type: "download", seq: 2, picked: a }, { type: "added", seq: 2, listed: null }]);
  assert.equal(s.phase === "added" && s.listed, null);
});
