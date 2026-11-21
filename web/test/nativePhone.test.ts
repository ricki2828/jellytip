import { test } from "node:test";
import assert from "node:assert/strict";
import { fullTimeNotice, kickoffNotice, nativeRetryDelayMs, NATIVE_RETRY_MS, shareBase } from "../src/lib/nativePhone.ts";
import type { FxChannel, FxItem } from "../src/lib/gameCentre.ts";

const NOW = 1_800_000_000;
const ch = (id: string, name: string): FxChannel => ({ id, name, num: null, logo: null, quality: null, confidence: 1, why: null, region: null, lang: null, maybe: false, dormant: false, start: null, stop: null, startOver: false, autoRecord: null });
const fx = (o: Partial<FxItem> = {}): FxItem => ({
  fid: "f", league: "EPL", leagueKey: "eng.1", sport: "soccer", home: "Arsenal", away: "Spurs", homeId: null, awayId: null, homeShort: null, awayShort: null, homeLogo: null, awayLogo: null,
  kickoff: NOW, status: "in", stage: null, score: null, interest: 0, significance: 0, rank: null, favourite: true, autoRecord: false, channels: [ch("c1", "Sky Sport 1")], best: null,
  reminderId: null, recordingId: null, matchId: "m", ...o,
});

test("native retry: 5xx (the 19-Nov 502s) and lost connections, three times with growing waits", () => {
  assert.deepEqual([0, 1, 2, 3].map((a) => nativeRetryDelayMs({ httpStatus: 502, errorCode: "ERROR_CODE_IO_BAD_HTTP_STATUS" }, a)), [...NATIVE_RETRY_MS, null]);
  assert.equal(nativeRetryDelayMs({ httpStatus: 503 }, 0), 2_000);
  assert.equal(nativeRetryDelayMs({ httpStatus: 0, errorCode: "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" }, 1), 5_000);
});

test("native retry: never for refusals, decode faults or unknown errors", () => {
  for (const r of [{ httpStatus: 401 }, { httpStatus: 403 }, { httpStatus: 404 }, { httpStatus: 410 }, { httpStatus: 429 }, { httpStatus: 502, iptvError: "provider_auth" }])
    assert.equal(nativeRetryDelayMs(r, 0), null, JSON.stringify(r));
  assert.equal(nativeRetryDelayMs({ errorCode: "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED" }, 0), null);
  assert.equal(nativeRetryDelayMs({}, 0), null);
  assert.equal(nativeRetryDelayMs({ httpStatus: 502 }, -1), null);
});

test("kick-off note: headline, teams and the channel", () => {
  assert.equal(kickoffNotice({ fixture: fx(), phase: "live" }, NOW + 30), "Kick-off now: Arsenal v Spurs · Sky Sport 1");
  assert.equal(kickoffNotice({ fixture: fx({ status: "pre", kickoff: NOW + 150, channels: [] }), phase: "soon" }, NOW), "Kick-off in 3 min: Arsenal v Spurs");
});

test("full-time note: the score only with scores on; what's next when there is one", () => {
  const done = fx({ status: "post", score: { home: 2, away: 1, clock: null, period: null } });
  const next = fx({ fid: "n", home: "Leeds", away: "Hull", status: "pre", kickoff: NOW + 1200 });
  assert.equal(fullTimeNotice(done, next, NOW, false), "Full time. Next: Leeds v Hull, starts in 20 min");
  assert.equal(fullTimeNotice(done, null, NOW, true), "Full time · Arsenal 2–1 Spurs.");
  assert.equal(fullTimeNotice({ ...done, score: null }, null, NOW, true), "Full time.");
  assert.equal(fullTimeNotice(done, fx({ fid: "l", home: "Leeds", away: "Hull" }), NOW, false), "Full time. Next: Leeds v Hull, live now");
});

test("movie night share address: the public web app in the phone app, the page itself on the web", () => {
  assert.equal(shareBase(true, "https://ricki.example", "https://localhost/#/movienight"), "https://ricki.example/jellytip/");
  assert.equal(shareBase(true, "https://ricki.example/", "https://localhost/"), "https://ricki.example/jellytip/");
  assert.equal(shareBase(false, "", "https://ricki.example/jellytip/#/movienight"), "https://ricki.example/jellytip/#/movienight");
  assert.equal(shareBase(true, "", "https://localhost/"), "https://localhost/");
});
