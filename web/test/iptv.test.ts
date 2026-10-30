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
