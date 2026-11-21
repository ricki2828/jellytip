import { test } from "node:test";
import assert from "node:assert/strict";
import { KICKOFF_SOON_SEC, kickoffDue, kickoffHeadline, kickoffKey, nextGame, nextLine } from "../src/lib/sportWow.ts";
import { pickBest, pickedLabel, releaseMatch, type RemoteSubtitle } from "../src/lib/remoteSubtitles.ts";
import type { FxChannel, FxItem } from "../src/lib/gameCentre.ts";

const NOW = 1_800_000_000;
const ch = (id: string): FxChannel => ({ id, name: `Ch ${id}`, num: null, logo: null, quality: null, confidence: 1, why: null, region: null, lang: null, maybe: false, dormant: false, start: null, stop: null, startOver: false, autoRecord: null });
const fx = (fid: string, o: Partial<FxItem> = {}): FxItem => ({
  fid, league: "NRL", leagueKey: "nrl", sport: "rugby-league", home: `H${fid}`, away: `A${fid}`, homeId: null, awayId: null, homeShort: null, awayShort: null, homeLogo: null, awayLogo: null,
  kickoff: NOW + 120, status: "pre", stage: null, score: null, interest: 0, significance: 0, rank: null, favourite: true, autoRecord: false, channels: [ch(`c${fid}`)], best: null,
  reminderId: null, recordingId: null, matchId: `m${fid}`, ...o,
});

test("kick-off: a followed team's game 2.5 min out is 'soon', at kick-off 'live'; once each, never both", () => {
  const seen = new Set<string>();
  const d = kickoffDue([fx("a")], NOW, seen)!;
  assert.equal(d.phase, "soon");
  assert.equal(kickoffHeadline(d, NOW), "Kick-off in 2 min");
  seen.add(kickoffKey(d));
  assert.equal(kickoffDue([fx("a")], NOW, seen), null);
  const live = kickoffDue([fx("a", { status: "in", kickoff: NOW - 60 })], NOW, seen)!;
  assert.equal(live.phase, "live");
  assert.equal(kickoffHeadline(live, NOW), "Kick-off now");
  // shown live first: no "soon" afterwards
  assert.equal(kickoffDue([fx("b")], NOW, new Set(["b:live"])), null);
  assert.equal(kickoffDue([fx("a", { kickoff: NOW + KICKOFF_SOON_SEC + 1 })], NOW, new Set()), null);
  assert.equal(kickoffDue([fx("a", { status: "in", kickoff: NOW - 301 })], NOW, new Set()), null);
});

test("kick-off: not for other teams, reminders, muted, no channel, or the channel already playing", () => {
  assert.equal(kickoffDue([fx("a", { favourite: false })], NOW, new Set()), null);
  assert.equal(kickoffDue([fx("a", { favourite: false, homeId: "7" })], NOW, new Set(), { refs: [{ teamId: "7" }] })?.phase, "soon");
  assert.equal(kickoffDue([fx("a", { reminderId: 3 })], NOW, new Set()), null);
  assert.equal(kickoffDue([fx("a")], NOW, new Set(), { hasReminder: () => true }), null);
  assert.equal(kickoffDue([fx("a")], NOW, new Set(), { muted: () => true }), null);
  assert.equal(kickoffDue([fx("a", { channels: [] })], NOW, new Set()), null);
  assert.equal(kickoffDue([fx("a")], NOW, new Set(), { playingChannel: "ca" }), null);
  // the earliest kick-off first
  assert.equal(kickoffDue([fx("late", { kickoff: NOW + 100 }), fx("early", { kickoff: NOW + 30 })], NOW, new Set())!.fixture.fid, "early");
});

test("full time: live favourite, then soonest favourite, then live, then soonest; excludes the game just played", () => {
  const live = (fid: string, o: Partial<FxItem> = {}) => fx(fid, { status: "in", kickoff: NOW - 600, favourite: false, ...o });
  const pre = (fid: string, inS: number, o: Partial<FxItem> = {}) => fx(fid, { status: "pre", kickoff: NOW + inS, favourite: false, ...o });
  const ex = new Set(["done", "mdone"]);
  assert.equal(nextGame([live("x"), pre("p", 600, { favourite: true }), live("f", { favourite: true })], ex, NOW)!.fid, "f");
  assert.equal(nextGame([live("x"), pre("p2", 1200, { favourite: true }), pre("p1", 600, { favourite: true })], ex, NOW)!.fid, "p1");
  assert.equal(nextGame([pre("p", 600), live("x"), live("y")], ex, NOW)!.fid, "x");
  assert.equal(nextGame([pre("p2", 1800), pre("p1", 900)], ex, NOW)!.fid, "p1");
  assert.equal(nextGame([pre("far", 2 * 3600), live("nochan", { channels: [] }), live("done"), live("z", { matchId: "mdone" })], ex, NOW), null);
  assert.equal(nextGame([live("x", { league: "AFL" })], ex, NOW, [], (f) => f.league === "AFL"), null);
  assert.equal(nextLine(live("x"), NOW), "Live now");
  assert.equal(nextLine(pre("p", 11 * 60 + 5), NOW), "Starts in 12 min");
});

const sub = (name: string, o: Partial<RemoteSubtitle> = {}): RemoteSubtitle => ({ id: name, name, provider: "p", format: "SRT", author: "", language: "eng", rating: null, downloads: null, perfectMatch: false, hearingImpaired: false, forced: false, aiTranslated: false, machineTranslated: false, ...o });

test("one-press subtitles: hash match, then the same release, then downloads, then rating", () => {
  const file = "/media/Movies/Heat (1995)/Heat.1995.1080p.BluRay.x264-GROUP.mkv";
  assert.equal(releaseMatch(file, "Heat.1995.1080p.BluRay.x264-GROUP"), 2);
  assert.equal(releaseMatch(file, "Heat.1995.1080p.WEB.srt"), 1);
  assert.equal(releaseMatch(file, "Something else"), 0);
  assert.equal(releaseMatch(null, "x"), 0);
  const list = [sub("popular", { downloads: 9000 }), sub("Heat.1995.1080p.BluRay.x264-GROUP", { downloads: 10 }), sub("rated", { rating: 9, downloads: 9000 })];
  assert.equal(pickBest(list, file)!.name, "Heat.1995.1080p.BluRay.x264-GROUP");
  assert.equal(pickBest([...list, sub("hash", { perfectMatch: true })], file)!.name, "hash");
  assert.equal(pickBest(list, null)!.name, "rated");
  assert.equal(pickBest([], file), null);
});

test("one-press subtitles: forced and hearing-impaired only as a last resort", () => {
  assert.equal(pickBest([sub("sdh", { hearingImpaired: true, downloads: 9 }), sub("plain", { downloads: 1 })])!.name, "plain");
  assert.equal(pickBest([sub("forced", { forced: true, downloads: 9 }), sub("sdh", { hearingImpaired: true })])!.name, "sdh");
  assert.equal(pickBest([sub("forced", { forced: true })])!.name, "forced");
  assert.equal(pickedLabel(sub("Heat", { perfectMatch: true, downloads: 1204 })), "Picked Heat (hash match, 1,204 downloads)");
  assert.equal(pickedLabel(sub("Heat")), "Picked Heat");
});
