// Playing a journey step (the Journeys page, See the path and the Home hero slide). Play is the way in: it opens the player at
// once and, behind it, starts the journey and sends the skip / play events in order (start clears skips, so it goes first).
// Library steps play /play/<itemId>; IPTV steps go the way IPTV details plays a film (#/iptv/play?vod=…), which on the phone
// is the native player. One play at a time.
import { go } from "../lib/router";
import { flashNote } from "../components/TasteButtons";
import { NativePlayer, nativeIptvPlayer } from "../lib/native";
import { isPlayable, journeyPath, nowIndex, oneAtATime, skipFor, stepPlayPath, TOAST_ADDED, type JvJourney } from "../lib/journeysV1";
import { fetchJourney, journeyAction, journeyEvent, peekJourney } from "../api/journeysV1";

const gate = oneAtATime();

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
  const st = j.steps[index];
  const path = st && isPlayable(st.source) ? stepPlayPath(st) : null;
  if (!path) return false;
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
  return gate.run(() => playNow(j, index));
}

/**
 * Home / the list hero: tonight's step of a journey, fetched fresh (it may have moved), then played. No step or a failed load
 * opens the path instead. One at a time (a second tap while it loads does nothing).
 */
export function playTonight(id: string): void {
  gate.run(async () => {
    const from = location.hash;
    const r = await fetchJourney(id);
    if (location.hash !== from) return; // they went somewhere else meanwhile: a late answer must not pull them into a film
    const j = r.kind === "ok" ? r.value : peekJourney(id)?.value ?? null;
    const now = j ? nowIndex(j.steps) : null;
    if (!j || now == null || !playNow(j, now)) go(journeyPath(id));
  });
}
