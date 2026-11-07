import { test } from "node:test";
import assert from "node:assert/strict";
import {
  titlesAgree,
  airsLabel,
  episodeStatus,
  mergeEpisodes,
  mergeSeasons,
  iptvSeasonRows,
  ownedVod,
  pickIptvSeries,
  presentEpisodes,
  reasonDetail,
  reasonLabel,
} from "../src/lib/seasonEpisodes.ts";
import type { Item } from "../src/api/jellyfin.ts";
import type { VodEpisode, VodItem } from "../src/lib/iptvVod.ts";

const now = new Date("2026-11-06T00:00:00Z");
const ep = (n: number, extra: Partial<Item> = {}): Item => ({
  Id: `e${n}`,
  Name: `Ep ${n}`,
  Type: "Episode",
  IndexNumber: n,
  ...extra,
});
const ve = (
  n: number,
  id = `3:e:${n}`,
  title: string | null = `IPTV ${n}`,
): VodEpisode => ({ id, episodeId: String(n), episode: n, title });

test("status: virtual + future premiere = unaired, virtual otherwise = missing, file = present", () => {
  assert.equal(episodeStatus(ep(1), now), "present");
  assert.equal(
    episodeStatus(
      ep(2, { LocationType: "Virtual", PremiereDate: "2026-12-12T00:00:00Z" }),
      now,
    ),
    "unaired",
  );
  assert.equal(
    episodeStatus(
      ep(3, { LocationType: "Virtual", PremiereDate: "2020-01-01T00:00:00Z" }),
      now,
    ),
    "missing",
  );
  assert.equal(
    episodeStatus(ep(4, { LocationType: "Virtual" }), now),
    "missing",
  );
  assert.equal(
    episodeStatus(
      ep(5, {
        LocationType: "FileSystem",
        PremiereDate: "2030-01-01T00:00:00Z",
      }),
      now,
    ),
    "present",
  );
});

test("merge: present, missing, unaired in order; presentEpisodes drops placeholders", () => {
  const jf = [
    ep(1),
    ep(2, { LocationType: "Virtual", PremiereDate: "2020-01-01T00:00:00Z" }),
    ep(3, { LocationType: "Virtual", PremiereDate: "2026-12-12T00:00:00Z" }),
  ];
  const rows = mergeEpisodes(jf, null, now);
  assert.deepEqual(
    rows.map((r) => [r.index, r.status]),
    [
      [1, "present"],
      [2, "missing"],
      [3, "unaired"],
    ],
  );
  assert.equal(rows[2].airDate, "2026-12-12T00:00:00Z");
  assert.deepEqual(
    presentEpisodes(jf).map((e) => e.Id),
    ["e1"],
  );
});

test("merge: duplicate numbers with files are all kept; a placeholder shadowed by a file is dropped", () => {
  const rows = mergeEpisodes(
    [
      ep(1),
      ep(1, { Id: "e1b" }),
      ep(1, { Id: "ph", LocationType: "Virtual" }),
      ep(2, { Id: "x", LocationType: "Virtual" }),
      ep(2, { Id: "y", LocationType: "Virtual" }),
    ],
    null,
    now,
  );
  assert.deepEqual(
    rows.map((r) => r.key),
    ["e1", "e1b", "x"],
  );
});

test("merge: IPTV attaches only to a missing placeholder, never creates a playable row or touches present/unaired", () => {
  const jf = [
    ep(1),
    ep(2, { LocationType: "Virtual", Name: "Pilot Two" }),
    ep(4, { LocationType: "Virtual", PremiereDate: "2026-12-12T00:00:00Z" }),
  ];
  const rows = mergeEpisodes(
    jf,
    [ve(1), ve(2, "3:e:2", "Pilot Two"), ve(3), ve(4)],
    now,
  );
  assert.deepEqual(
    rows.map((r) => [r.index, r.status, r.iptv?.id ?? null]),
    [
      [1, "present", null],
      [2, "missing", "3:e:2"],
      [4, "unaired", null],
    ],
  );
});

test("merge: absolute numbering / offset IPTV episodes do not attach or create rows", () => {
  // Jellyfin S2E1 is missing; the provider numbers it 13 (absolute) and has its own unrelated "episode 1"
  const jf = [ep(1, { LocationType: "Virtual", Name: "The Return" })];
  const rows = mergeEpisodes(
    jf,
    [ve(13, "3:e:13", "The Return"), ve(1, "3:e:1", "Season One Opener")],
    now,
  );
  assert.equal(rows.length, 1);
  assert.equal(rows[0].iptv, undefined);
  assert.deepEqual(mergeEpisodes([], [ve(5)], now), []);
});

test("titles: generic or unknown agree, clear mismatches do not", () => {
  assert.equal(titlesAgree("Episode 5", "Anything"), true);
  assert.equal(titlesAgree("The Return", null), true);
  assert.equal(titlesAgree("The Return", "the return!"), true);
  assert.equal(titlesAgree("The Return", "Another One"), false);
});

test("merge: no IPTV info leaves the Jellyfin list as is", () => {
  assert.deepEqual(
    mergeEpisodes([ep(1)], undefined, now).map((r) => r.status),
    ["present"],
  );
  assert.deepEqual(mergeEpisodes([ep(1)], [], now).length, 1);
});

test("airs label: day month, year only when not this year, null when unusable", () => {
  assert.equal(airsLabel("2026-12-12T00:00:00Z", now), "Airs 12 Dec");
  assert.equal(airsLabel("2027-01-05T00:00:00Z", now), "Airs 5 Jan 2027");
  assert.equal(airsLabel(undefined, now), null);
  assert.equal(airsLabel("junk", now), null);
});

test("reasons come from real data only", () => {
  const [present, missing, unaired] = mergeEpisodes(
    [
      ep(1),
      ep(2, { LocationType: "Virtual" }),
      ep(3, { LocationType: "Virtual", PremiereDate: "2026-12-12T00:00:00Z" }),
    ],
    null,
    now,
  );
  assert.equal(reasonLabel(present, now), null);
  assert.equal(reasonLabel(missing, now), "Not in your library");
  assert.equal(reasonLabel(unaired, now), "Airs 12 Dec");
  assert.equal(reasonDetail(unaired, now), "Not released yet");
  assert.equal(reasonDetail(missing, now), null);
  const [, , undated] = mergeEpisodes(
    [
      ep(1),
      ep(2),
      ep(3, { LocationType: "Virtual", PremiereDate: "2099-01-01T00:00:00Z" }),
    ],
    null,
    now,
  );
  assert.equal(
    reasonLabel({ ...undated, airDate: undefined }, now),
    "Not released yet",
  );
  const viaIptv = mergeEpisodes(
    [ep(2, { LocationType: "Virtual" })],
    [ve(2)],
    now,
  )[0];
  assert.equal(reasonLabel(viaIptv, now), "Available via IPTV");
});

const vod = (
  id: string,
  tmdbId: number,
  kind: "series" | "movie" = "series",
): VodItem => ({
  id,
  source: "iptv",
  kind,
  tmdbId,
  title: "Show",
  quality: [],
  tech: [],
});

test("provider ownership: only owned/shared provider ids survive", () => {
  const items = [vod("3:s:1", 10), vod("9:s:2", 10), vod("11:s:3", 10)];
  assert.deepEqual(
    ownedVod(items, [3, "11"]).map((i) => i.id),
    ["3:s:1", "11:s:3"],
  );
  assert.deepEqual(ownedVod(items, []), []);
});

test("pick series: same TMDB id, series kind, owned provider; otherwise null", () => {
  const items = [
    vod("9:s:2", 10),
    vod("3:m:5", 10, "movie"),
    vod("3:s:1", 11),
    vod("3:s:7", 10),
  ];
  assert.equal(pickIptvSeries(items, "10", [3])?.id, "3:s:7");
  assert.equal(pickIptvSeries(items, 10, [9])?.id, "9:s:2");
  assert.equal(pickIptvSeries(items, "10", [4]), null);
  assert.equal(pickIptvSeries(items, undefined, [3]), null);
  assert.equal(pickIptvSeries(items, "abc", [3]), null);
});

const season = (n: number): Item => ({ Id: `s${n}`, Name: `Season ${n}`, IndexNumber: n, Type: "Season" }) as Item;
const vodEps = (n: number): VodEpisode[] =>
  Array.from({ length: 8 }, (_, i) => ({ id: `p:e:${n}${i + 1}`, episodeId: `${n}${i + 1}`, episode: i + 1, title: `T${i + 1}` }));

test("mergeSeasons: library {1,3} + IPTV {1,2,3} gives 1, 2 (IPTV), 3", () => {
  const out = mergeSeasons([season(1), season(3)], [
    { season: 1, episodes: vodEps(1) },
    { season: 2, episodes: vodEps(2) },
    { season: 3, episodes: vodEps(3) },
  ]);
  assert.deepEqual(out.map((s) => s.number), [1, 2, 3]);
  assert.deepEqual(out.map((s) => !!s.iptvOnly), [false, true, false]);
  assert.equal(out[1]!.name, "Season 2 · via IPTV");
  assert.equal(out[1]!.key, "iptv-season:2");
});

test("mergeSeasons: no IPTV, empty/special IPTV seasons add nothing; specials stay first", () => {
  const sp = { ...season(0), Name: "Specials" };
  assert.equal(mergeSeasons([season(1)], null).length, 1);
  assert.equal(mergeSeasons([season(1)], [{ season: 0, episodes: vodEps(0) }, { season: 2, episodes: [] }]).length, 1);
  assert.deepEqual(mergeSeasons([season(1), sp], []).map((s) => s.number), [0, 1]);
});

test("iptvSeasonRows: sorted, playable via IPTV, language flag", () => {
  const rows = iptvSeasonRows([...vodEps(2)].reverse(), true);
  assert.deepEqual(rows.map((r) => r.index), [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.equal(reasonLabel(rows[0]!, now), "Available via IPTV");
  assert.equal(rows[0]!.iptv!.id, "p:e:21");
  assert.equal(rows[0]!.langUnknown, true);
});
