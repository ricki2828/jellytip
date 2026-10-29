import { test } from "node:test";
import assert from "node:assert/strict";
import { parseGenres, parseTitles, withStatuses } from "../src/lib/discover.ts";

test("titles: companion or raw TMDB shapes, junk and duplicates dropped", () => {
  const t = parseTitles(
    {
      items: [
        { tmdbId: 1, mediaType: "tv", title: "A", year: 2023, posterUrl: "https://image.tmdb.org/a.jpg", inLibrary: false, jellyfinId: null, requested: { state: "requested" } },
        { id: 2, media_type: "movie", name: "B", release_date: "1999-05-01", vote_average: 7.5 },
        { id: 2, media_type: "movie", name: "B again" },
        { title: "no id" },
        null,
      ],
    },
    "movie",
  );
  assert.deepEqual(t.map((x) => [x.tmdbId, x.mediaType, x.title, x.year, x.state, x.rating]), [
    [1, "tv", "A", 2023, "requested", null],
    [2, "movie", "B", 1999, null, 7.5],
  ]);
  assert.deepEqual(parseTitles("nope", "movie"), []);
});

test("inLibrary wins and carries the Jellyfin id", () => {
  const [t] = parseTitles([{ tmdbId: 5, mediaType: "movie", title: "M", inLibrary: true, jellyfinId: "abc", requested: { state: "requested" } }], "movie");
  assert.equal(t.state, "in_library");
  assert.equal(t.itemId, "abc");
});

test("statuses merge by media:id", () => {
  const [a] = withStatuses(parseTitles([{ tmdbId: 9, mediaType: "movie", title: "X" }], "movie"), { status: { "movie:9": { state: "downloading", progress: 40 } } });
  assert.equal(a.state, "downloading");
  assert.equal(a.progress, 40);
});

test("genres", () => {
  assert.deepEqual(parseGenres({ genres: [{ id: 18, name: "Drama" }, { id: "x" }] }), [{ id: 18, name: "Drama" }]);
});
