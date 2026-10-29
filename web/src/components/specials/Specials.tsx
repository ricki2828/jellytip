// The "specials": new carousel formats picked per user per day by the companion (GET /v1/home/specials;
// contract in ~/ai/plans/jellytip-specials.md). Each special renders as one block between the normal rows; unknown
// layouts render nothing. Item data comes from one Jellyfin call per special; nothing per tile hits the companion.
import { useEffect, useRef, useState } from "react";
import { getItems, getSession, localTrailers, runtimeText, type Item } from "../../api/jellyfin";
import {
  backdropInfo,
  companionMediaUrl,
  extras,
  type Extras,
  type AroundHouse,
  type BecauseLoved,
  type ClosetPick,
  type ClosetPicks,
  type DoubleFeature,
  type PersonSpotlight,
  type Special,
  type TonightsPick,
  type Top10,
  type TrailerBreak,
} from "../../api/companion";
import { trailerStreamUrl } from "../../api/playback";
import { cardLayers, heroItemId, specialPick, type ImagePick } from "../../lib/art";
import { go } from "../../lib/router";
import { claimVideo, releaseVideo, useVideoBlocked } from "../../lib/videoFocus";
import { useSoundBack } from "../../lib/soundBack";
import { useIsPhone } from "../../lib/device";
import { placeSpecials } from "../../lib/specialsPlacement";
import { Backdrop } from "../Backdrop";
import { Card } from "../Card";
import { ScoreLine } from "../ScoreLine";
import { infoLine, TitleLogo } from "../TitleBlock";
import { PosterCard } from "../PosterCard";
import { GetItSheet } from "../GetItSheet";
import { filmCount, PersonTile } from "../PersonTile";
import "./specials.css";

// ---------- shared ----------

/** The special's items in the order given (one Jellyfin call; ids the caller can't see simply drop out). */
function useItems(ids: string[]): Item[] | null {
  const key = ids.join(",");
  const [items, setItems] = useState<Item[] | null>(null);
  useEffect(() => {
    let alive = true;
    const unique = [...new Set(ids)].filter(Boolean);
    if (!unique.length) {
      setItems([]);
      return;
    }
    getItems({ Ids: unique.join(","), Fields: "Overview,Genres,ProviderIds,PrimaryImageAspectRatio,Taglines" })
      .then((r) => {
        if (!alive) return;
        const byId = new Map(r.Items.map((i) => [i.Id, i]));
        setItems(ids.map((id) => byId.get(id)).filter((x): x is Item => !!x));
      })
      .catch(() => alive && setItems([]));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return items;
}

/** Ids whose art has no title in the picture (from the payload): those tiles draw the title over it. */
const untitledSet = (s: Special) => new Set(s.untitled ?? []);

/**
 * Mostly on screen (`threshold` of it)? Takes the element through a callback ref: the blocks render nothing until their items load, so
 * the element appears after the first effect would have run.
 */
/**
 * Still for a trailer break / tonight's pick (rules in specialPick), chosen once the companion ranking is in so the
 * hero's image never flashes first.
 */
function useSpecialStill(item: Item | undefined): ImagePick {
  const [pick, setPick] = useState<ImagePick>({ url: null, focal: null });
  useEffect(() => {
    if (!item) return;
    let alive = true;
    const set = () => alive && setPick(specialPick(item));
    const t = setTimeout(set, 1500); // companion slow or down: best guess without it
    backdropInfo(heroItemId(item), { priority: "high" }).then(() => {
      clearTimeout(t);
      set();
    });
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [item]);
  return pick;
}

function useInView(threshold: number): [(el: Element | null) => void, boolean] {
  const [el, setEl] = useState<Element | null>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    if (!el) return;
    // Taller than the screen (phone closet: clip above picks) counts once it fills half the screen
    const on = (e: IntersectionObserverEntry) =>
      e.isIntersecting && e.intersectionRect.height >= Math.min(e.boundingClientRect.height * threshold, window.innerHeight * 0.5);
    const io = new IntersectionObserver((es) => setInView(es.some(on)), { threshold: [0, 0.2, 0.4, 0.5, 0.6, 0.8, 1] });
    io.observe(el);
    return () => {
      io.disconnect();
      setInView(false);
    };
  }, [el, threshold]);
  return [setEl, inView];
}

function Stars({ rating }: { rating: number }) {
  const whole = Math.floor(rating);
  return (
    <span className="stars" aria-label={`${rating} out of 5`}>
      {"★".repeat(whole)}
      {rating - whole >= 0.5 ? "½" : ""}
    </span>
  );
}

/** Kicker = a short category label ("TOP 10"); never drawn when it just repeats the title (or how it starts). */
function SpecialHeader({ kicker: k, title }: { kicker?: string; title?: string }) {
  const same = (a?: string, b?: string) => !!a && !!b && b.trim().toLowerCase().startsWith(a.trim().toLowerCase());
  const kicker = same(k, title) ? undefined : k;
  if (!kicker && !title) return null;
  return (
    <h2 className="row-title special-title">
      {kicker && <span className="kicker">{kicker}</span>}
      {title}
    </h2>
  );
}

// ---------- trailer_break ----------

function TrailerBreakBlock({ s }: { s: TrailerBreak }) {
  const item = useItems([s.itemId])?.[0];
  const video = useRef<HTMLVideoElement>(null);
  const [box, inView] = useInView(0.6);
  const [focused, setFocused] = useState(false);
  const [trailer, setTrailer] = useState<string | null>(null);
  const [sound, setSound] = useState(false);
  const blocked = useVideoBlocked(s.id);
  const { soundOn, mute } = useSoundBack(s.id, sound, () => setSound(false));
  const [ext, setExt] = useState<Extras | null>(null);
  // The still stays up until the trailer has a picture (a black panel while it buffers looks broken)
  const [framed, setFramed] = useState(false);

  useEffect(() => {
    if (!item || (item.Type !== "Movie" && item.Type !== "Series")) return;
    let alive = true;
    extras(item.Id, item.Type === "Movie" ? "movie" : "series").then((e) => alive && setExt(e));
    return () => {
      alive = false;
    };
  }, [item]);

  useEffect(() => {
    let alive = true;
    if (s.trailerItemId) setTrailer(trailerStreamUrl(s.trailerItemId));
    else localTrailers(s.itemId).then((t) => alive && setTrailer(t[0] ? trailerStreamUrl(t[0].Id) : null));
    return () => {
      alive = false;
    };
  }, [s.itemId, s.trailerItemId]);

  // Play while on screen (or focused) and nobody else holds the video; the hero pauses meanwhile
  const wantPlay = !!trailer && (inView || focused);
  // Focus (re)claims, so the block being used wins over another one that is merely on screen
  useEffect(() => {
    if (wantPlay) claimVideo(s.id);
    else {
      releaseVideo(s.id);
      if (sound) mute();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wantPlay, focused, s.id]);
  useEffect(() => {
    if (blocked && sound) mute(); // another video took over: its sound must not come back later
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocked]);
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    v.muted = !sound;
    if (wantPlay && !blocked) v.play().catch(() => undefined);
    else v.pause();
  }, [wantPlay, blocked, sound, trailer]);

  const pick = useSpecialStill(item);
  if (!item) return null;
  const playing = wantPlay && !blocked;
  return (
    <section className={`special trailer-break${playing ? " playing" : ""}`} ref={box}>
      <button
        className="tb-hit"
        data-nav
        aria-label={sound ? `Open ${item.Name}` : `Play ${item.Name} trailer with sound`}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onClick={() => {
          // As on the TV: first press = sound, second = details
          if (trailer && !sound) {
            claimVideo(s.id); // even if another clip had taken over
            setSound(true);
            soundOn();
          } else go(`/item/${item.Id}`);
        }}
      />
      <div className="tb-text">
        <span className="kicker">{s.title || "Trailer break"}</span>
        <TitleLogo item={item} />
        {s.reason && <span className="reason">{s.reason}</span>}
        <span className="line">{infoLine(item)}</span>
        <ScoreLine extras={ext} />
        <span className="tb-hint">{!trailer ? "Open for details" : sound ? "Sound on · again for details" : "Select for sound · again for details"}</span>
        {sound && (
          <button className="btn small" data-nav onClick={mute}>
            Mute
          </button>
        )}
      </div>
      {/* Fixed 16:9 panel: the still until the trailer plays, the trailer letterboxed (never stretched) */}
      <div className="tb-panel">
        <Backdrop url={pick.url} focal={pick.focal} />
        {trailer && (
          <video
            ref={video}
            className={`tb-video${playing && framed ? " on" : ""}`}
            src={trailer}
            muted
            playsInline
            loop
            preload="none"
            onPlaying={() => setFramed(true)}
          />
        )}
      </div>
    </section>
  );
}

// ---------- person_spotlight ----------

function PersonSpotlightBlock({ s }: { s: PersonSpotlight }) {
  const items = useItems(s.itemIds);
  const phone = useIsPhone();
  const untitled = untitledSet(s);
  if (!items || items.length === 0) return null;
  return (
    <section className="special row person-spotlight">
      <SpecialHeader kicker={s.role === "director" ? "Director spotlight" : "Actor spotlight"} title={s.title ?? s.name} />
      <div className="row-scroll">
        <PersonTile
          id={s.personId}
          name={s.name}
          imageTag={s.imageTag}
          width={phone ? 150 : 200}
          sub={`${filmCount(s.filmCount)}${s.unwatchedCount > 0 ? ` · ${s.unwatchedCount} unwatched` : ""}`}
        />
        {items.map((i) => (
          <PosterCard key={i.Id} item={i} onOpen={() => go(`/item/${i.Id}`)} untitled={untitled.has(i.Id)} />
        ))}
      </div>
    </section>
  );
}

// ---------- tonights_pick ----------

function TonightsPickBlock({ s }: { s: TonightsPick }) {
  const item = useItems([s.itemId])?.[0];
  const pick = useSpecialStill(item);
  if (!item) return null;
  return (
    <section className="special tonights-pick">
      <button className="tp-card" data-nav onClick={() => go(`/item/${item.Id}`)} aria-label={`Tonight's pick: ${item.Name}`}>
        <Backdrop url={pick.url} focal={pick.focal} />
        <span className="tp-scrim" />
        <span className="tp-text">
          <span className="kicker">{s.kicker || s.title || "Tonight's pick"}</span>
          <TitleLogo item={item} />
          {s.reason && <em className="reason">{s.reason}</em>}
          <span className="line">{infoLine(item)}</span>
        </span>
      </button>
      <div className="tp-actions">
        <button className="btn primary" data-nav onClick={() => go(`/play/${item.Id}`)}>
          Play
        </button>
      </div>
    </section>
  );
}

// ---------- top10 ----------

function Top10Block({ s }: { s: Top10 }) {
  const items = useItems(s.itemIds.slice(0, 10));
  const untitled = untitledSet(s);
  if (!items || items.length < 3) return null;
  return (
    <section className="special row top10">
      <SpecialHeader kicker="Top 10" title={s.title || (s.variant === "letterboxd" ? "On Letterboxd, not seen yet" : "On Jellytip this month")} />
      <div className="row-scroll">
        {items.map((i, n) => (
          <div key={i.Id} className="top10-entry">
            <span className="top10-num" aria-hidden>
              {n + 1}
            </span>
            <PosterCard item={i} width={140} onOpen={() => go(`/item/${i.Id}`)} label={`${n + 1}. ${i.Name}`} untitled={untitled.has(i.Id)} />
          </div>
        ))}
      </div>
    </section>
  );
}

// ---------- double_feature ----------

function pairArt(i: Item): string | null {
  return cardLayers(i).find((l) => l.url && l.kind !== "poster")?.url ?? null;
}

const PAIR_HALF_W = 264;
const PHONE_PAIR_HALF_W = 168;

/**
 * Each pair is one framed unit with two halves you pick separately (as on the TV): 1 | 2, each a normal titled tile
 * that opens its own film; details opened from a pair offer "Play both" (that film first). Caption shared below.
 */
function DoubleFeatureBlock({ s }: { s: DoubleFeature }) {
  const ids = s.pairs.flatMap((p) => [p.a, p.b]);
  const items = useItems(ids);
  const phone = useIsPhone();
  if (!items) return null;
  const byId = new Map(items.map((i) => [i.Id, i]));
  const pairs = s.pairs.filter((p) => byId.has(p.a) && byId.has(p.b));
  if (!pairs.length) return null;
  const untitled = untitledSet(s);
  const w = phone ? PHONE_PAIR_HALF_W : PAIR_HALF_W;
  return (
    <section className="special row double-feature">
      <SpecialHeader kicker="Double feature" title={s.title} />
      <div className="row-scroll">
        {pairs.map((p) => {
          const a = byId.get(p.a)!;
          const b = byId.get(p.b)!;
          const runtime = p.totalRuntimeMin ? runtimeText(p.totalRuntimeMin * 60 * 10_000_000) : null;
          return (
            <div key={`${p.a}-${p.b}`} className="pair-unit" role="group" aria-label={`Double feature: ${a.Name} and ${b.Name}`}>
              <div className="pair-halves">
                {[
                  [a, b],
                  [b, a],
                ].map(([one, other], n) => (
                  <Card
                    key={one.Id}
                    item={one}
                    width={w}
                    tag={String(n + 1)}
                    untitled={untitled.has(one.Id)}
                    label={`${n + 1} of 2: ${one.Name} (double feature with ${other.Name})`}
                    onOpen={() => go(`/item/${one.Id}?pair=${other.Id}`)}
                  />
                ))}
              </div>
              <span className="pair-caption">
                <strong>
                  {a.Name} + {b.Name}
                </strong>
                {(p.reason || runtime) && <span>{[p.reason, runtime && `${runtime} together`].filter(Boolean).join("  ·  ")}</span>}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ---------- because_loved ----------

function BecauseLovedBlock({ s }: { s: BecauseLoved }) {
  const items = useItems([s.anchorItemId, ...s.itemIds]);
  if (!items) return null;
  const untitled = untitledSet(s);
  const anchor = items.find((i) => i.Id === s.anchorItemId);
  const rest = items.filter((i) => i.Id !== s.anchorItemId);
  if (!anchor || rest.length === 0) return null;
  const art = pairArt(anchor);
  return (
    <section className="special row because-loved">
      <SpecialHeader kicker="Because you loved" title={s.title ?? anchor.Name} />
      <div className="row-scroll">
        <button className="quote-card" data-nav onClick={() => go(`/item/${anchor.Id}`)} aria-label={`Your review of ${anchor.Name}`}>
          {art && <img src={art} alt="" />}
          <span className="quote-body">
            <span className="quote-who">
              You{s.rating ? <> · <Stars rating={s.rating} /></> : null}
            </span>
            {s.quote && <em>“{s.quote.length > 160 ? `${s.quote.slice(0, 157).trimEnd()}…` : s.quote}”</em>}
            <span className="quote-title">{anchor.Name}</span>
          </span>
        </button>
        {rest.map((i) => (
          <Card key={i.Id} item={i} untitled={untitled.has(i.Id)} />
        ))}
      </div>
    </section>
  );
}

// ---------- around_house ----------

function ago(when: string): string {
  const t = Date.parse(when);
  if (!t) return "";
  const h = (Date.now() - t) / 3_600_000;
  if (h < 1) return "just now";
  if (h < 24) return `${Math.round(h)}h ago`;
  const d = Math.round(h / 24);
  return d === 1 ? "yesterday" : `${d}d ago`;
}

function AroundHouseBlock({ s }: { s: AroundHouse }) {
  const items = useItems(s.entries.map((e) => e.itemId));
  if (!items || items.length === 0) return null;
  const byId = new Map(items.map((i) => [i.Id, i]));
  const untitled = untitledSet(s);
  return (
    <section className="special row around-house">
      <SpecialHeader kicker="Friends" title={s.title || "What friends are watching"} />
      <div className="row-scroll">
        {s.entries
          .filter((e) => byId.has(e.itemId))
          .map((e) => (
            <Card key={`${e.itemId}-${e.who}-${e.when}`} item={byId.get(e.itemId)!} tag={`${e.who} ${e.what}${e.when ? ` · ${ago(e.when)}` : ""}`}
              untitled={untitled.has(e.itemId)}
            />
          ))}
      </div>
    </section>
  );
}

// ---------- closet_picks ----------

function ClosetPicksBlock({ s }: { s: ClosetPicks }) {
  const libIds = s.picks.map((p) => p.itemId).filter((x): x is string => !!x);
  const items = useItems(libIds);
  const untitled = untitledSet(s);
  const video = useRef<HTMLVideoElement>(null);
  const [box, inView] = useInView(0.5);
  const [focused, setFocused] = useState(false);
  const [t, setT] = useState(0);
  const [sound, setSound] = useState(false);
  const [getIt, setGetIt] = useState<ClosetPick | null>(null);
  const blocked = useVideoBlocked(s.id);
  const { soundOn, mute } = useSoundBack(s.id, sound, () => setSound(false));
  const admin = !!getSession()?.isAdmin;
  const wantPlay = inView || focused;

  // Focus (re)claims, so the block being used wins over another one that is merely on screen
  useEffect(() => {
    if (wantPlay) claimVideo(s.id);
    else {
      releaseVideo(s.id);
      if (sound) mute();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wantPlay, focused, s.id]);
  useEffect(() => {
    if (blocked && sound) mute(); // another video took over: its sound must not come back later
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocked]);
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    v.muted = !sound;
    if (wantPlay && !blocked) v.play().catch(() => undefined);
    else v.pause();
  }, [wantPlay, blocked, sound]);

  if (!items) return null;
  const byId = new Map(items.map((i) => [i.Id, i]));
  // Lit: the video has passed the pick's moment; the latest one passed is the "current" pick
  const passed = s.picks.filter((p) => p.atSec != null && t >= p.atSec);
  const current = passed.length ? passed[passed.length - 1] : null;
  return (
    <section className="special closet-picks" ref={box}>
      <SpecialHeader kicker="Closet picks" title={s.title ?? `${s.guest} in the Criterion Closet`} />
      <div className="closet">
        <div className="closet-video">
          <video
            ref={video}
            src={companionMediaUrl(s.videoUrl)}
            poster={s.posterUrl ? companionMediaUrl(s.posterUrl) : undefined}
            muted
            playsInline
            preload="metadata"
            onTimeUpdate={(e) => setT(e.currentTarget.currentTime)}
          />
          <button
            className="closet-hit"
            data-nav
            aria-label={sound ? "Mute" : `Watch ${s.guest} with sound`}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            onClick={() => {
              // No details page for a clip: the second press mutes again
              if (sound) mute();
              else {
                claimVideo(s.id);
                setSound(true);
                soundOn();
              }
            }}
          />
          <span className="closet-guest">
            {s.guest}
            {sound ? " · sound on" : ""}
          </span>
        </div>
        <div className="closet-picks-list">
          {s.picks.map((p, n) => {
            const item = p.itemId ? byId.get(p.itemId) : undefined;
            const inLib = !!item;
            const lit = p.atSec != null && t >= p.atSec;
            const label = `${p.title}${p.year ? ` (${p.year})` : ""}${inLib ? "" : " · not in the library"}`;
            const open = inLib ? () => go(`/item/${item!.Id}`) : admin && p.tmdbId ? () => setGetIt(p) : undefined;
            return (
              <PosterCard
                key={`${p.title}-${n}`}
                item={item}
                width={112}
                src={item ? undefined : p.posterUrl ? companionMediaUrl(p.posterUrl) : null}
                faded={!inLib}
                lit={lit && current === p}
                label={label}
                name={p.title}
                untitled={!!item && untitled.has(item.Id)}
                onOpen={open}
              />
            );
          })}
        </div>
      </div>
      {getIt && <GetItSheet pick={getIt} onClose={() => setGetIt(null)} />}
    </section>
  );
}

// ---------- dispatch ----------

export function SpecialBlock({ s }: { s: Special }) {
  switch (s.layout) {
    case "trailer_break":
      return <TrailerBreakBlock s={s} />;
    case "person_spotlight":
      return <PersonSpotlightBlock s={s} />;
    case "tonights_pick":
      return <TonightsPickBlock s={s} />;
    case "top10":
      return <Top10Block s={s} />;
    case "double_feature":
      return <DoubleFeatureBlock s={s} />;
    case "because_loved":
      return <BecauseLovedBlock s={s} />;
    case "around_house":
      return <AroundHouseBlock s={s} />;
    case "closet_picks":
      return <ClosetPicksBlock s={s} />;
    default:
      return null; // newer server, older app: skip
  }
}

/** Interleave specials with the normal rows (rules in lib/specialsPlacement.ts). */
export function withSpecials(rows: React.ReactNode[], specials: Special[]): React.ReactNode[] {
  const slots = placeSpecials(rows.length, specials);
  const out: React.ReactNode[] = [];
  slots.forEach((list, i) => {
    for (const s of list) out.push(<SpecialBlock key={`special-${s.id}`} s={s} />);
    if (i < rows.length) out.push(rows[i]);
  });
  return out;
}
