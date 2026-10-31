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
  assert.deepEqual(parsePrefs({ sport: { teams: ["A"], muted: { sports: ["golf"], leagues: 5 } } }).sport, { teams: ["A"], leagues: [], sports: [], muted: { sports: ["golf"], leagues: [], tags: [] }, setup: false, spoilers: false, flipAlerts: true, panelStyle: "docked" });
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
import { matchOutcome, displayTitle, matchupTitle, parseFixture, parseMatchDetail, statusLabel, venueLine } from "../src/lib/matchInfo.ts";

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

import { liveTabs, agoText, maskText, mayShow, parseLive, pct, volumeText } from "../src/lib/liveStats.ts";

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

import { parseChannelHits } from "../src/lib/search.ts";
import { mineLive, chipText, clampAlertMin, hasLeagues, enqueue, eventText, isNfl, mineInGame, parseFantasy } from "../src/lib/fantasy.ts";

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

test("live drawer tabs: Chat only with a chat block", () => {
  assert.deepEqual(liveTabs(false, false).map(([k]) => k), ["stats", "plays", "odds"]);
  assert.deepEqual(liveTabs(true, false).map(([k]) => k), ["stats", "plays", "odds", "chat"]);
  assert.deepEqual(liveTabs(true, true).map(([k]) => k), ["stats", "plays", "odds", "chat", "fantasy"]);
  assert.deepEqual(liveTabs(false, true).map(([k]) => k), ["stats", "plays", "odds", "fantasy"]);
  assert.equal(parseLive({ chat: { source: "reddit", comments: [] } })!.chat !== null, true);
  assert.equal(parseLive({})!.chat, null);
});

test("match routes: 404 is the route, 403 no access, unknown only that match", () => {
  assert.equal(matchOutcome(404, null), "route-missing");
  assert.equal(matchOutcome(403, { error: "no IPTV" }), "forbidden");
  assert.equal(matchOutcome(200, { id: "x", unknown: true }), "unknown");
  assert.equal(matchOutcome(200, { id: "x", status: "in" }), null);
  assert.equal(matchOutcome(500, null), null);
  assert.equal(matchOutcome(0, null), null);
});

import { readFileSync } from "node:fs";
import { parseOnNow } from "../src/lib/onNow.ts";
import { parseUpcoming } from "../src/lib/upcoming.ts";

const fx = (n: string) => JSON.parse(readFileSync(new URL(`./fixtures/${n}`, import.meta.url), "utf8"));

test("Ricki's real on-now and upcoming payloads: match info reaches the tiles (the info button's input)", () => {
  const on = fx("onnow_ricki_min.json");
  const items = parseOnNow(on, on.now);
  const withFixture = items.filter((o) => o.info.fixture);
  assert.equal(withFixture.length, 3);
  assert.equal(withFixture[0].info.fixture?.id, "espn:basketball~nba:401909286");
  assert.equal(withFixture[0].info.fixture?.home.name, "Phoenix Suns");
  assert.equal(withFixture[0].info.fixture?.leagueKey, "nba");
  assert.equal(withFixture[0].info.fixture?.status, "in");
  // the favourite marker `match: {league}` on the item is not a fixture
  assert.equal(items.filter((o) => !o.info.fixture).length, items.length - 3);
  const up = parseUpcoming(fx("upcoming_ricki_min.json"));
  assert.equal(up.filter((e) => e.fixture).length, 2);
  assert.ok(up.every((e) => !e.fixture || (e.fixture.id && e.fixture.home.name && e.fixture.away.name)));
  // a fixture put on the event group instead of the programme still counts
  const moved = JSON.parse(JSON.stringify(on));
  const c = moved.channels.find((x: { now?: { matchInfo?: unknown } }) => x.now?.matchInfo);
  c.eventGroup = { ...(c.eventGroup ?? {}), matchInfo: c.now.matchInfo };
  delete c.now.matchInfo;
  assert.equal(parseOnNow(moved, moved.now).filter((o) => o.info.fixture).length, 3);
});

import { formTone, hex, heroColors, impliedProb, oddsSplit, startsIn, tint } from "../src/lib/matchInfo.ts";

test("match modal helpers: colours, countdown, odds split, form chips", () => {
  assert.equal(hex("#FFAA00"), "#ffaa00");
  assert.equal(hex("fa0"), "#ffaa00");
  assert.equal(hex("red"), null);
  assert.equal(hex(5), null);
  const f = parseFixture({ ...FIX, home: { name: "Chiefs", color: "e31837", altColor: "#ffb612" }, away: { name: "Bills", color: "nope" }, venue: { name: "Arrowhead", city: "KC", image: "https://r2.thesportsdb.com/x.jpg" } })!;
  assert.deepEqual(heroColors(f), ["#e31837", "#2a2d33"]);
  assert.equal(f.home.altColor, "#ffb612");
  assert.equal(f.venue?.image, "https://r2.thesportsdb.com/x.jpg");
  assert.equal(startsIn(1000 + 134 * 60, 1000), "Starts in 2h 14m");
  assert.equal(startsIn(1000 + 35 * 60, 1000), "Starts in 35 min");
  assert.equal(startsIn(900, 1000), "Starting now");
  assert.equal(Math.round(impliedProb("-150")! * 100), 60);
  assert.equal(Math.round(impliedProb("+130 · +3.5")! * 100), 43);
  assert.equal(impliedProb("n/a"), null);
  const sp = oddsSplit("-150", "+130")!;
  assert.ok(Math.abs(sp.home + sp.away - 1) < 1e-9);
  assert.ok(sp.home > 0.5);
  assert.equal(oddsSplit("-150", null), null);
  assert.equal(formTone("W"), "w");
  assert.equal(formTone("l"), "l");
  assert.equal(formTone("D"), "d");
  assert.equal(tint("#ff0000", 0.2), "rgba(255, 0, 0, 0.2)");
});

import { readable } from "../src/lib/matchInfo.ts";
import { newMoments, situationBlocks, sparkPoints, statNumber, statShare } from "../src/lib/liveStats.ts";

test("live panel redesign: new fields parse, split-bar shares, sparkline, moments, per-sport situation", () => {
  const d = parseLive({
    layout: "Cricket",
    situation: { batters: [{ name: "Williamson", runs: 52, balls: 41, onStrike: true }], bowler: { name: "Bumrah", figures: "2-18" }, rr: "6.1", rrr: "7.4", lastBalls: ["1", "W", "4", "0", "6", "1", "2"] },
    moments: [{ at: 100, kind: "wicket", team: "away", label: "Wicket" }, { at: 90, kind: "x" }, { label: "no at" }],
    leaders: [{ name: "Jokic", team: "DEN", line: "28 pts", headshot: "https://a.espncdn.com/i/headshots/nba/players/full/3112335.png" }],
    plays: [{ text: "Booker 3PT", scoring: true, team: "home", points: 3 }, { text: "Foul" }],
    market: { home: 0.55, away: 0.45, series: [{ t: 1000, p: 0.5 }, { t: 1600, p: 0.6 }, { t: 1900, p: 7 }] },
  })!;
  assert.equal(d.layout, "cricket");
  assert.equal(d.moments.length, 2);
  assert.equal(d.leaders[0].headshot?.startsWith("https://a.espncdn.com"), true);
  assert.equal(d.plays[0].delta, 3);
  assert.equal(d.plays[0].team, "home");
  assert.equal(d.plays[1].delta, null);
  assert.equal(d.market!.series.length, 2);
  assert.equal(statNumber("47%"), 47);
  assert.equal(statNumber("1,204 yds"), 1204);
  assert.equal(statNumber("n/a"), null);
  assert.equal(statShare("30", "10"), 0.75);
  assert.equal(statShare("0", "0"), null);
  assert.equal(statShare("x", "5"), null);
  assert.equal(sparkPoints([{ t: 0, p: 1 }, { t: 100, p: 0 }], 200, 40), "0,0 200,40");
  assert.equal(sparkPoints([{ t: 0, p: 1 }], 200, 40), "");
  assert.deepEqual(newMoments(d.moments, 95).map((m) => m.at), [100]);
  assert.deepEqual(newMoments(d.moments, 100), []);
  const cr = situationBlocks("cricket", d.situation, { home: "NZ", away: "IND" });
  assert.equal(cr[0].kind, "kv");
  assert.deepEqual((cr[0] as { rows: string[][] }).rows[0], ["Williamson *", "52 (41)"]);
  assert.deepEqual((cr[cr.length - 1] as { items: string[] }).items, ["W", "4", "0", "6", "1", "2"]);
  const nfl = situationBlocks("nfl", { down: "2nd", distance: 7, fieldPos: 66, redZone: false, possession: "home", ballOn: "KC 34" }, { home: "KC", away: "BUF" });
  assert.equal(nfl[0].kind, "kv");
  assert.deepEqual((nfl[1] as { pos: number; side: string }).pos, 66);
  const bb = situationBlocks("basketball", { quarters: [{ period: "Q1", home: 28, away: 25 }, { home: 30, away: 33 }], fouls: { home: "5", away: "3" }, run: "12-2 run" }, { home: "PHX", away: "DEN" });
  assert.equal(bb[0].kind, "grid");
  assert.deepEqual(bb.map((b) => b.kind), ["grid", "kv", "bar"]);
  // pinned shape: yardLine, drive {summary}, periods, run {text, team}, equation, and any other {home, away} key as a bar
  const nfl2 = situationBlocks("nfl", { down: "3rd", distance: 4, yardLine: "BUF 41", fieldPos: 59, possession: "away", redZone: true, drive: { summary: "7 plays, 52 yds" } }, { home: "KC", away: "BUF" });
  assert.deepEqual((nfl2[0] as { rows: string[][] }).rows, [["Down & distance", "3rd & 4"], ["Ball on", "BUF 41"], ["Drive", "7 plays, 52 yds"]]);
  assert.deepEqual({ ...(nfl2[1] as object) }, { kind: "field", pos: 59, side: "away", redZone: true, text: "BUF 41" });
  const bb2 = situationBlocks("basketball", { periods: [{ home: 20, away: 22 }, { home: 25, away: 19 }], run: { text: "12-2 run", team: "home" }, fouls: { home: 5, away: 3 }, teamRebounds: { home: "3", away: "5" } }, { home: "PHX", away: "DEN" });
  assert.deepEqual((bb2[0] as { head: string[] }).head, ["Q1", "Q2"]);
  assert.deepEqual(bb2.map((b) => b.kind), ["grid", "kv", "bar", "bar"]);
  assert.equal((bb2[3] as { label: string }).label, "Team Rebounds");
  const cr2 = situationBlocks("cricket", { bowler: { name: "Bumrah", figures: "2-18", overs: "4.2" }, equation: "NZ need 41 from 30", rr: 6.1 }, { home: "NZ", away: "IND" });
  assert.deepEqual((cr2[0] as { rows: string[][] }).rows, [["Bowling", "Bumrah 2-18 (4.2 ov)"], ["Run rate", "6.1"], ["Target", "NZ need 41 from 30"]]);
  assert.equal(parseLive({ plays: [{ id: "p1", text: "x" }] })!.plays[0].id, "p1");
  assert.deepEqual(situationBlocks("unknown", { a: 1 }, { home: "A", away: "B" }), []);
  assert.deepEqual(situationBlocks("nfl", null, { home: "A", away: "B" }), []);
  assert.deepEqual(situationBlocks("football", { possession: { home: "58", away: "42" } }, { home: "A", away: "B" }).map((b) => b.kind), ["bar"]);
});

test("readable team colours on the dark panel", () => {
  assert.equal(readable("#0e2240") !== "#0e2240", true);
  assert.equal(readable("#e56020"), "#e56020");
  assert.equal(readable("nope"), "nope");
});

import { blend, deltaE, onColor, teamColors, washBackground } from "../src/lib/broadcast.ts";

test("broadcast look: clash rule, contrast text, wash", () => {
  assert.equal(deltaE("#e31837", "#e31837"), 0);
  assert.ok(deltaE("#e31837", "#e8203c") < 20);
  assert.ok(deltaE("#e31837", "#00338d") > 40);
  assert.equal(onColor("#ffc72c"), "#000000");
  assert.equal(onColor("#0b0b0b"), "#ffffff");
  assert.equal(onColor("#1a4fbf"), "#ffffff");
  assert.equal(onColor("#f58426"), "#000000");
  // away uses its alt colour when the primaries are close
  const f = parseFixture({ ...FIX, home: { name: "A", color: "e31837" }, away: { name: "B", color: "e8203c", altColor: "00338d" } })!;
  assert.deepEqual(teamColors(f), { home: "#e31837", away: "#00338d", known: true });
  const g = parseFixture({ ...FIX, home: { name: "A", color: "e31837" }, away: { name: "B", color: "e8203c" } })!;
  assert.equal(teamColors(g).away, "#e8203c");
  const none = teamColors(parseFixture(FIX));
  assert.equal(none.known, false);
  assert.equal(washBackground(none), "#0D1015");
  assert.match(washBackground(teamColors(f)), /^linear-gradient\(90deg, #[0-9a-f]{6} 0 50%, #[0-9a-f]{6} 50% 100%\)$/);
  assert.equal(blend("#ffffff", 0, "#0D1015"), "#0d1015");
  assert.equal(blend("#ffffff", 1), "#ffffff");
  assert.equal(teamColors(null).known, false);
});

import { flagOf } from "../src/lib/matchInfo.ts";
import { seriesColor } from "../src/lib/broadcast.ts";
import { tyreLetter } from "../src/lib/liveStats.ts";

test("event match info (F1, golf), tennis flags, and the three live layouts", () => {
  const f1 = parseFixture({ id: "f1:mex-race", kind: "event", leagueKey: "f1", title: "Mexico City GP · Race", participants: ["VER", { name: "NOR" }], status: "in", league: "Formula 1" })!;
  assert.equal(f1.kind, "event");
  assert.equal(f1.title, "Mexico City GP · Race");
  assert.deepEqual(f1.participants, ["VER", "NOR"]);
  assert.equal(matchupTitle(f1), "Mexico City GP · Race");
  assert.equal(displayTitle("Race", f1), "Mexico City GP · Race");
  assert.equal(parseFixture({ id: "x", kind: "event" }), null); // an event needs its title
  assert.equal(parseFixture({ id: "x", home: { name: "A" } }), null); // a match still needs both sides
  assert.deepEqual(teamColors(f1), { home: "#E10600", away: "#E10600", known: true });
  assert.equal(seriesColor("golf-pga"), "#0B6E4F");
  assert.equal(seriesColor("tennis-atp"), null);
  assert.equal(teamColors(parseFixture({ id: "g", kind: "event", leagueKey: "tennis-atp", title: "x" })).known, false);
  const t = parseFixture({ id: "t1", leagueKey: "tennis-atp", home: { name: "Kimmer Coppejans", country: "BE" }, away: { name: "Chris Wood", country: "nz" } })!;
  assert.equal(t.kind, "match");
  assert.equal(t.home.country, "BE");
  assert.equal(flagOf("NZ"), "🇳🇿");
  assert.equal(flagOf("GBR"), "GBR");
  assert.equal(flagOf(null), null);
  assert.equal(tyreLetter("soft"), "S");
  assert.equal(tyreLetter("INTERMEDIATE"), "I");
  assert.equal(tyreLetter("x"), null);

  const f1b = situationBlocks("f1", { lap: 31, laps: 71, session: "Race", flag: "sc", fastestLap: { driver: "NOR", time: "1:19.4" }, standings: [{ pos: 1, driver: "VER", team: "Red Bull", teamColor: "3671C6", gap: "Leader", tyre: "hard", tyreAge: 12, fastest: false }, { pos: 2, driver: "NOR", gap: "+2.1", tyre: "M", fastest: true }, { pos: 3 }] }, { home: "", away: "" });
  assert.deepEqual({ ...(f1b[0] as object) }, { kind: "banner", lap: "LAP 31/71", session: "Race", flag: "sc", fastest: "NOR 1:19.4" });
  const tower = f1b[1] as { rows: { driver: string; color: string | null; tyre: string | null; fastest: boolean }[] };
  assert.equal(tower.rows.length, 2);
  assert.equal(tower.rows[0].color, "#3671c6");
  assert.equal(tower.rows[0].tyre, "H");
  assert.equal(tower.rows[1].fastest, true);

  const ten = situationBlocks("tennis", { sets: [{ home: 6, away: 4 }, { home: 7, away: 6, tb: { home: 7, away: 5 } }], game: { home: "30", away: "AD" }, serving: "away", stats: { aces: { home: 4, away: 9 } } }, { home: "Coppejans", away: "Wood" });
  const grid = ten[0] as { head: string[]; rows: { name: string; serving: boolean; cells: string[]; game: string }[] };
  assert.deepEqual(grid.head, ["S1", "S2", "GAME"]);
  assert.equal(grid.rows[1].serving, true);
  assert.equal(grid.rows[0].serving, false);
  assert.equal(grid.rows[0].game, "30");
  assert.equal(grid.rows[1].cells[1], "6⁽5⁾");
  assert.equal(ten[1].kind, "bar");

  const golf = situationBlocks("golf", { round: 3, leaderboard: [{ pos: "T1", player: "Hideki Matsuyama", country: "JPN", toPar: "-12", thru: "F", today: "-4" }, { player: "" }], favourites: [{ pos: "T14", player: "Ryan Fox", country: "NZ", toPar: "-5", thru: "12" }] }, { home: "", away: "" });
  const lb = golf[0] as { title: string; rows: { player: string; toPar: string }[]; pinned: { player: string; country: string | null }[] };
  assert.equal(lb.title, "Round 3");
  assert.equal(lb.rows.length, 1);
  assert.equal(lb.pinned[0].player, "Ryan Fox");
  assert.deepEqual(situationBlocks("f1", { standings: [] }, { home: "", away: "" }), []);
});

test("RedZone (multi): fixture without teams, games board, fantasy gating by leagueKey", () => {
  const m = parseFixture({ id: "multi:nfl:2026-11-01", kind: "multi", leagueKey: "nfl", title: "NFL RedZone" })!;
  assert.equal(m.kind, "multi");
  assert.equal(matchupTitle(m), "NFL RedZone");
  assert.equal(displayTitle("RedZone", m), "NFL RedZone");
  assert.equal(isNfl(m.leagueKey), true);
  assert.equal(teamColors(m).known, false);
  assert.equal(parseFixture({ id: "x", kind: "multi" }), null);
  const d = parseLive({
    layout: "multi",
    market: null,
    games: [
      { matchId: "espn:football~nfl:1", home: { name: "Chiefs", short: "KC" }, away: "Bills", score: { home: 17, away: 14 }, clock: "3:12", period: "Q4", situation: { down: "2nd", distance: 7, yardLine: "BUF 12", redZone: true } },
      { matchId: "espn:football~nfl:2", home: { name: "Lions" }, away: { name: "Packers", short: "GB" }, score: null },
      { home: "x" },
    ],
  })!;
  assert.equal(d.layout, "multi");
  assert.equal(d.games.length, 2);
  assert.equal(d.games[0].away.name, "Bills");
  assert.equal(d.games[0].situation, "2nd & 7 · BUF 12");
  assert.equal(d.games[0].redZone, true);
  assert.equal(d.games[1].redZone, false);
  assert.equal(d.market, null);
  // all my live starters across games (the Fantasy tab in multi mode) are counted from every league's starters
  const live = parseFantasy({ leagues: [{ id: "L", name: "CCP", me: { team: "R", points: 10 }, opp: { team: "O", points: 8 }, starters: [{ side: "me", name: "A", points: 5, matchId: "g1", gameState: "in" }, { side: "me", name: "B", points: 2, matchId: "g2", gameState: "in" }, { side: "opp", name: "C", points: 1, matchId: "g3", gameState: "post" }] }] })!;
  assert.equal(live.leagues[0].starters.filter((s) => s.gameState === "in").length, 2);
  assert.equal(mineLive(live), 2);
  assert.equal(mineLive(null), 0);
});

test("round 2 pinned shapes: practice tower with best and gap, tennis tournament matches, event session/round", () => {
  const ev = parseFixture({ id: "f1:mex-q", kind: "event", leagueKey: "f1", title: "Mexico City GP · Qualifying", session: "Qualifying", flag: "🇲🇽" })!;
  assert.equal(ev.session, "Qualifying");
  assert.equal(ev.flag, "🇲🇽");
  assert.equal(ev.round, null);
  const q = situationBlocks("f1", { session: "Qualifying", standings: [{ pos: 1, driver: "NOR", number: 4, best: "1:15.2", gap: "BEST", tyre: "S" }, { pos: 2, driver: "VER", best: "1:15.3", gap: "+0.123" }] }, { home: "", away: "" });
  const tw = q.find((b) => b.kind === "tower") as { rows: { best: string | null; gap: string | null }[] };
  assert.equal(tw.rows[1].best, "1:15.3");
  assert.equal(tw.rows[1].gap, "+0.123");
  const ten = situationBlocks("tennis", { matches: [{ id: "m1", home: { name: "Chris Wood" }, away: "K. Coppejans", status: "in", round: "R32", court: "Court 2", sets: [{ home: 6, away: 4 }, { home: 3, away: 3 }], serving: "home" }, { home: "x" }] }, { home: "", away: "" });
  assert.equal(ten.length, 1);
  const mt = ten[0] as { kind: string; rows: { home: string; away: string; score: string | null; serving: string | null; court: string | null }[] };
  assert.equal(mt.kind, "matches");
  assert.equal(mt.rows.length, 1);
  assert.equal(mt.rows[0].score, "6-4 3-3");
  assert.equal(mt.rows[0].serving, "home");
  // a single match without game points still shows its set grid
  const single = situationBlocks("tennis", { sets: [{ home: 6, away: 3 }], serving: "away" }, { home: "A", away: "B" });
  assert.deepEqual((single[0] as { head: string[] }).head, ["S1"]);
  const g = situationBlocks("golf", { round: 2, leaderboard: [{ pos: 1, player: "X", country: "JPN", flag: "🇯🇵", toPar: "-9" }] }, { home: "", away: "" });
  assert.equal((g[0] as { rows: { country: string | null }[] }).rows[0].country, "🇯🇵");
});

test("RedZone server shapes: games with status, last play; matchInfo on the channel itself", () => {
  const d = parseLive({
    layout: "multi",
    games: [
      { matchId: "a", home: "KC", away: "BUF", status: "in", score: { home: 17, away: 14 }, period: "Q4", clock: "3:12", situation: { down: "2nd", distance: 7, yardLine: "BUF 12", redZone: true, lastPlay: "Pass complete for 9" } },
      { matchId: "b", home: "DET", away: "GB", status: "pre", startsAt: "2026-11-01T18:00:00Z" },
      { matchId: "c", home: "NE", away: "NYJ", status: "post", score: { home: 20, away: 10 } },
    ],
    moments: [{ at: "2026-11-01T17:00:00Z", kind: "touchdown", team: "home", label: "Touchdown", matchId: "a" }],
  })!;
  assert.deepEqual(d.games.map((g) => g.status), ["in", "pre", "post"]);
  assert.equal(d.games[0].lastPlay, "Pass complete for 9");
  assert.equal(d.games[1].startsAt, Date.parse("2026-11-01T18:00:00Z") / 1000);
  assert.equal(d.moments.length, 1);
  // matchInfo on the channel item (no guide) or on its now_next entry still reaches the tile
  const mi = { id: "multi:nfl:2026-11-01", kind: "multi", title: "NFL RedZone", leagueKey: "nfl" };
  assert.equal(parseSport({ matchInfo: mi }, { title: "x" }).fixture?.id, "multi:nfl:2026-11-01");
  assert.equal(parseSport({ now_next: { matchInfo: mi } }, { title: "x" }).fixture?.kind, "multi");
  const ch = parseChannelHits([{ id: "3:9", name: "NFL RedZone", matchInfo: mi }]);
  assert.equal(ch[0].fixture?.kind, "multi");
});

test("RedZone channel with no guide: matchInfo on the tile makes it an on-now card with the match id", () => {
  const now = 1_900_000_000;
  const mi = { id: "multi:nfl:2026-11-01", kind: "multi", title: "NFL RedZone", leagueKey: "nfl", startsAt: now - 7200 };
  const tile = { id: "3:900", name: "NFL RedZone HD", sport: true, now: null, next: null, matchInfo: mi };
  const items = parseOnNow({ now, channels: [tile, { id: "3:901", name: "Plain", now: null }] }, now);
  assert.equal(items.length, 1);
  assert.equal(items[0].title, "NFL RedZone");
  assert.equal(items[0].info.fixture?.id, "multi:nfl:2026-11-01");
  assert.equal(items[0].info.fixture?.kind, "multi");
  assert.equal(items[0].sport, true);
  // via a now_next entry
  const nn = parseOnNow({ now, channels: [{ id: "3:902", name: "RZ", now_next: { now: null, next: null, matchInfo: mi } }] }, now);
  assert.equal(nn[0].info.fixture?.id, "multi:nfl:2026-11-01");
  // an ordinary match without a programme is still skipped
  assert.equal(parseOnNow({ now, channels: [{ id: "3:903", name: "X", matchInfo: { id: "m", home: { name: "A" }, away: { name: "B" } } }] }, now).length, 0);
});

test("Polymarket winner markets: ranked outcomes, flip to a name, modal detail", () => {
  const d = parseLive({
    market: { kind: "winner", source: "polymarket", question: "Mexico City GP winner", outcomes: [{ name: "Norris", p: 0.22 }, { name: "Verstappen", p: 0.41 }, { name: "bad", p: 5 }, { p: 0.1 }, { name: "Leclerc", p: 0.18 }, { name: "A", p: 0.05 }, { name: "B", p: 0.04 }, { name: "C", p: 0.03 }, { name: "D", p: 0.02 }], volume: 250000, flip: { at: "2026-10-31T10:00:00Z", from: "Norris", to: "Verstappen", prob: 0.41 }, series: [{ t: 1000, p: 0.3 }, { t: 1600, p: 0.41 }] },
  })!;
  assert.equal(d.market!.kind, "winner");
  assert.deepEqual(d.market!.outcomes.map((o) => o.name), ["Verstappen", "Norris", "Leclerc", "A", "B", "C"]);
  assert.equal(d.market!.outcomes.length, 6);
  assert.equal(d.market!.flip?.to, "Verstappen");
  assert.equal(flipText(d.market!.flip!, { home: "H", away: "A" }), "📈 Polymarket flip: Verstappen now favourites · 41%");
  assert.equal(flipText({ at: 1, from: "home", to: "away", prob: 0.54 }, { home: "Knicks", away: "76ers" }), "📈 Polymarket flip: 76ers now favourites · 54%");
  // head to head is unchanged, and a winner kind without outcomes is not a market
  assert.equal(parseLive({ market: { home: 0.6, away: 0.4 } })!.market!.kind, "h2h");
  assert.equal(parseLive({ market: { kind: "winner", outcomes: [] } })!.market, null);
  // the match detail carries a winner market only
  const det = parseMatchDetail({ matchInfo: { id: "f1:x", kind: "event", title: "GP", leagueKey: "f1" }, market: { kind: "winner", outcomes: [{ name: "VER", p: 0.5 }] } })!;
  assert.equal(det.market?.outcomes[0].name, "VER");
  assert.equal(parseMatchDetail({ matchInfo: { id: "m", home: { name: "A" }, away: { name: "B" } }, market: { home: 0.5, away: 0.5 } })!.market, null);
});
