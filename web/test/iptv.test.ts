import { test } from "node:test";
import assert from "node:assert/strict";
import { chunks, layoutRow, ticks, visibleRows, windowStart } from "../src/lib/epg.ts";
import { engineFor, linkFailure, nativeLink, parseTarget, playQuery, targetPath } from "../src/lib/iptvPlay.ts";

const H = 3600;

test("window starts on the half hour at least 30 min before now", () => {
  assert.equal(windowStart(10 * H + 50 * 60), 10 * H);
  assert.equal(windowStart(10 * H + 10 * 60), 9 * H + 1800);
});

test("blocks clip to the window, trim overlaps, drop slivers", () => {
  const from = 10 * H;
  const to = 13 * H;
  const rows = layoutRow(
    [
      { start: 11 * H, stop: 12 * H, title: "B" },
      { start: 9 * H, stop: 10 * H + 1800, title: "A" },
      { start: 11 * H + 1800, stop: 12 * H + 1800, title: "overlap" },
      { start: 12 * H + 1800, stop: 12 * H + 1830, title: "sliver" },
      { start: 13 * H, stop: 14 * H, title: "after" },
    ],
    from,
    to,
    0.1,
    11 * H + 600,
  );
  assert.deepEqual(rows.map((b) => [b.prog.title, b.left, b.width, b.clipped, b.live, b.past]), [
    ["A", 0, 180, true, false, true],
    ["B", 360, 360, false, true, false],
    ["overlap", 720, 180, false, false, false],
  ]);
});

test("ticks every half hour inside the window", () => {
  assert.deepEqual(ticks(10 * H + 600, 11 * H + 1), [10 * H + 1800, 11 * H]);
});

test("visible rows include overscan and stay in range", () => {
  assert.deepEqual(visibleRows(0, 500, 64, 3), [0, 3]);
  assert.deepEqual(visibleRows(640, 320, 64, 100, 2), [8, 17]);
});

test("chunks split ids for the 300-channel guide limit", () => {
  assert.deepEqual(chunks([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
});

test("route query ↔ target; nothing playable → null", () => {
  assert.equal(parseTarget(new URLSearchParams("series=1:s:2")), null);
  assert.equal(parseTarget(new URLSearchParams("")), null);
  const t = parseTarget(new URLSearchParams("channel=3:77&start=1700000000&stop=x"))!;
  assert.deepEqual(t, { channel: "3:77", start: 1700000000 });
  assert.deepEqual(parseTarget(new URLSearchParams(targetPath({ recording: "9" }).split("?")[1])), { recording: "9" });
});

test("play query: live gets a format, catch-up and recordings don't; device always", () => {
  assert.equal(playQuery({ channel: "3:77" }, "web-ab"), "v1/iptv/play?channel=3%3A77&format=ts&compat=web&device=web-ab");
  assert.equal(playQuery({ channel: "3:77", start: 5, stop: 9 }, "d"), "v1/iptv/play?channel=3%3A77&start=5&stop=9&compat=web&device=d");
  assert.equal(playQuery({ recording: "4" }, "d"), "v1/iptv/play?recording=4&device=d");
  // The native player never asks for the web re-encode
  assert.equal(playQuery({ channel: "3:77" }, "d", "ts", true), "v1/iptv/play?channel=3%3A77&format=ts&device=d&client=native");
  assert.equal(playQuery({ channel: "3:77" }, "d", "ts", true), "v1/iptv/play?channel=3%3A77&format=ts&device=d&client=native");
});

test("engine by link extension or mime", () => {
  assert.equal(engineFor("v1/iptv/s/abc.ts"), "mpegts");
  assert.equal(engineFor("v1/iptv/s/abc.m3u8"), "hls");
  assert.equal(engineFor("v1/iptv/s/abc.mkv", "video/x-matroska"), "native");
  assert.equal(engineFor("v1/iptv/s/abc", "application/vnd.apple.mpegurl"), "hls");
});

test("410 stale and 403 expired renew; 409 is full; other 403s are final", () => {
  assert.equal(linkFailure(410, "stale"), "renew");
  assert.equal(linkFailure(403, "expired"), "renew");
  assert.equal(linkFailure(403, "bad_token"), "fatal");
  assert.equal(linkFailure(403, "provider_auth"), "fatal");
  assert.equal(linkFailure(409, "max_connections"), "full");
  assert.equal(linkFailure(502, "provider_error"), "fatal");
});

import { fallbackFormat, playbackFailureText } from "../src/lib/iptvPlay.ts";
import { scrub } from "../src/lib/scrub.ts";

test("live TS falls back to HLS once; nothing else falls back", () => {
  assert.equal(fallbackFormat(true, "ts"), "m3u8");
  assert.equal(fallbackFormat(true, "m3u8"), null);
  assert.equal(fallbackFormat(false, "ts"), null);
});

test("failure text names the engine detail and never claims a stop", () => {
  assert.match(playbackFailureText("native", "MediaError 4", 200), /can't play this file \(MediaError 4\)/);
  assert.match(playbackFailureText("mpegts", "", 200), /couldn't play it\.$/);
  assert.equal(playbackFailureText("hls", "x", 0), "The server can't be reached.");
});

test("debug log drops the signed part of stream links", () => {
  assert.equal(scrub("GET https://h/jellytip-api/v1/iptv/s/abc123.ts?x=1 failed"), "GET https://h/jellytip-api/v1/iptv/s/…?x=1 failed");
});

import { blockerText, canStop, parseBlocker } from "../src/lib/holders.ts";

test("409 holders: recording wins and is stoppable", () => {
  const b = parseBlocker({ connection: { active: [{ kind: "viewer", title: "Ch 5" }, { kind: "recording", recordingId: 7, title: "Final", endsAt: 1_800_000_000 }] } });
  assert.equal(b?.kind, "recording");
  assert.ok(canStop(b));
  assert.match(blockerText(b!), /^Recording Final is using your stream until \d/);
});

test("409 holders: viewer cannot be stopped, old server shapes tolerated", () => {
  const v = parseBlocker({ connection: { active: [{ kind: "viewer", channel: "Sky Sport 1" }] } });
  assert.equal(canStop(v), false);
  assert.equal(blockerText(v!), "Sky Sport 1 is being watched on another device.");
  const old = parseBlocker({ connection: { active: [{ kind: "recording", label: "Match" }] } });
  assert.equal(canStop(old), false);
  assert.equal(blockerText(old!), "Recording Match is using your stream.");
  assert.equal(parseBlocker({ connection: { active: [{ kind: "watch", userName: "Sam" }] } })?.kind, "viewer");
  assert.equal(parseBlocker(null), null);
  assert.equal(parseBlocker({ error: "x" }), null);
});

import { normaliseServer, normaliseWebUrl, parseProviderLink } from "../src/lib/providerLink.ts";

test("provider link: get.php and player_api.php with login", () => {
  assert.deepEqual(parseProviderLink("http://host.tv:8080/get.php?username=bob&password=p%40ss&type=m3u_plus&output=ts"), {
    kind: "xtream",
    server: "http://host.tv:8080",
    username: "bob",
    password: "p@ss",
  });
  assert.deepEqual(parseProviderLink("  host.tv/player_api.php?username=a&password=b "), { kind: "xtream", server: "http://host.tv", username: "a", password: "b" });
});

test("provider link: stream URL, plain playlist, junk", () => {
  assert.deepEqual(parseProviderLink("http://host.tv:8080/live/u1/pw%2F2/123.ts"), { kind: "xtream", server: "http://host.tv:8080", username: "u1", password: "pw/2" });
  assert.deepEqual(parseProviderLink("https://x.example/list/abc.m3u"), { kind: "m3u", playlistUrl: "https://x.example/list/abc.m3u" });
  assert.equal(parseProviderLink("not a link"), null);
  assert.equal(parseProviderLink(""), null);
});

test("server field: scheme added, tails and spaces stripped", () => {
  assert.equal(normaliseServer(" provider.example:8080/ "), "http://provider.example:8080");
  assert.equal(normaliseServer("https://p.example/get.php?username=a"), "https://p.example");
  assert.equal(normaliseServer("http://p.example/player_api.php"), "http://p.example");
  assert.equal(normaliseServer("hello"), null);
  assert.equal(normaliseWebUrl("p.example/a.m3u"), "http://p.example/a.m3u");
});

import { countdown, dueNudges, parseReminders, stateOf } from "../src/lib/reminders.ts";

const rem = (id: number, start: number, stop: number) => ({ id, key: `k${id}`, title: `Match ${id}`, league: "NRL", start, stop, state: "upcoming", channel: { id: "1:c:1", name: "Sport 1" }, alternateChannels: [] });

test("reminders: parse, order, drop unusable", () => {
  const r = parseReminders({ items: [rem(2, 2000, 3000), rem(1, 1000, 2000), { id: 3, title: "no channel", start: 1, stop: 2 }, { title: "no id" }] });
  assert.deepEqual(r.map((x) => x.id), [1, 2]);
  assert.equal(r[0].event.reminderId, 1);
  assert.deepEqual(parseReminders(null), []);
});

test("reminders: state and countdown follow the clock", () => {
  const e = { start: 10_000, stop: 17_000 };
  assert.equal(stateOf(e, 1000), "upcoming");
  assert.equal(stateOf(e, 10_000 - 600), "soon");
  assert.equal(stateOf(e, 10_000), "live");
  assert.equal(stateOf(e, 17_000), "ended");
  assert.deepEqual(countdown(e, 10_000 - 7800), { text: "in 2h 10m", tone: "time" });
  assert.deepEqual(countdown(e, 10_000 - 300), { text: "Starting soon", tone: "soon" });
  assert.deepEqual(countdown(e, 11_000), { text: "LIVE", tone: "rec" });
});

test("reminders: nudge once at soon and once at kick-off", () => {
  const items = parseReminders({ items: [rem(1, 10_000, 17_000)] });
  const seen = new Set<string>();
  assert.equal(dueNudges(items, seen, 1000).length, 0);
  assert.deepEqual(dueNudges(items, seen, 9500).map((n) => n.at), ["soon"]);
  assert.equal(dueNudges(items, seen, 9600).length, 0);
  assert.deepEqual(dueNudges(items, seen, 10_100).map((n) => n.at), ["live"]);
  assert.equal(dueNudges(items, seen, 10_200).length, 0);
});

import { clampSeek, clockText, recordingLength } from "../src/lib/recPlay.ts";

test("recording length: the file's own, else from the recording's times", () => {
  assert.equal(recordingLength(null, 1000, 5400), 5400);
  assert.equal(recordingLength({ state: "done", start: 1000, stop: 4600, began: 940, ended: 5000 }, 9999, NaN), 4060);
  assert.equal(recordingLength({ state: "done", start: 1000, stop: 4600, stopPadded: 4900 }, 9999, Infinity), 3900);
  assert.equal(recordingLength({ state: "done", start: 1000, stop: 4600, progressDuration: 3700 }, 9999, 0), 3700);
  assert.equal(recordingLength({ state: "recording", start: 1000, stop: 4600, began: 940 }, 1500, Infinity), 560);
  assert.equal(recordingLength(null, 1, NaN), 0);
});

test("seek clamps inside what exists; live edge keeps a margin", () => {
  assert.equal(clampSeek(-5, 100, false), 0);
  assert.equal(clampSeek(500, 100, false), 99);
  assert.equal(clampSeek(500, 100, true), 97);
  assert.equal(clockText(3723), "1:02:03");
  assert.equal(clockText(65), "01:05");
});

test("native link: direct answers keep the provider URL, companion links become absolute", () => {
  const abs = (rel: string) => `https://c.example/${rel}`;
  assert.deepEqual(nativeLink({ url: "v1/iptv/s/abc.ts" }, abs), { url: "https://c.example/v1/iptv/s/abc.ts", direct: false });
  assert.deepEqual(nativeLink({ url: "http://p.example:8080/live/u/p/1.ts", direct: true }, abs), { url: "http://p.example:8080/live/u/p/1.ts", direct: true });
  // A direct flag on a relative link is not trusted as a provider URL
  assert.deepEqual(nativeLink({ url: "v1/iptv/s/x.ts", direct: true }, abs), { url: "https://c.example/v1/iptv/s/x.ts", direct: false });
});

import { parseFeaturePairs } from "../src/lib/specialsIptv.ts";

test("double feature: IPTV halves parse, pairs missing a half drop", () => {
  const v = { id: "3:m:9", title: "Léon", year: 1994, quality: ["1080p"] };
  const pairs = parseFeaturePairs([
    { a: "lib1", b: null, bIptv: v, reason: "r" },
    { a: "lib2", b: "lib3" },
    { a: "lib4", b: null },
    { a: null, b: null, aIptv: v },
  ]);
  assert.equal(pairs.length, 2);
  assert.equal(pairs[0].b, null);
  assert.equal(pairs[0].bIptv?.title, "Léon");
  assert.equal(pairs[1].a, "lib2");
  assert.deepEqual(parseFeaturePairs(null), []);
});

import { muteChoices, parseLearned, withMuted } from "../src/lib/sportMute.ts";
import { parsePrefs } from "../src/lib/prefs.ts";

test("not interested: choices, add/remove, prefs shape", () => {
  const m = { sports: ["speedway"], leagues: ["NBL"], tags: [] };
  assert.deepEqual(muteChoices({ league: "NRLW", sportName: "Rugby League" }, m).map((c) => c.label), ["Not interested in NRLW", "Not interested in Rugby League"]);
  assert.deepEqual(muteChoices({ league: "nbl", sportName: "speedway" }, m), []);
  assert.deepEqual(muteChoices({ league: null, sportName: null }, m), []);
  assert.deepEqual(withMuted(m, "leagues", "NRLW", true).leagues, ["NBL", "NRLW"]);
  assert.deepEqual(withMuted(m, "leagues", "nbl", false).leagues, []);
  assert.deepEqual(parsePrefs({ sport: { teams: ["A"], muted: { sports: ["golf"], leagues: 5 } } }).sport, { teams: ["A"], leagues: [], sports: [], muted: { sports: ["golf"], leagues: [], tags: [] }, setup: false, spoilers: false, flipAlerts: true });
  assert.deepEqual(parsePrefs({}).sport.muted, { sports: [], leagues: [], tags: [] });
});

test("learned taste: strongest first, keys read, negatives and junk dropped", () => {
  const l = parseLearned({ affinities: [{ key: "sport:cricket", score: 9 }, { key: "team:BLACKCAPS", score: 12 }, { key: "league:NFL", score: -2 }, { key: "weird", score: 3 }, { key: "sport:horse_racing", score: 0 }] });
  assert.deepEqual(l.map((x) => x.key), ["team:BLACKCAPS", "sport:cricket"]);
  assert.equal(l[1].label, "Cricket");
  assert.equal(l[0].label, "BLACKCAPS (team)");
  assert.deepEqual(parseLearned({ "sport:cricket": 2 }).map((x) => x.key), ["sport:cricket"]);
  assert.deepEqual(parseLearned(null), []);
});

import { applySportState, hasSportPrefs, nextSportState, sportState } from "../src/lib/sportMute.ts";

test("sports I follow: tap cycles neutral, follow, mute; never both lists", () => {
  const m = { sports: ["Golf"], leagues: [], tags: [] };
  assert.equal(sportState("cricket", ["Cricket"], m), "follow");
  assert.equal(sportState("golf", [], m), "mute");
  assert.equal(sportState("Tennis", [], m), "neutral");
  assert.equal(nextSportState("neutral"), "follow");
  assert.equal(nextSportState("follow"), "mute");
  assert.equal(nextSportState("mute"), "neutral");
  const f = applySportState("Golf", "follow", ["Cricket"], m);
  assert.deepEqual(f.sports, ["Cricket", "Golf"]);
  assert.deepEqual(f.muted.sports, []);
  const x = applySportState("Cricket", "mute", ["Cricket"], m);
  assert.deepEqual(x.sports, []);
  assert.deepEqual(x.muted.sports, ["Golf", "Cricket"]);
  assert.equal(hasSportPrefs({ teams: [], leagues: [], sports: [], muted: { sports: [], leagues: [], tags: [] }, setup: false }), false);
  assert.equal(hasSportPrefs({ teams: [], leagues: [], sports: [], muted: { sports: [], leagues: [], tags: ["womens"] }, setup: false }), true);
});

import { continuePath, continueTitle, mergeContinue, parseContinue } from "../src/lib/continue.ts";

const ce = (o: Record<string, unknown>) => ({ source: "iptv", kind: "movie", id: "3:m:9", title: "Léon", pos: 600, dur: 6600, at: 1000, poster: null, backdrop: null, tech: [], ...o });

test("continue: parse, titles and resume paths", () => {
  const l = parseContinue({ items: [ce({}), ce({ kind: "episode", id: "3:e:77", seriesId: "3:s:5", seriesTitle: "Show", season: 2, episode: 3, title: "Pilot", pos: 12 }), ce({ kind: "recording", id: "rec:4" }), { kind: "movie" }, ce({ source: "other" })] });
  assert.equal(l.length, 3);
  assert.equal(continueTitle(l[1]), "Show · S2 E3 · Pilot");
  assert.equal(continuePath(l[0]), "/iptv/play?vod=3%3Am%3A9&t=L%C3%A9on&resume=600");
  assert.equal(continuePath(l[1]), "/iptv/play?series=3%3As%3A5&episode=3%3Ae%3A77&t=Show+%C2%B7+S2+E3+%C2%B7+Pilot".replace("Show+%C2%B7+S2+E3+%C2%B7+Pilot", encodeURIComponent("Show · S2 E3 · Pilot")));
  assert.match(continuePath(l[2]), /recording=4/);
  assert.deepEqual(parseContinue(null), []);
});

test("continue: library and IPTV by recency, next up only for shows not above", () => {
  const lib = (Id: string, d?: string, SeriesId?: string) => ({ Id, SeriesId, UserData: d ? { LastPlayedDate: d } : undefined });
  const resume = [lib("a", "2026-10-30T10:00:00Z"), lib("b", "2026-10-28T10:00:00Z")];
  const iptv = parseContinue({ items: [ce({ id: "3:m:1", at: Date.parse("2026-10-29T10:00:00Z") / 1000 })] });
  const next = [lib("n1", undefined, "S1"), lib("n2", undefined, "SHOW9")];
  const iNext = parseContinue({ items: [ce({ kind: "episode", id: "3:e:2", seriesId: "SHOW9", pos: 0 }), ce({ kind: "episode", id: "3:e:3", seriesId: "3:s:8", pos: 0 })] });
  const m = mergeContinue([...resume, lib("c", "2026-10-01T00:00:00Z", "SHOW9")], next, iptv, iNext);
  const names = m.map((x) => (x.kind === "lib" ? x.item.Id : x.entry.id));
  assert.deepEqual(names, ["a", "3:m:1", "b", "c", "n1", "3:e:3"]);
});

import { liveExtrasQuery, livePath, parseLiveExtras } from "../src/lib/iptvPlay.ts";

test("live extras: kick-off and alternates round-trip through the play link", () => {
  const x = { kickoff: { start: 1000, stop: 2000 }, alternates: [{ id: "3:77", name: "Sky Sport 2" }, { id: "3:78", name: "Fox & Co ~ HD" }] };
  const path = livePath("3:76", "Sky Sport 1", x);
  const q = new URLSearchParams(path.split("?")[1]);
  assert.equal(q.get("channel"), "3:76");
  assert.deepEqual(parseLiveExtras(q), { kickoff: { start: 1000, stop: 2000 }, alternates: [{ id: "3:77", name: "Sky Sport 2" }, { id: "3:78", name: "Fox & Co ~ HD" }], startsAt: null, eventKey: null, matchId: null, desc: null, upNext: [] });
  assert.equal(liveExtrasQuery(null), "");
  assert.equal(liveExtrasQuery({ kickoff: { start: 5, stop: 5 }, alternates: [] }), "");
  assert.deepEqual(parseLiveExtras(new URLSearchParams("alt=bad&ko=5&kos=3")), { kickoff: null, alternates: [], startsAt: null, eventKey: null, matchId: null, desc: null, upNext: [] });
  const p = parseLiveExtras(new URLSearchParams(liveExtrasQuery({ startsAt: 1_900_000_000, eventKey: "nfl:a-v-b" }).slice(1)));
  assert.equal(p.startsAt, 1_900_000_000);
  assert.equal(p.eventKey, "nfl:a-v-b");
});

import { inText, isPre, parsePhase, parseStartsAt, preGameCard, startsLine } from "../src/lib/preGame.ts";

test("pre-game: phase and start parse, the clock decides, texts", () => {
  const at = parseStartsAt("2026-10-31T18:00:00Z")!;
  assert.equal(at, Date.parse("2026-10-31T18:00:00Z") / 1000);
  assert.equal(parseStartsAt("nope"), null);
  assert.equal(parseStartsAt(null), null);
  assert.equal(parsePhase("pre"), "pre");
  assert.equal(parsePhase("weird"), null);
  assert.equal(isPre(at, at - 600, "pre"), true);
  assert.equal(isPre(at, at - 600, null), true);
  assert.equal(isPre(at, at + 1, "pre"), false);
  assert.equal(isPre(at, at - 600, "live"), false);
  assert.equal(isPre(null, 0, "pre"), false);
  assert.equal(inText(at, at - 35 * 60), "in 35 min");
  assert.equal(inText(at, at - 130 * 60), "in 2h 10m");
  assert.equal(inText(at, at + 5), "any moment");
  assert.match(startsLine(at, at - 600), /^Starts \d\d:\d\d · in 10 min$/);
  assert.match(preGameCard(at, at - 600), /^Pre-game — the event starts at \d\d:\d\d \(in 10 min\)$/);
});

import { cardLine, parseHub } from "../src/lib/collections.ts";

test("collections hub: sections in order, empty and unusable dropped, card line", () => {
  const sec = parseHub({
    sections: [
      { key: "franchises", title: "Franchises", count: 2, items: [{ id: "tmdb:10", title: "Alien", art: { poster: "p" }, total: 12, libCount: 7, iptvCount: 5, ranked: false }, { id: "tmdb:10", title: "dup" }, { title: "no id" }] },
      { key: "empty", title: "Empty", items: [] },
      { key: "top-lists", title: "Top lists", items: [{ id: "imdb-top", title: "IMDb Top", total: 1, libCount: 1, iptvCount: 0, ranked: true }] },
    ],
  });
  assert.deepEqual(sec.map((s) => s.key), ["franchises", "top-lists"]);
  assert.equal(sec[0].items.length, 1);
  assert.equal(sec[1].items[0].ranked, true);
  assert.equal(cardLine(sec[0].items[0]), "12 titles · 5 on IPTV");
  assert.equal(cardLine({ total: 1, libCount: 1, iptvCount: 0 }), "1 title");
  assert.deepEqual(parseHub(null), []);
});

test("collections hub: optional art fields", () => {
  const c = parseHub({ sections: [{ key: "directors", title: "Directors", items: [{ id: "dir:1", title: "Nolan", mediaType: "movie", art: { itemId: "a".repeat(32), person: { id: "b".repeat(32), imageTag: "t" } } }] }] })[0].items[0];
  assert.equal(c.itemId, "a".repeat(32));
  assert.deepEqual(c.person, { id: "b".repeat(32), imageTag: "t" });
  assert.equal(c.mediaType, "movie");
});

import { counts, filterChannels, parsePrune, sportBadge, windowSlice, withHidden } from "../src/lib/channelPrune.ts";

test("channel pruning: parse, filter, badges, bulk hide, window", () => {
  const l = parsePrune({
    policy: "sports",
    channels: [
      { sid: 1, name: "ESPN", sports: "yes", sportShare: 0.82, hidden: false },
      { sid: 2, name: "TLC", sports: "no", hidden: true },
      { sid: 3, name: "FX", sports: "sometimes", sportShare: 0.05, lastSport: "NFL", hidden: false },
      { sid: 4, name: "Local 9", sports: "weird" },
      { name: "no sid" },
      { sid: 1, name: "dup" },
    ],
  })!;
  assert.equal(l.policy, "sports");
  assert.deepEqual(l.channels.map((c) => c.sid), ["1", "2", "3", "4"]);
  assert.equal(l.channels[3].sports, "unknown");
  assert.deepEqual(counts(l.channels), { total: 4, hidden: 1 });
  assert.deepEqual(filterChannels(l.channels, "", "hidden").map((c) => c.name), ["TLC"]);
  assert.deepEqual(filterChannels(l.channels, "es", "all").map((c) => c.name), ["ESPN"]);
  assert.deepEqual(filterChannels(l.channels, "", "sometimes").map((c) => c.name), ["FX"]);
  assert.equal(sportBadge(l.channels[0]), "Sport 82%");
  assert.equal(sportBadge(l.channels[2]), "Sometimes · NFL last week");
  assert.equal(sportBadge(l.channels[1]), "No sport");
  assert.equal(sportBadge(l.channels[3]), "Not known");
  assert.equal(withHidden(l.channels, new Set(["1"]), true)[0].hidden, true);
  assert.equal(parsePrune({ channels: [] })?.policy, null);
  assert.equal(parsePrune(null), null);
  assert.deepEqual(windowSlice(0, 400, 50, 1000), { from: 0, to: 16 });
  assert.deepEqual(windowSlice(5000, 400, 50, 1000), { from: 92, to: 116 });
  assert.deepEqual(windowSlice(49_900, 400, 50, 1000), { from: 990, to: 1000 });
});

import { lastUpdated, retryText } from "../src/lib/channelPrune.ts";

test("refresh guide: last updated and retry texts", () => {
  const now = 1_900_000_000;
  assert.equal(lastUpdated(now - 30, now), "Last updated just now");
  assert.equal(lastUpdated(now - 7200, now), "Last updated 2 h ago");
  assert.equal(lastUpdated(now - 25 * 60, now), "Last updated 25 min ago");
  assert.equal(lastUpdated((now - 7200) * 1000, now), "Last updated 2 h ago");
  assert.equal(lastUpdated(null, now), null);
  assert.equal(retryText(420), "Try again in 7 min");
  assert.equal(retryText(30), "Try again in 1 min");
  assert.equal(retryText(undefined), "Try again in 10 min");
});

import { parseSport } from "../src/lib/sport.ts";
import { displayTitle, matchupTitle, parseFixture, parseMatchDetail, statusLabel, venueLine } from "../src/lib/matchInfo.ts";

const FIX = { id: "nfl:401", league: "NFL", home: { name: "Chiefs", short: "KC", record: "6-1" }, away: { name: "Bills", short: "BUF" }, startsAt: "2026-10-31T18:00:00Z", venue: { name: "Arrowhead", city: "Kansas City" }, status: "pre", inferred: true };

test("match info: fixture, inferred title, detail sections", () => {
  const f = parseFixture(FIX)!;
  assert.equal(matchupTitle(f), "Chiefs v Bills");
  assert.equal(displayTitle("NFL on CBS", f), "Chiefs v Bills");
  assert.equal(displayTitle("NFL on CBS", { ...f, inferred: false }), "NFL on CBS");
  assert.equal(venueLine(f), "Arrowhead, Kansas City");
  assert.equal(f.score, null);
  assert.equal(statusLabel({ ...f, status: "in", score: { home: 3, away: 0, clock: "12:04", period: "Q2" } }, 0), "LIVE · 12:04");
  assert.equal(statusLabel({ ...f, status: "post" }, 0), "FT");
  // The older {team, league} match shape is not a fixture
  assert.equal(parseFixture({ team: "Chiefs", league: "NFL" }), null);
  assert.equal(parseFixture(null), null);
  const d = parseMatchDetail({
    matchInfo: FIX,
    lineups: { announced: false, home: [], away: [] },
    odds: { provider: "ESPN BET", home: { moneyline: "-150", spread: "-3.5" }, away: { moneyline: "+130" }, overUnder: "47.5", updatedAt: "2026-10-31T10:00:00Z" },
    form: { home: ["W", "W", "L"], away: ["L"] },
    h2h: ["KC 27-24 BUF", { summary: "BUF 24-20 KC" }],
    broadcasts: ["CBS"],
    channels: [{ id: "3:7", name: "CBS HD", quality: { bucket: "fhd" } }, { name: "no id" }],
    pending: true,
  })!;
  assert.equal(d.lineups?.announced, false);
  assert.equal(d.odds?.home, "-150 · -3.5");
  assert.equal(d.odds?.away, "+130");
  assert.deepEqual(d.h2h, ["KC 27-24 BUF", "BUF 24-20 KC"]);
  assert.deepEqual(d.channels, [{ id: "3:7", name: "CBS HD", quality: "fhd" }]);
  assert.equal(d.pending, true);
  assert.equal(parseMatchDetail({}), null);
  assert.equal(parseMatchDetail({ matchInfo: FIX })?.odds, null);
  assert.equal(parseMatchDetail({ match: FIX }), null);
});

test("match info is read from matchInfo, never from the favourite marker match", () => {
  const raw = { match: { team: "Chiefs", league: "NFL" } };
  assert.equal(parseSport(raw, { title: "x", matchInfo: { ...FIX, id: "nfl:999" } }).fixture?.id, "nfl:999");
  assert.deepEqual(parseSport(raw, { title: "x", matchInfo: FIX }).match, { team: "Chiefs", league: "NFL" });
  assert.equal(parseSport({ match: { ...FIX, team: "Chiefs" } }, { title: "x" }).fixture, null);
});

import { agoText, maskText, mayShow, parseLive, pct, volumeText } from "../src/lib/liveStats.ts";

test("live panel: parse, odds as percentages, spoiler gate, chat mask", () => {
  const d = parseLive({
    status: "in",
    clock: "08:12",
    period: "Q3",
    score: { home: 61, away: 58 },
    stats: [{ label: "FG%", home: "47%", away: "44%" }, { home: "no label" }],
    leaders: [{ team: "NYK", name: "Brunson", line: "24 pts" }],
    plays: Array.from({ length: 20 }, (_, i) => ({ clock: "1:0" + (i % 10), period: "Q3", text: "Play " + i, scoring: i === 0 })),
    winProb: { home: 0.62 },
    market: { source: "polymarket", question: "q", home: 0.58, away: 0.42, volume: 1_234_567, url: "https://x", updatedAt: "2026-10-31T10:00:00Z" },
    chat: { source: "reddit", thread: "Game thread", comments: [{ author: "a", text: "what the fuck", score: 3 }, { text: "" }] },
    updatedAt: 1_900_000_000,
  })!;
  assert.equal(d.stats.length, 1);
  assert.equal(d.plays.length, 15);
  assert.equal(d.winProb, 0.62);
  assert.equal(pct(d.market!.home), "58%");
  assert.equal(volumeText(d.market!.volume), "$1.2M traded");
  assert.equal(volumeText(48_000), "$48k traded");
  assert.equal(volumeText(null), null);
  assert.equal(d.chat!.comments.length, 1);
  assert.equal(maskText(d.chat!.comments[0].text), "what the f***");
  assert.equal(agoText(1000, 1030), "30 s ago");
  assert.equal(agoText(1000, 1000 + 240), "4 min ago");
  assert.equal(mayShow(true, false), true);
  assert.equal(mayShow(false, false), false);
  assert.equal(mayShow(false, true), true);
  assert.equal(parseLive({ market: { home: 2, away: -1 } })!.market, null);
  assert.equal(parseLive(null), null);
});

import { flipText } from "../src/lib/liveStats.ts";

test("market flip: parsed with the favourite, text names the new favourite", () => {
  const d = parseLive({ market: { source: "polymarket", home: 0.46, away: 0.54, favourite: "away", flip: { at: "2026-10-31T10:00:00Z", from: "home", to: "away", prob: 0.54 } } })!;
  assert.equal(d.market!.favourite, "away");
  assert.deepEqual(d.market!.flip, { at: Date.parse("2026-10-31T10:00:00Z") / 1000, from: "home", to: "away", prob: 0.54 });
  assert.equal(flipText(d.market!.flip!, { home: "Knicks", away: "76ers" }), "📈 Polymarket flip: 76ers now favourites · 54%");
  assert.equal(parseLive({ market: { home: 0.5, away: 0.5, flip: { at: "x", to: "away", prob: 0.5 } } })!.market!.flip, null);
  assert.equal(parseLive({ market: { home: 0.5, away: 0.5 } })!.market!.flip, null);
  assert.equal(parsePrefs({}).sport.flipAlerts, true);
  assert.equal(parsePrefs({ sport: { flipAlerts: false } }).sport.flipAlerts, false);
});

import { chipText, clampAlertMin, hasLeagues, enqueue, eventText, isNfl, mineInGame, parseFantasy } from "../src/lib/fantasy.ts";

const FANT = {
  week: 9,
  updatedAt: 1_900_000_000,
  leagues: [
    {
      id: "L1",
      name: "CCP Dynasty",
      me: { team: "Ricki", points: 93.8, projected: 120 },
      opp: { team: "Opp", points: 79.2 },
      starters: [
        { side: "me", name: "Gibbs", pos: "RB", nflTeam: "DET", points: 21.4, matchId: "nfl:401772", gameState: "in" },
        { side: "me", name: "Goff", pos: "QB", nflTeam: "DET", points: 12, matchId: "nfl:401772", gameState: "in" },
        { side: "opp", name: "Barkley", pos: "RB", nflTeam: "PHI", points: 9, matchId: "nfl:401772", gameState: "in" },
        { side: "me", name: "Kelce", pos: "TE", nflTeam: "KC", points: 3, matchId: "nfl:999999", gameState: "pre" },
      ],
    },
    { id: "L2", name: "Fantasy", me: { team: "R", points: 87.4, projected: null }, opp: { team: "O", points: 90.1 }, starters: [] },
    { id: "bad" },
  ],
  events: [{ at: 1_899_999_990, league: "CCP Dynasty", name: "Gibbs", delta: 6.4, side: "me", reason: "TD catch, 34 yds" }, { at: 1_899_999_991, league: "CCP Dynasty", name: "Lamb", delta: 7.1, side: "opp", reason: "Catch, 41 yds" }, { at: 1_899_999_992, league: "Fantasy", name: "Nobody", delta: 3.2, side: "me" }, { name: "no at" }],
};

test("fantasy: parse, chip, scoring toast text, my starters in a game", () => {
  const d = parseFantasy(FANT)!;
  assert.equal(d.leagues.length, 2);
  assert.equal(d.events.length, 3);
  assert.equal(chipText(d), "🏈 CCP 93.8 – 79.2   🏈 Fantasy 87.4 – 90.1");
  assert.equal(eventText(d.events[0], d), "🏈 +6.4 Gibbs · TD catch, 34 yds (CCP) · you lead 93.8 – 79.2");
  const trail = { ...d, leagues: [{ ...d.leagues[0], me: { ...d.leagues[0].me, points: 70 } }] };
  assert.equal(eventText(d.events[1], trail), "🏈 Opp +7.1 Lamb · Catch, 41 yds (CCP) · you trail 70.0 – 79.2");
  assert.equal(eventText(d.events[2], d), "🏈 +3.2 Nobody (Fantasy) · you trail 87.4 – 90.1");
  assert.equal(d.events[2].reason, null);
  assert.deepEqual(enqueue([1, 2, 3], 4), [2, 3, 4]);
  assert.deepEqual(enqueue([], 1), [1]);
  assert.equal(clampAlertMin(0), 1);
  assert.equal(clampAlertMin(99), 10);
  assert.equal(clampAlertMin(undefined), 3);
  assert.equal(parsePrefs({}).fantasy.alertMin, 3);
  assert.equal(parsePrefs({ fantasy: { alertMin: 5.4 } }).fantasy.alertMin, 5);
  assert.equal(parsePrefs({ fantasy: { alertMin: 50 } }).fantasy.alertMin, 10);
  assert.equal(mineInGame(d, "nfl:401772"), 2);
  assert.equal(mineInGame(d, "nfl:555555"), 0);
  assert.equal(mineInGame(null, "nfl:401772"), 0);
  assert.equal(isNfl("nfl"), true);
  assert.equal(isNfl(null), false);
  assert.equal(isNfl("nba"), false);
  assert.equal(parseFixture({ ...FIX, leagueKey: "NFL" })!.leagueKey, "nfl");
  assert.equal(parseFixture(FIX)!.leagueKey, null);
  assert.equal(hasLeagues(d), true);
  assert.equal(hasLeagues(parseFantasy({ providers: {} })), false);
  assert.equal(hasLeagues(null), false);
  assert.equal(parseFantasy({}), null);
  assert.deepEqual(d.providers, { sleeper: { linked: false }, yahoo: { available: false, linked: false } });
  assert.deepEqual(parseFantasy({ providers: { yahoo: { available: true } } })!.leagues, []);
  const pv = parseFantasy({ leagues: [], providers: { sleeper: { linked: true }, yahoo: { available: true, linked: false } } })!;
  assert.deepEqual(pv.providers, { sleeper: { linked: true }, yahoo: { available: true, linked: false } });
  assert.equal(chipText({ week: null, leagues: [], events: [], updatedAt: null }), null);
  assert.equal(parsePrefs({ fantasy: { sleeper: { username: " ricki28 " } } }).fantasy.sleeperUsername, "ricki28");
  assert.equal(parsePrefs({}).fantasy.sleeperUsername, null);
  assert.equal(parsePrefs({}).fantasy.alerts, true);
  assert.equal(parsePrefs({ fantasy: { alerts: false } }).fantasy.alerts, false);
});

import { parseIntegrations, statusText } from "../src/lib/integrations.ts";

test("integrations: parse statuses, status lines", () => {
  const l = parseIntegrations([{ service: "yahoo", configured: true, checkedOk: true, updatedAt: 5 }, { service: "reddit", configured: false }, { service: "other", configured: true }, null]);
  assert.deepEqual(l.map((x) => x.service), ["yahoo", "reddit"]);
  assert.equal(statusText(l[0]), "Configured · checked OK");
  assert.equal(statusText(l[1]), "Not configured");
  assert.equal(statusText({ configured: true, checkedOk: false }), "Configured · the check failed");
  assert.equal(statusText({ configured: true, checkedOk: null }), "Configured");
  assert.equal(statusText(undefined), "Not configured");
  assert.deepEqual(parseIntegrations({ integrations: [{ service: "reddit", configured: true }] }).map((x) => x.configured), [true]);
  assert.deepEqual(parseIntegrations(null), []);
});

test("live extras carry the description and what is on next (phone panel)", () => {
  const q = new URLSearchParams(liveExtrasQuery({ desc: "Live cricket from Hamilton", upNext: [{ title: "Highlights", start: 1_900_000_000 }, { title: "News" }] }).slice(1));
  const p = parseLiveExtras(q);
  assert.equal(p.desc, "Live cricket from Hamilton");
  assert.deepEqual(p.upNext, [{ title: "Highlights", start: 1_900_000_000 }, { title: "News", start: undefined }]);
});
