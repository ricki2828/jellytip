// Home hero: follows the focused/hovered tile (debounced), shows the companion's hero backdrop (≠ tile art),
// then a muted local trailer after a short rest. Click / Enter on the hero turns the sound on.
import { useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { backdropInfo, extras, type Extras } from "../api/companion";
import { heroItemId, heroPick, type ImagePick } from "../lib/art";
import { go } from "../lib/router";
import { vodPath } from "../lib/iptvVod";
import { useVideoBlocked } from "../lib/videoFocus";
import { useSoundBack } from "../lib/soundBack";
import { jfTarget, type PreviewTarget } from "../lib/trailerPreview";
import { usePreviewLifecycle, useTrailerPreview } from "../lib/useTrailerPreview";
import { Backdrop } from "./Backdrop";
import { ScoreLine } from "./ScoreLine";
import { infoLine, TitleLogo } from "./TitleBlock";
import { HeroQuote } from "./Reviews";

const DEBOUNCE_MS = 250;
const RANK_WAIT_MS = 1200; // the companion usually answers well inside this; a premature guess costs a second full-size image

/**
 * The muted preview video's state: sound toggle (Back mutes), the "other video took over" pause, a hidden tab pause,
 * and the single-preview slot. `url` null = nothing playing.
 */
function useHeroVideo(id: string, url: string | null) {
  const [stopped, setStopped] = useState<string | null>(null); // url a newer preview (or the clip ending) switched off
  const [sound, setSound] = useState(false);
  const [hint, setHint] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (!url) setStopped(null); // the next preview of the same title may play again
  }, [url]);
  const live = url && stopped !== url ? url : null;
  const { hidden } = usePreviewLifecycle(id, live, () => setStopped(url));
  // "Back" while the trailer has sound = mute, as on the TV (shared with the specials' inline videos)
  const back = useSoundBack(id, sound, () => setSound(false));
  const soundOn = () => {
    setSound(true);
    back.soundOn();
  };
  const mute = back.mute;
  useEffect(() => {
    if (videoRef.current) videoRef.current.muted = !sound;
  }, [sound, live]);
  // Another video on the page (trailer break, closet clip) took over: pause, resume when it lets go
  const blocked = useVideoBlocked(id);
  useEffect(() => {
    const el = videoRef.current;
    if (!el) return;
    if (blocked || hidden) {
      el.pause();
      if (sound && blocked) mute(); // its sound must not come back when the hero resumes
    } else el.play().catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocked, hidden, live]);
  const showHint = () => {
    setHint(true);
    setTimeout(() => setHint(false), 2000);
  };
  return { videoRef, live, sound, soundOn, mute, hint, showHint, stop: () => setStopped(url) };
}

function HeroVideo({ v }: { v: ReturnType<typeof useHeroVideo> }) {
  if (!v.live) return null;
  return <video ref={v.videoRef} key={v.live} className="hero-trailer" src={v.live} autoPlay muted={!v.sound} playsInline onEnded={v.stop} onError={v.stop} />;
}

/** An IPTV title the person is on in a row (tile focus / hover / tap). */
export interface IptvHeroTile {
  id: string;
  title: string;
  year?: number | null;
  art: string | null;
}

/** Hero for an IPTV title: its art, then the companion's cached trailer (if any) after a short rest. */
export function IptvHero({ tile }: { tile: IptvHeroTile }) {
  const target: PreviewTarget = { kind: "iptv", id: tile.id };
  const url = useTrailerPreview(target, { trigger: "focus" });
  const v = useHeroVideo("hero", url);
  const onClick = () => {
    if (v.live && !v.sound) {
      v.soundOn();
      v.showHint();
    } else go(vodPath(tile.id));
  };
  return (
    <div className="hero">
      <Backdrop url={tile.art} focal={null} kenBurns={!v.live} />
      <HeroVideo v={v} />
      <div className="hero-click" onClick={onClick} aria-hidden />
      <div className="scrim-left" />
      <div className="scrim-bottom" />
      {v.hint && <div className="hero-hint">Sound on · click again for details</div>}
      <div className="hero-text">
        <div className="hero-kicker">via IPTV</div>
        <div className="line strong">{tile.title}</div>
        {tile.year ? <div className="line">{tile.year}</div> : null}
        <div className="buttons">
          <button className="btn primary" data-nav onClick={() => go(vodPath(tile.id))}>
            Details
          </button>
          {v.live && (
            <button className="btn" data-nav onClick={() => (v.sound ? v.mute() : v.soundOn())}>
              {v.sound ? "Mute" : "Sound on"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/** `kicker`: where a featured-mix slide came from; `rotating`: the mix is on, so no trailers (slides last a few seconds). */
export function Hero({ item, kicker, rotating = false }: { item: Item | null; kicker?: string; rotating?: boolean }) {
  const [shown, setShown] = useState<Item | null>(null);
  // The kicker changes with the slide that is shown, not the one that is coming (the title is debounced)
  const [shownKicker, setShownKicker] = useState<string | undefined>(undefined);
  const [pick, setPick] = useState<ImagePick>({ url: null, focal: null });
  const [ext, setExt] = useState<Extras | null>(null);
  // Prefs, the rotating mix, touch, reduced motion, Save-Data and the 2.5 s rest are all decided in the hook
  const url = useTrailerPreview(shown ? jfTarget(shown) : null, { trigger: "focus", rotating });
  const v = useHeroVideo("hero", url);
  const trailer = v.live;
  const { sound, soundOn, mute } = v;

  useEffect(() => {
    if (!item) return;
    const t = setTimeout(() => {
      setShown(item);
      setShownKicker(kicker);
    }, shown ? DEBOUNCE_MS : 0);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item?.Id]);

  useEffect(() => {
    if (!shown) return;
    let alive = true;
    setExt(null);
    // Wait briefly for the companion's ranking; past that show a non-tile backdrop and upgrade when it arrives
    const quick = setTimeout(() => alive && setPick(heroPick(shown, false)), RANK_WAIT_MS);
    backdropInfo(heroItemId(shown), { priority: "high" }).then(() => {
      clearTimeout(quick);
      if (alive) setPick(heroPick(shown, false));
    });
    const kind = shown.Type === "Movie" ? "movie" : shown.Type === "Series" ? "series" : null;
    if (kind) extras(shown.Id, kind).then((e) => alive && setExt(e));
    return () => {
      alive = false;
      clearTimeout(quick);
    };
  }, [shown]);

  if (!shown) return <div className="hero" />;

  const onHeroClick = () => {
    if (trailer && !sound) {
      soundOn();
      v.showHint();
    } else go(`/item/${shown.Id}`);
  };
  const episodeLine = shown.Type === "Episode" ? `S${shown.ParentIndexNumber} E${shown.IndexNumber} · ${shown.Name}` : null;
  const resume = (shown.UserData?.PlaybackPositionTicks ?? 0) > 0;

  return (
    <div className="hero">
      <Backdrop url={pick.url} focal={pick.focal} kenBurns={!trailer} />
      <HeroVideo v={v} />
      <div className="hero-click" onClick={onHeroClick} aria-hidden />
      <div className="scrim-left" />
      <div className="scrim-bottom" />
      {v.hint && <div className="hero-hint">Sound on · click again for details</div>}
      <div className="hero-text">
        {shownKicker && <div className="hero-kicker">{shownKicker}</div>}
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
