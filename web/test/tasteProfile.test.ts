import { test } from "node:test";
import assert from "node:assert/strict";
import { affinity, parseProfile, topGenres } from "../src/lib/tasteProfile.ts";

const raw = { genres: { Thriller: 0.9, Comedy: 0.2, Horror: -0.8 }, people: {}, decades: { "1990": 0.7 }, keywords: {}, media: { movie: 0.6, series: 0.1 }, updatedAt: "2026-11-07" };

test("parseProfile: weights lowercased and clamped; junk and empty → null", () => {
  const p = parseProfile({ ...raw, genres: { Thriller: 5, Bad: "x" } })!;
  assert.deepEqual(p.genres, { thriller: 1 });
  assert.equal(parseProfile(null), null);
  assert.equal(parseProfile([]), null);
  assert.equal(parseProfile({ genres: {}, media: {} }), null);
});

test("topGenres: strongest first, positive only", () => {
  assert.deepEqual(topGenres(parseProfile(raw)!), ["thriller", "comedy"]);
});

test("affinity: liked genre high, disliked low, unknown neutral", () => {
  const p = parseProfile(raw)!;
  const a = (genres: string[], year?: number, kind: "movie" | "series" = "movie") => affinity(p, { genres, year, kind });
  assert.ok(a(["Thriller"], 1995) > 0.7);
  assert.ok(a(["Horror"]) < 0.4);
  assert.ok(Math.abs(a(["Western"], undefined, "series") - 0.5) < 0.05);
  assert.ok(a(["Thriller"]) > a(["Comedy"]));
});
