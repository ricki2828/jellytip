import { readFileSync } from "node:fs";
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { rolldown } from "rolldown";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { parseFantasy } from "../src/lib/fantasy.ts";
import { parseHash } from "../src/lib/routeParse.ts";
// Synthetic contract fixture; no real identities or credentials.
const fixture = {
  providers: { sleeper: { linked: true }, yahoo: { available: true, linked: true, status: "approval_required" } },
  week: 9, leagues: ["sleeper", "yahoo"].map(source => ({
    id: `${source}-example`, source, name: `${source} league`,
    me: { team: "Home", points: 42 }, opp: { team: "Away", points: 31 },
    starters: ["me", "opp"].flatMap(side => Array.from({ length: 11 }, (_, i) => ({
      side, name: `${source}-${side}-${i}`, pos: "WR", nflTeam: "SEA", points: i,
      matchId: i === 0 ? null : "example-game", gameState: [null, "pre", "in", "post"][i % 4],
    }))),
  })), events: [], updatedAt: 1,
};
test("provider-only blocked Yahoo remains observable and sanitized", () => {
  const live = parseFantasy({ providers: fixture.providers });
  assert.equal(live?.providers.yahoo.status, "approval_required");
  assert.deepEqual(live?.leagues, []);
  assert.equal(parseFantasy({ providers: { yahoo: { status: "secret body" } } })?.providers.yahoo.status, undefined);
});
test("both sources preserve every starter; standalone route works", () => {
  const live = parseFantasy(fixture)!;
  assert.deepEqual(live.leagues.map(l => l.source), ["sleeper", "yahoo"]);
  for (const league of live.leagues) {
    assert.equal(league.starters.length, 22);
    assert.equal(league.starters.filter(s => s.side === "opp").length, 11);
  }
  assert.equal(parseHash("#/fantasy").name, "fantasy");
});
test("render includes all 44 starter rows, sources, sides, scores and game states", async () => {
  const bundle = await rolldown({ input: new URL("../src/components/FantasyMatchups.tsx", import.meta.url).pathname, external: ["react", "react/jsx-runtime"], transform: { jsx: { runtime: "automatic" } } });
  const result = await bundle.generate({ format: "cjs" });
  await bundle.close();
  const js = result.output[0].code;
  const module = { exports: {} as Record<string, any> };
  new Function("require", "module", "exports", js)(createRequire(import.meta.url), module, module.exports);
  const html = renderToStaticMarkup(createElement(module.exports.FantasyMatchups, { live: parseFantasy(fixture)! }));
  const serverRaw = JSON.parse(readFileSync(new URL("./fixtures/server-fantasy.json", import.meta.url), "utf8"));
  const serverHtml = renderToStaticMarkup(createElement(module.exports.FantasyMatchups, { live: parseFantasy(serverRaw)! }));
  assert.equal((serverHtml.match(/<li>/g) ?? []).length, 6);
  for (const league of serverRaw.leagues) for (const player of league.starters) assert.ok(serverHtml.includes(player.name));
  assert.equal((html.match(/<li>/g) ?? []).length, 44);
  for (const league of fixture.leagues) for (const player of league.starters) assert.ok(html.includes(player.name));
  for (const label of ["Yahoo", "Sleeper", "Your team", "Opponent", "42.0", "31.0", "Game status unavailable", "Pregame", "Live", "Final"]) assert.ok(html.includes(label), label);
});

test("all defined Yahoo statuses are preserved without upstream details", () => {
  for (const status of ["ready", "approval_required", "reconnect", "unavailable", "unconfigured"]) {
    const live = parseFantasy({ providers: { yahoo: { status, token: "sentinel", body: "upstream detail" } }, leagues: [] })!;
    assert.equal(live.providers.yahoo.status, status);
    assert.ok(!JSON.stringify(live).includes("sentinel"));
    assert.ok(!JSON.stringify(live).includes("upstream detail"));
  }
});
test("legacy leagues retain starters without inventing a provider or game mapping", () => {
  const league = { ...fixture.leagues[0], source: undefined };
  const live = parseFantasy({ leagues: [league] })!;
  assert.equal(live.leagues[0].source, "unknown");
  assert.equal(live.leagues[0].starters.length, 22);
  assert.equal(live.leagues[0].starters[0].matchId, null);
  assert.equal(live.leagues[0].starters[0].gameState, null);
});

test("actual staged server output parses both sources and every supplied starter", () => {
  const raw = JSON.parse(readFileSync(new URL("./fixtures/server-fantasy.json", import.meta.url), "utf8"));
  const parsed = parseFantasy(raw)!;
  assert.equal(parsed.providers.yahoo.status, "ready");
  assert.equal(parsed.updatedAt, Date.parse(raw.updatedAt) / 1000);
  assert.deepEqual(parsed.leagues.map(l => l.source), ["sleeper", "yahoo"]);
  for (const [index, league] of parsed.leagues.entries()) {
    assert.deepEqual(league.starters, raw.leagues[index].starters);
    assert.equal(league.me.points, raw.leagues[index].me.points);
    assert.equal(league.opp.points, raw.leagues[index].opp.points);
  }
});

test("server ISO event timestamps survive parsing for scoring alerts", () => {
  const at = "2026-11-01T18:00:00Z";
  const parsed = parseFantasy({ providers: fixture.providers, leagues: [], updatedAt: at,
    events: [{ at, league: "example", name: "Player", delta: 6, side: "me" }] })!;
  assert.equal(parsed.updatedAt, Date.parse(at) / 1000);
  assert.equal(parsed.events[0].at, Date.parse(at) / 1000);
});
