import { test } from "node:test";
import assert from "node:assert/strict";
import { epKey, FLOOD_LIMIT, MAX_SERIES, addRequest, applyObservation, ago, dueForRefresh, emptyStore, eventText, markAllRead, parseStore, pickIptv, reqKey, storageKey, tidyEvents, unreadCount, EXPIRY_MS, THROTTLE_MS, type Observed } from "../src/lib/inbox.ts";

const NOW = 1_800_000_000_000;
const ep = (id: string, n: number, at = NOW - 1000) => ({ id, show: "Show", season: 2, episode: n, at });
const title = (r: 0 | 1 | 2, extra = {}) => ({ r, title: "Dune", requested: true, ...extra });

test("first run seeds silently, even when everything is already available", () => {
  const obs: Observed = { titles: { "movie:1": title(2, { libId: "a" }) }, eps: { s1: [ep("e1", 1), ep("e2", 2)] } };
  const s = applyObservation(emptyStore("u"), obs, NOW);
  assert.equal(s.seeded, true);
  assert.equal(s.events.length, 0);
  assert.equal(unreadCount(s, NOW), 0);
  assert.deepEqual([...s.snap.eps.s1].sort(), ["#2x1", "#2x2", "e1", "e2"]); // ids plus season+episode keys
  // same data again: still nothing
  assert.equal(applyObservation(s, obs, NOW + 1).events.length, 0);
});

test("new episode of a followed series; library arrival of a request; IPTV arrival", () => {
  let s = applyObservation(emptyStore("u"), { titles: { "movie:1": title(0), "tv:2": title(0, { title: "Andor" }) }, eps: { s1: [ep("e1", 1)] } }, NOW);
  s = applyObservation(s, { titles: { "movie:1": title(2, { libId: "abc" }), "tv:2": title(1, { title: "Andor", iptvId: "7:s:99" }) }, eps: { s1: [ep("e2", 2, NOW), ep("e1", 1)] } }, NOW + 5000);
  const by = Object.fromEntries(s.events.map((e) => [e.id, e]));
  assert.equal(eventText(by["lib:movie:1"]), "Your request for Dune is now in your library");
  assert.equal(by["lib:movie:1"].link, "/item/abc");
  assert.equal(eventText(by["iptv:tv:2"]), "Andor is now available via IPTV");
  assert.equal(by["iptv:tv:2"].link, "/iptv/item?id=7%3As%3A99");
  assert.equal(eventText(by["ep:e2"]), "New episode: Show S2E2");
  assert.equal(by["ep:e2"].link, "/item/e2");
  assert.equal(s.events.length, 3);
  assert.equal(unreadCount(s, NOW + 5000), 3);
});

test("a title or series new to the snapshot is seeded silently; unchanged or falling rank is quiet", () => {
  let s = applyObservation(emptyStore("u"), { titles: {}, eps: {} }, NOW);
  s = applyObservation(s, { titles: { "movie:9": title(2, { libId: "z", requested: false }) }, eps: { s9: [ep("x1", 1)] } }, NOW + 1);
  assert.equal(s.events.length, 0);
  s = applyObservation(s, { titles: { "movie:9": title(0) } }, NOW + 2); // left the library
  assert.equal(s.events.length, 0);
  assert.equal(s.snap.titles["movie:9"].r, 0);
});

test("a request recorded after the seed fires its arrival even if the next look finds it already there", () => {
  let s = applyObservation(emptyStore("u"), { titles: {}, eps: {} }, NOW);
  s = addRequest(s, { key: reqKey("movie", 5), tmdbId: 5, media: "movie", title: "Heat", at: NOW });
  s = applyObservation(s, { titles: { "movie:5": { r: 2, title: "Heat", requested: true, libId: "h" } } }, NOW + 10);
  assert.deepEqual(s.events.map((e) => e.id), ["lib:movie:5"]);
});

test("dedupe: the same arrival seen twice, or detected again after a drop and return, is one event", () => {
  let s = applyObservation(emptyStore("u"), { titles: { "movie:1": title(0) } }, NOW);
  const up: Observed = { titles: { "movie:1": title(2, { libId: "a" }) } };
  s = applyObservation(s, up, NOW + 1);
  s = applyObservation(s, { titles: { "movie:1": title(0) } }, NOW + 2);
  s = applyObservation(s, up, NOW + 3);
  assert.equal(s.events.length, 1);
  // read state survives a duplicate, whichever copy comes first
  const read = markAllRead(s);
  for (const order of [[{ ...read.events[0], read: false }, ...read.events], [...read.events, { ...read.events[0], read: false }]]) {
    const merged = tidyEvents(order, NOW + 4);
    assert.equal(merged.length, 1);
    assert.equal(merged[0].read, true);
  }
});

test("a source that failed leaves its part of the snapshot alone; partial IPTV lookups do not drop a known IPTV rank", () => {
  let s = applyObservation(emptyStore("u"), { titles: { "movie:1": title(1, { iptvId: "1:m:1" }) }, eps: { s1: [ep("e1", 1)] } }, NOW);
  s = applyObservation(s, { titles: undefined, eps: undefined }, NOW + 1);
  assert.equal(s.snap.titles["movie:1"].r, 1);
  assert.deepEqual(s.snap.eps.s1, ["e1", "#2x1"]);
  s = applyObservation(s, { titles: { "movie:1": title(0, { partial: true }) } }, NOW + 2);
  assert.equal(s.snap.titles["movie:1"].r, 1);
  s = applyObservation(s, { titles: { "movie:1": title(2, { libId: "q" }) } }, NOW + 3);
  assert.equal(s.events.length, 1);
});

test("30-day expiry: old events drop on tidy, from the count, and when a new look runs", () => {
  const old = { id: "ep:old", kind: "episode" as const, title: "S", link: "/item/o", at: NOW - EXPIRY_MS - 1, read: false };
  const fresh = { ...old, id: "ep:new", at: NOW - EXPIRY_MS + 60_000 };
  assert.deepEqual(tidyEvents([old, fresh], NOW).map((e) => e.id), ["ep:new"]);
  const s = { ...emptyStore("u"), seeded: true, events: [old, fresh] };
  assert.equal(unreadCount(s, NOW), 1);
  assert.deepEqual(applyObservation(s, { titles: {}, eps: {} }, NOW).events.map((e) => e.id), ["ep:new"]);
});

test("events are newest first and bounded", () => {
  const many = Array.from({ length: 260 }, (_, i) => ({ id: `ep:${i}`, kind: "episode" as const, title: "S", link: "/", at: NOW - i * 1000, read: false }));
  const t = tidyEvents(many, NOW);
  assert.equal(t.length, 200);
  assert.equal(t[0].id, "ep:0");
});

test("mark all read clears the badge", () => {
  let s = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1)] } }, NOW);
  s = applyObservation(s, { eps: { s1: [ep("e2", 2), ep("e1", 1)] } }, NOW + 1);
  assert.equal(unreadCount(s, NOW + 1), 1);
  assert.equal(unreadCount(markAllRead(s), NOW + 1), 0);
});

test("provider ownership: only titles from the person's own or shared providers count", () => {
  const vod = (id: string, tmdbId: number, kind: "movie" | "series" = "movie") => ({ id, source: "iptv" as const, kind, tmdbId, title: "T", quality: [], tech: [] });
  const items = [vod("9:m:1", 5), vod("3:m:2", 5)];
  assert.equal(pickIptv(items, "movie", 5, [3, "4"])?.id, "3:m:2");
  assert.equal(pickIptv(items, "movie", 5, [1, 2]), null);
  assert.equal(pickIptv([vod("3:s:1", 5, "series")], "movie", 5, [3]), null); // wrong kind
  assert.equal(pickIptv(items, "movie", 6, [3]), null); // wrong title
});

test("session scoping: another person's stored inbox never loads", () => {
  let a = applyObservation(emptyStore("userA"), { eps: { s1: [ep("e1", 1)] } }, NOW);
  a = applyObservation(a, { eps: { s1: [ep("e2", 2), ep("e1", 1)] } }, NOW + 1);
  assert.equal(a.events.length, 1);
  const raw = JSON.stringify(a);
  assert.notEqual(storageKey("userA"), storageKey("userB"));
  const b = parseStore(raw, "userB"); // even if the wrong blob were read under B's key
  assert.equal(b.userId, "userB");
  assert.equal(b.events.length, 0);
  assert.equal(b.seeded, false);
  assert.equal(parseStore(raw, "userA").events.length, 1);
  assert.equal(parseStore("not json", "userA").events.length, 0);
  assert.equal(parseStore(null, "userA").seeded, false);
});

test("throttle and wording helpers", () => {
  const s = { ...emptyStore("u"), seeded: true, lastRefresh: NOW };
  assert.equal(dueForRefresh(s, NOW + THROTTLE_MS - 1), false);
  assert.equal(dueForRefresh(s, NOW + THROTTLE_MS), true);
  assert.equal(eventText({ id: "x", kind: "library", title: "Dune", link: "/", at: 0, read: false }), "Dune is now in your library");
  assert.equal(eventText({ id: "x", kind: "episode", title: "Show", season: 2, episode: 5, link: "/", at: 0, read: false }), "New episode: Show S2E5");
  assert.equal(ago(NOW - 2 * 3600_000, NOW), "2h ago");
});

test("events are stamped with detection time, not the episode's DateCreated", () => {
  let s = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1)] } }, NOW);
  s = applyObservation(s, { eps: { s1: [ep("e2", 2, NOW - 20 * 24 * 3600_000), ep("e1", 1)] } }, NOW + 7);
  assert.equal(s.events[0].at, NOW + 7);
});

test("unobserved series carry through; a rescan with changed dates fires nothing", () => {
  let s = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1)], s2: [ep("f1", 1)] } }, NOW);
  s = applyObservation(s, { eps: { s2: [ep("f1", 1)] } }, NOW + 1); // s1 not asked this time
  assert.deepEqual(s.snap.eps.s1, ["e1", "#2x1"]);
  s = applyObservation(s, { eps: { s1: [ep("e2", 2), ep("e1", 1)] } }, NOW + 2); // a finished series gets an episode
  assert.deepEqual(s.events.map((e) => e.id), ["ep:e2"]);
  const again = applyObservation(s, { eps: { s1: [ep("e2", 2, 5), ep("e1", 1, 9)], s2: [ep("f1", 1, 123)] } }, NOW + 3);
  assert.equal(again.events.length, 1);
});

test("series cap drops the least recently touched", () => {
  const eps: Record<string, ReturnType<typeof ep>[]> = {};
  for (let i = 0; i < MAX_SERIES + 5; i++) eps[`s${i}`] = [ep(`e${i}`, 1)];
  const s = applyObservation(emptyStore("u"), { eps }, NOW);
  assert.equal(Object.keys(s.snap.eps).length, MAX_SERIES);
  assert.equal(s.snap.eps.s0, undefined);
});

test("several new episodes of one show are one grouped event", () => {
  let s = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1)] } }, NOW);
  s = applyObservation(s, { eps: { s1: [1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => ep(`e${n}`, n)) } }, NOW + 1);
  assert.equal(s.events.length, 1);
  assert.equal(eventText(s.events[0]), "Show: 8 new episodes");
  assert.equal(s.events[0].link, "/item/s1");
});

test("flood guard: more than 20 events in one look collapse to one summary", () => {
  const seed: Record<string, ReturnType<typeof ep>[]> = {};
  const next: Record<string, ReturnType<typeof ep>[]> = {};
  for (let i = 0; i < FLOOD_LIMIT + 1; i++) {
    seed[`s${i}`] = [ep(`a${i}`, 1)];
    next[`s${i}`] = [ep(`b${i}`, 2), ep(`a${i}`, 1)];
  }
  let s = applyObservation(emptyStore("u"), { eps: seed }, NOW);
  s = applyObservation(s, { eps: next }, NOW + 1);
  assert.equal(s.events.length, 1);
  assert.equal(s.events[0].kind, "summary");
  assert.equal(s.events[0].count, FLOOD_LIMIT + 1);
  // at the limit it stays itemised
  const small = applyObservation(applyObservation(emptyStore("u"), { eps: { s0: [ep("a", 1)] } }, NOW), { eps: { s0: [ep("b", 2), ep("a", 1)] } }, NOW + 1);
  assert.equal(small.events[0].kind, "episode");
});

test("old or malformed stored shapes re-seed silently", () => {
  const good = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1)] } }, NOW);
  assert.equal(parseStore(JSON.stringify(good), "u").seeded, true);
  assert.equal(parseStore(JSON.stringify({ ...good, v: 1 }), "u").seeded, false); // old version
  assert.equal(parseStore(JSON.stringify({ ...good, snap: { titles: {}, eps: { s1: [{ id: "e1" }] } } }), "u").seeded, false); // eps not strings
  assert.equal(parseStore(JSON.stringify({ ...good, snap: { titles: {}, eps: { s1: "e1" } } }), "u").seeded, false);
  assert.equal(parseStore(JSON.stringify({ ...good, snap: { titles: { a: { r: 7 } }, eps: {} } }), "u").seeded, false);
  assert.equal(parseStore(JSON.stringify({ ...good, checked: { a: "x" } }), "u").seeded, false);
  assert.equal(parseStore(JSON.stringify({ ...good, snap: { titles: [], eps: {} } }), "u").seeded, false);
  assert.equal(storageKey("u").includes("v2"), true);
});

test("partial lookup that finds nothing keeps the known rank, so no second library event", () => {
  let s = applyObservation(emptyStore("u"), { titles: { "movie:1": title(0) } }, NOW);
  s = applyObservation(s, { titles: { "movie:1": title(2, { libId: "a" }) } }, NOW + 1);
  assert.equal(s.events.length, 1);
  s = applyObservation(s, { titles: { "movie:1": title(0, { partial: true }) } }, NOW + 2);
  assert.equal(s.snap.titles["movie:1"].r, 2);
  s = applyObservation(s, { titles: { "movie:1": title(2, { libId: "a" }) } }, NOW + 3);
  assert.equal(s.events.length, 1);
});

test("a re-import with new ids for the same season+episode does not re-fire", () => {
  let s = applyObservation(emptyStore("u"), { eps: { s1: [ep("e1", 1), ep("e2", 2)] } }, NOW);
  s = applyObservation(s, { eps: { s1: [ep("n1", 1), ep("n2", 2)] } }, NOW + 1);
  assert.equal(s.events.length, 0);
  s = applyObservation(s, { eps: { s1: [ep("n3", 3), ep("n1", 1), ep("n2", 2)] } }, NOW + 2);
  assert.deepEqual(s.events.map((e) => e.id), ["ep:n3"]);
  assert.equal(epKey({ season: 2, episode: 3 }), "#2x3");
  assert.equal(epKey({ season: 2 }), null);
  const d = applyObservation(applyObservation(emptyStore("u"), { eps: { s1: [ep("a", 1)] } }, NOW), { eps: { s1: [ep("x", 2), ep("y", 2), ep("a", 1)] } }, NOW + 1);
  assert.equal(d.events.length, 1);
  assert.equal(d.events[0].kind, "episode");
});

test("per-series snapshot is capped at 120 entries", () => {
  const many = Array.from({ length: 300 }, (_, i) => ({ id: `e${i}`, show: "S", season: 1, episode: i, at: NOW - i }));
  const s = applyObservation(emptyStore("u"), { eps: { s1: many } }, NOW);
  assert.equal(s.snap.eps.s1.length, 120);
});
