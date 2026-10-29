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

const DEBOUNCE_MS = 250;
const TRAILER_DELAY_MS = 2500;
const RANK_WAIT_MS = 1200; // the companion usually answers well inside this; a premature guess costs a second full-size image
const trailerCache = new Map<string, string | null>();

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
    backdropInfo(heroItemId(shown)).then(() => {
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
      setSound(true);
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
        <ScoreLine extras={ext} />
        <div className="buttons">
          <button className="btn primary" data-nav onClick={() => go(`/play/${shown.Id}`)}>
            {resume ? "Resume" : "Play"}
          </button>
          <button className="btn" data-nav onClick={() => go(`/item/${shown.Id}`)}>
            Details
          </button>
          {trailer && (
            <button className="btn" data-nav onClick={() => setSound((s) => !s)}>
              {sound ? "Mute" : "Sound on"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
