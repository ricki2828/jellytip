// Home hero: follows the focused/hovered tile (debounced), shows the companion's hero backdrop (≠ tile art),
// then a muted local trailer after a short rest. Click / Enter on the hero turns the sound on.
import { useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { localTrailers } from "../api/jellyfin";
import { backdropInfo, extras, type Extras } from "../api/companion";
import { trailerStreamUrl } from "../api/playback";
import { heroItemId, heroPick, type ImagePick } from "../lib/art";
import { go } from "../lib/router";
import { Backdrop } from "./Backdrop";
import { ScoreLine } from "./ScoreLine";
import { infoLine, TitleLogo } from "./TitleBlock";
import { HeroQuote } from "./Reviews";

const DEBOUNCE_MS = 250;
const TRAILER_DELAY_MS = 2500;
const RANK_WAIT_MS = 1200; // the companion usually answers well inside this; a premature guess costs a second full-size image
const trailerCache = new Map<string, string | null>();

// "Back" while the hero trailer has sound = mute, as on the TV. Turning sound on pushes a same-URL history entry, so
// phone Back / the mouse back button pop it (popstate) instead of leaving the page. If the user navigates away with
// sound still on, that entry is left behind; landing on it later is skipped so Back never looks dead.
const SOUND_STATE = "jellytipHeroSound";
let soundEntryOwner: (() => void) | null = null; // the mounted hero's mute(fromPop) while its entry is on top
window.addEventListener("popstate", (e) => {
  const owner = soundEntryOwner;
  if (owner) {
    soundEntryOwner = null;
    owner();
  } else if ((e.state as Record<string, unknown> | null)?.[SOUND_STATE]) {
    history.back();
  }
});
window.addEventListener("jellytip-session", () => trailerCache.clear()); // per user (and token in the URL)

async function trailerFor(item: Item): Promise<string | null> {
  const target = item.Type === "Episode" || item.Type === "Season" ? item.SeriesId : item.Id;
  if (!target || !["Movie", "Series", "Episode", "Season"].includes(item.Type)) return null;
  if (trailerCache.has(target)) return trailerCache.get(target)!;
  const t = (await localTrailers(target))[0];
  const url = t ? trailerStreamUrl(t.Id) : null;
  trailerCache.set(target, url);
  return url;
}

export function Hero({ item }: { item: Item | null }) {
  const [shown, setShown] = useState<Item | null>(null);
  const [pick, setPick] = useState<ImagePick>({ url: null, focal: null });
  const [ext, setExt] = useState<Extras | null>(null);
  const [trailer, setTrailer] = useState<string | null>(null);
  const [sound, setSound] = useState(false);
  const [hint, setHint] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  const soundOn = () => {
    setSound(true);
    if (!soundEntryOwner) {
      history.pushState({ ...(history.state ?? {}), [SOUND_STATE]: true }, "");
      soundEntryOwner = () => setSound(false); // popped by Back: just mute
    }
  };
  const mute = () => {
    setSound(false);
    if (soundEntryOwner) {
      soundEntryOwner = null;
      history.back(); // drop our entry; the popstate finds no owner and a plain state, so nothing else happens
    }
  };

  // Esc / Backspace while sound is on: mute and swallow the key (document runs before the app's window-level Esc)
  useEffect(() => {
    if (!sound) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" && e.key !== "Backspace") return;
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
      e.preventDefault();
      e.stopPropagation();
      mute();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sound]);

  // Leaving home with sound on: the pushed entry stays behind (skipped on the way back), the owner goes
  useEffect(
    () => () => {
      soundEntryOwner = null;
    },
    [],
  );

  useEffect(() => {
    if (!item) return;
    const t = setTimeout(() => setShown(item), shown ? DEBOUNCE_MS : 0);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item?.Id]);

  useEffect(() => {
    if (!shown) return;
    let alive = true;
    setTrailer(null);
    setExt(null);
    // Wait briefly for the companion's ranking; past that show a non-tile backdrop and upgrade when it arrives
    const quick = setTimeout(() => alive && setPick(heroPick(shown, false)), RANK_WAIT_MS);
    backdropInfo(heroItemId(shown), { priority: "high" }).then(() => {
      clearTimeout(quick);
      if (alive) setPick(heroPick(shown, false));
    });
    const kind = shown.Type === "Movie" ? "movie" : shown.Type === "Series" ? "series" : null;
    if (kind) extras(shown.Id, kind).then((e) => alive && setExt(e));
    const t = setTimeout(() => {
      trailerFor(shown).then((u) => alive && setTrailer(u));
    }, TRAILER_DELAY_MS);
    return () => {
      alive = false;
      clearTimeout(t);
      clearTimeout(quick);
    };
  }, [shown]);

  useEffect(() => {
    if (videoRef.current) videoRef.current.muted = !sound;
  }, [sound, trailer]);

  if (!shown) return <div className="hero" />;

  const onHeroClick = () => {
    if (trailer && !sound) {
      soundOn();
      setHint(true);
      setTimeout(() => setHint(false), 2000);
    } else go(`/item/${shown.Id}`);
  };
  const episodeLine = shown.Type === "Episode" ? `S${shown.ParentIndexNumber} E${shown.IndexNumber} · ${shown.Name}` : null;
  const resume = (shown.UserData?.PlaybackPositionTicks ?? 0) > 0;

  return (
    <div className="hero">
      <Backdrop url={pick.url} focal={pick.focal} kenBurns={!trailer} />
      {trailer && (
        <video
          ref={videoRef}
          key={trailer}
          className="hero-trailer"
          src={trailer}
          autoPlay
          muted={!sound}
          playsInline
          onEnded={() => setTrailer(null)}
          onError={() => setTrailer(null)}
        />
      )}
      <div className="hero-click" onClick={onHeroClick} aria-hidden />
      <div className="scrim-left" />
      <div className="scrim-bottom" />
      {hint && <div className="hero-hint">Sound on · click again for details</div>}
      <div className="hero-text">
        <TitleLogo item={shown} />
        {episodeLine && <div className="line strong">{episodeLine}</div>}
        <div className="line">{infoLine(shown)}</div>
        {shown.Overview && <p className="overview two">{shown.Overview}</p>}
        <HeroQuote item={shown} />
        <ScoreLine extras={ext} />
        <div className="buttons">
          <button className="btn primary" data-nav onClick={() => go(`/play/${shown.Id}`)}>
            {resume ? "Resume" : "Play"}
          </button>
          <button className="btn" data-nav onClick={() => go(`/item/${shown.Id}`)}>
            Details
          </button>
          {trailer && (
            <button className="btn" data-nav onClick={() => (sound ? mute() : soundOn())}>
              {sound ? "Mute" : "Sound on"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
