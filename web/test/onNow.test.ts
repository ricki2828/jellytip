import { test } from "node:test";
import assert from "node:assert/strict";
import { parseOnNow, progressOf } from "../src/lib/onNow.ts";

const N = 1_000_000;

test("on now: sport first, then server order; finished, future and bad items dropped", () => {
  const r = parseOnNow(
    {
      channels: [
        { id: "1:10", name: "TVNZ 1", logo: "v1/iptv/img/x", now: { start: N - 60, stop: N + 60, title: "The News" } },
        { id: "1:11", name: "Sky Sport 1", now: { start: N - 60, stop: N + 600, title: "Super Rugby: Blues v Chiefs" } },
        { id: "1:12", name: "Old", now: { start: N - 600, stop: N - 1, title: "Gone" } },
        { id: "1:13", name: "Soon", now: { start: N + 10, stop: N + 600, title: "Later" } },
        { id: "bad id", name: "x", now: { start: N - 1, stop: N + 1, title: "y" } },
        { id: "1:14", name: "Movies 24", sport: false, now: { start: N - 1, stop: N + 1, title: "Golf Club Massacre" } },
        { id: "1:15", name: "Channel 9", sport: true, programme: { start: N - 1, stop: N + 1, title: "Today" } },
      ],
    },
    N,
  );
  assert.deepEqual(r.map((o) => [o.channelId, o.sport]), [["1:11", true], ["1:15", true], ["1:10", false], ["1:14", false]]);
  assert.equal(r[2].logo, "v1/iptv/img/x");
  assert.deepEqual(parseOnNow(null, N), []);
  assert.equal(parseOnNow({ items: [{ channel: { id: "2:1", name: "ESPN" }, now: { start: N - 1, stop: N + 1, title: "NBA" } }] }, N)[0].sport, true);
});

test("progress clamps", () => {
  assert.equal(progressOf({ start: 0, stop: 100 }, 25), 0.25);
  assert.equal(progressOf({ start: 0, stop: 100 }, 200), 1);
});
