import { test } from "node:test";
import assert from "node:assert/strict";
import { chunks, layoutRow, ticks, visibleRows, windowStart } from "../src/lib/epg.ts";
import { engineFor, linkFailure, parseTarget, playQuery, targetPath } from "../src/lib/iptvPlay.ts";

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
  assert.equal(playQuery({ channel: "3:77" }, "web-ab"), "v1/iptv/play?channel=3%3A77&format=ts&device=web-ab");
  assert.equal(playQuery({ channel: "3:77", start: 5, stop: 9 }, "d"), "v1/iptv/play?channel=3%3A77&start=5&stop=9&device=d");
  assert.equal(playQuery({ recording: "4" }, "d"), "v1/iptv/play?recording=4&device=d");
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
