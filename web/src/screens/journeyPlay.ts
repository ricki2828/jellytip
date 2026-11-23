// Playing a journey step (the Journeys page, See the path and the Home hero slide). Play is the way in: it opens the player at
// once and, behind it, starts the journey and sends the skip / play events in order (start clears skips, so it goes first).
// Library steps play /play/<itemId>; IPTV steps go the way IPTV details plays a film (#/iptv/play?vod=…), which on the phone
// is the native player. One play at a time: a play holds its gate until the route changes (or 1.5 s), so a double tap sends
// start / skip / play once. An IPTV series step opens its page instead (nothing played yet, so no journey events).
import { go } from "../lib/router";
import { flashNote } from "../components/TasteButtons";
import { NativePlayer, nativeIptvPlayer } from "../lib/native";
import { isPlayable, journeyPath, nowIndex, oneAtATime, skipFor, stepPlayPath, TOAST_ADDED, type JvJourney, type JvStep } from "../lib/journeysV1";
import { fetchJourney, freshJourney, journeyAction, journeyEvent, peekJourney } from "../api/journeysV1";
import { vodItem } from "../api/iptv";

const gate = oneAtATime();
/** Home / hero Play has its own: its (short) fetch must not swallow a step's Play on the path page. */
const tonightGate = oneAtATime();
const TONIGHT_WAIT_MS = 3_000;
const HOLD_MS = 1_500;

/** Resolves when the route changes or after `ms`, whichever is first: how long a play keeps its gate shut. */
const routeChangeOr = (ms: number) =>
  new Promise<void>((done) => {
    const end = () => {
      window.removeEventListener("hashchange", end);
      clearTimeout(t);
      done();
    };
    const t = setTimeout(end, ms);
    window.addEventListener("hashchange", end);
  });

// ---------- IPTV resume ----------
// A journey's IPTV step is often just {id, kind}: no progress. The film's own IPTV answer (as IptvDetails reads it) has it, so
// the pages warm it in the background; a play uses it when it is there and never waits for it.
const iptvResume = new Map<string, number>();
// Dropped on a session change and whenever playback saved progress ("jellytip-watched"): a film watched further must not
// restart from the old point
if (typeof window !== "undefined") for (const ev of ["jellytip-session", "jellytip-watched"]) window.addEventListener(ev, () => iptvResume.clear());
export function warmIptvResume(steps: JvStep[]): void {
  for (const st of steps.filter((x) => x.source === "iptv" && x.iptv?.kind === "movie" && !x.iptv.resume && !iptvResume.has(x.iptv.id)).slice(0, 8)) {
    const id = st.iptv!.id;
    iptvResume.set(id, 0);
    void vodItem(id)
      .then((a) => {
        if (a.status !== 200 || !a.data) return void iptvResume.delete(id); // a failed answer is not "from the start": ask again next time
        const p = a.data.progress;
        iptvResume.set(id, p && !p.played && p.position > 30 ? Math.floor(p.position) : 0);
      })
      .catch(() => iptvResume.delete(id)); // ask again next time
  }
}
const withResume = (st: JvStep): JvStep =>
  st.source === "iptv" && st.iptv && !st.iptv.resume && iptvResume.get(st.iptv.id) ? { ...st, iptv: { ...st.iptv, resume: iptvResume.get(st.iptv.id)! } } : st;

/** The toast shows over the player: the web player's own note, or the phone's native IPTV player (it covers the page). */
function toastOverPlayer(text: string, nativeIptv: boolean) {
  if (nativeIptv) {
    const tryNotice = (left: number) =>
      void NativePlayer.notice({ text })
        .then((r) => !r.shown && left > 0 && window.setTimeout(() => tryNotice(left - 1), 1500))
        .catch(() => undefined);
    window.setTimeout(() => tryNotice(2), 1200);
  } else window.setTimeout(() => flashNote(text), 600); // after the player has mounted its note
}

/** Opens the player for step `index`, then (not blocking) start + skip + play. False when the step can't play. */
function playNow(j: JvJourney, index: number): boolean {
  const st = j.steps[index] ? withResume(j.steps[index]) : undefined;
  const path = st && isPlayable(st.source) ? stepPlayPath(st) : null;
  if (!st || !path) return false;
  if (st.source === "iptv" && st.iptv?.kind === "series") {
    go(path); // its page: nothing is playing yet, so no start / play
    return true;
  }
  const id = j.summary.id;
  const status = j.summary.status;
  const starts = status !== "active" && status !== "done"; // never a start on a finished journey
  const skip = skipFor(j.steps, index);
  go(path);
  if (starts) toastOverPlayer(TOAST_ADDED, nativeIptvPlayer && st.source === "iptv" && st.iptv?.kind !== "series");
  void (async () => {
    if (starts) await journeyAction(id, "start", status);
    if (skip?.tmdbId) await journeyEvent(id, "skip", skip.tmdbId);
    await journeyEvent(id, "play", st.tmdbId);
  })().catch(() => undefined);
  return true;
}

/** Plays step `index` of a loaded journey. False when it can't play or another play is under way. */
export function playStep(j: JvJourney, index: number): boolean {
  const st = j.steps[index];
  if (!st || !isPlayable(st.source) || !stepPlayPath(st)) return false;
  return gate.run(() => {
    if (playNow(j, index)) return routeChangeOr(HOLD_MS);
  });
}

/**
 * Home / the list hero: tonight's step of a journey. A fresh memo plays at once; otherwise a short fetch (3 s) for where it has
 * moved to, falling back to what the memo had; with neither, a note says so (the path page is one tap away). Own gate.
 */
export function playTonight(id: string): void {
  tonightGate.run(async () => {
    let j = freshJourney(id);
    if (!j) {
      const from = location.hash;
      const r = await fetchJourney(id, TONIGHT_WAIT_MS);
      if (location.hash !== from) return; // they went somewhere else meanwhile: a late answer must not pull them into a film
      if (r.kind === "missing") return go(journeyPath(id));
      j = r.kind === "ok" ? r.value : peekJourney(id)?.value ?? null;
    }
    if (!j) return flashNote("Couldn't load tonight's film. Check the connection and try again.");
    const now = nowIndex(j.steps);
    if (now == null) return go(journeyPath(id));
    const found = j;
    // the step gate too, so a Play tap on the path page right after this one can't send a second start
    gate.run(() => (playNow(found, now) ? routeChangeOr(HOLD_MS) : go(journeyPath(id))));
  });
}
