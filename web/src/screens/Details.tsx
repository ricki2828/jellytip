// Details for movies, series and episodes. Backdrop = companion detailsIndex (≠ hero, ≠ tile), info block bottom-left only;
// series get season chips + episodes, episodes get prev/next; cast and more-like-this sit further down.
import { useEffect, useMemo, useState } from "react";
import {
  episodes as getEpisodes,
  getItem,
  localTrailers,
  personImage,
  seasons as getSeasons,
  setFavorite,
  setPlayed,
  similar,
  TICKS_PER_SECOND,
  type Item,
} from "../api/jellyfin";
import { backdropInfo, episodeArtFor, extras, type Extras } from "../api/companion";
import { trailerStreamUrl } from "../api/playback";
import { heroItemId, heroPick, type ImagePick } from "../lib/art";
import { go } from "../lib/router";
import { focusFirst } from "../lib/spatial";
import { useIsPhone } from "../lib/device";
import { Backdrop } from "../components/Backdrop";
import { Card } from "../components/Card";
import { Row } from "../components/Row";
import { ScoreLine } from "../components/ScoreLine";
import { DownloadButton, SeasonDownloadButton } from "../components/DownloadButton";
import { fileBadges, infoLine, TitleLogo } from "../components/TitleBlock";
import { runtimeText } from "../api/jellyfin";

function fmtClock(ticks: number) {
  const s = Math.floor(ticks / TICKS_PER_SECOND);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

export function Details({ id }: { id: string }) {
  const [item, setItem] = useState<Item | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pick, setPick] = useState<ImagePick>({ url: null, focal: null });
  const [ext, setExt] = useState<Extras | null>(null);
  const [trailerId, setTrailerId] = useState<string | null>(null);
  const [showTrailer, setShowTrailer] = useState(false);
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [seasonList, setSeasonList] = useState<Item[]>([]);
  const [season, setSeason] = useState<string | null>(null);
  const [eps, setEps] = useState<Item[] | null>(null);
  const [siblings, setSiblings] = useState<Item[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const phone = useIsPhone();
  const [, bump] = useState(0);

  useEffect(() => {
    let alive = true;
    setItem(null);
    setError(null);
    setExt(null);
    setTrailerId(null);
    setShowTrailer(false);
    setOverviewOpen(false);
    setSeasonList([]);
    setEps(null);
    setSiblings([]);
    getItem(id)
      .then((it) => {
        if (!alive) return;
        if (it.Type === "Season" && it.SeriesId) {
          go(`/item/${it.SeriesId}?season=${it.Id}`, true);
          return;
        }
        setItem(it);
        const quick = setTimeout(() => alive && setPick(heroPick(it, true)), 1200);
        backdropInfo(heroItemId(it), { priority: "high" }).then(() => {
          clearTimeout(quick);
          if (alive) setPick(heroPick(it, true));
        });
        const kind = it.Type === "Movie" ? "movie" : it.Type === "Series" ? "series" : null;
        if (kind) extras(it.Id, kind).then((e) => alive && setExt(e));
        const trailerOf = it.Type === "Episode" ? it.SeriesId : it.Id;
        if (trailerOf) localTrailers(trailerOf).then((t) => alive && setTrailerId(t[0]?.Id ?? null));
        if (it.Type === "Series") {
          getSeasons(it.Id).then((s) => {
            if (!alive) return;
            setSeasonList(s);
            const wanted = new URLSearchParams(location.hash.split("?")[1] ?? "").get("season");
            // Default: the first season with something unwatched, else the first
            // A ?season= that isn't one of this series' seasons (empty, stale) is ignored
            const asked = wanted ? s.find((x) => x.Id === wanted) : undefined;
            setSeason((asked ?? s.find((x) => !x.UserData?.Played) ?? s[0])?.Id ?? null);
          });
          episodeArtFor(it.Id, { priority: "high" }).then(() => alive && bump((n) => n + 1));
        }
        if (it.Type === "Episode" && it.SeriesId) {
          getEpisodes(it.SeriesId).then((all) => alive && setSiblings(all));
        }
        setTimeout(() => focusFirst(document.querySelector(".details") ?? document), 0);
      })
      .catch((e) => alive && setError(String(e.message ?? e)));
    return () => {
      alive = false;
    };
  }, [id]);

  useEffect(() => {
    if (!item || item.Type !== "Series" || !season) return;
    let alive = true;
    setEps(null);
    getEpisodes(item.Id, season).then((e) => alive && setEps(e));
    return () => {
      alive = false;
    };
  }, [item, season]);

  // Esc closes the trailer instead of leaving the page (document runs before the app's window-level Esc)
  useEffect(() => {
    if (!showTrailer) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      setShowTrailer(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [showTrailer]);

  const loadSimilar = useMemo(() => (item && item.Type !== "Episode" ? () => similar(item.Id) : null), [item]);

  if (error) return <div className="empty">Couldn't load this title. {error}</div>;
  if (!item) return <div className="details loading" />;

  const ud = item.UserData;
  const resumeTicks = ud?.PlaybackPositionTicks ?? 0;
  // Series: play the next unwatched / in-progress episode
  const seriesNext = item.Type === "Series" ? eps?.find((e) => (e.UserData?.PlaybackPositionTicks ?? 0) > 0) ?? eps?.find((e) => !e.UserData?.Played) : null;
  const director = item.People?.filter((p) => p.Type === "Director").map((p) => p.Name).join(", ");
  const dim = [director ? `Directed by ${director}` : null, item.ProductionLocations?.[0]].filter(Boolean).join("  ·  ");
  const cast = (item.People ?? []).filter((p) => p.Type === "Actor").slice(0, 18);
  const badges = fileBadges(item);
  const epIdx = siblings.findIndex((e) => e.Id === item.Id);
  const prev = epIdx > 0 ? siblings[epIdx - 1] : null;
  const next = epIdx >= 0 && epIdx < siblings.length - 1 ? siblings[epIdx + 1] : null;

  const flash = (msg: string) => {
    setNotice(msg);
    setTimeout(() => setNotice(null), 3000);
  };
  const toggleWatched = async () => {
    try {
      await setPlayed(item.Id, !ud?.Played);
      setItem({ ...item, UserData: { ...ud, Played: !ud?.Played, PlaybackPositionTicks: 0, PlayedPercentage: 0 } });
    } catch {
      flash("Couldn't update watched. Try again.");
    }
  };
  const toggleFav = async () => {
    try {
      await setFavorite(item.Id, !ud?.IsFavorite);
      setItem({ ...item, UserData: { ...ud, IsFavorite: !ud?.IsFavorite } });
    } catch {
      flash("Couldn't update favourite. Try again.");
    }
  };

  return (
    <div className="details">
      <div className="details-hero">
        <Backdrop url={pick.url} focal={pick.focal} />
        <div className="scrim-left" />
        <div className="scrim-bottom" />
        <div className="details-text">
          <TitleLogo item={item} big />
          {item.Type === "Episode" && (
            <div className="line strong">
              S{item.ParentIndexNumber} E{item.IndexNumber} · {item.Name}
            </div>
          )}
          <div className="line info">
            <span>{infoLine(item)}</span>
            {item.Type === "Series" && seasonList.length > 0 && <span>{seasonList.length} season{seasonList.length > 1 ? "s" : ""}</span>}
            {badges.map((b) => (
              <span key={b} className="badge">
                {b}
              </span>
            ))}
          </div>
          {dim && <div className="line dim">{dim}</div>}
          {item.Overview && (
            <button className={`overview${overviewOpen ? " open" : ""}`} data-nav onClick={() => setOverviewOpen((o) => !o)}>
              {item.Overview}
            </button>
          )}
          <ScoreLine extras={ext} />
          <div className="buttons">
            {item.Type === "Series" ? (
              seriesNext && (
                <button className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${seriesNext.Id}`)}>
                  {(seriesNext.UserData?.PlaybackPositionTicks ?? 0) > 0 ? "Resume" : "Play"} S{seriesNext.ParentIndexNumber} E{seriesNext.IndexNumber}
                </button>
              )
            ) : (
              <button className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${item.Id}`)}>
                {resumeTicks > 0 ? `Resume ${fmtClock(resumeTicks)}` : "Play"}
              </button>
            )}
            {item.Type !== "Series" && resumeTicks > 0 && (
              <button className="btn" data-nav onClick={() => go(`/play/${item.Id}?start=0`)}>
                From start
              </button>
            )}
            {trailerId && (
              <button className="btn" data-nav onClick={() => setShowTrailer(true)}>
                Trailer
              </button>
            )}
            {item.Type === "Episode" && (
              <>
                <button className="btn" data-nav disabled={!prev} onClick={() => prev && go(`/item/${prev.Id}`, true)}>
                  ‹ Prev
                </button>
                <button className="btn" data-nav disabled={!next} onClick={() => next && go(`/item/${next.Id}`, true)}>
                  Next ›
                </button>
                <button className="btn" data-nav onClick={() => go(item.SeasonId ? `/item/${item.SeriesId}?season=${item.SeasonId}` : `/item/${item.SeriesId}`)}>
                  Series
                </button>
              </>
            )}
            <button className="btn" data-nav onClick={toggleWatched}>
              {ud?.Played ? "Watched ✓" : "Mark watched"}
            </button>
            <button className="btn" data-nav onClick={toggleFav}>
              {ud?.IsFavorite ? "♥ Favourite" : "♡ Favourite"}
            </button>
            {item.Type !== "Series" && <DownloadButton item={item} onError={flash} />}
          </div>
        </div>
      </div>

      <div className="details-below">
        {item.Type === "Series" && seasonList.length > 0 && (
          <section className="row">
            <div className="chips">
              {seasonList.map((s) => (
                <button key={s.Id} data-nav className={`chip${s.Id === season ? " on" : ""}`} onClick={() => setSeason(s.Id)}>
                  {s.Name}
                </button>
              ))}
              <SeasonDownloadButton episodes={eps ?? []} onError={flash} />
            </div>
            {phone ? (
              // Phone: a vertical list, still + name/runtime/overview side by side, the whole row is the tap target
              <div className="ep-list">
                {(eps ?? []).map((e) => (
                  <div key={e.Id} className="ep-row">
                    <Card item={e} width={150} caption={`${e.IndexNumber ?? ""}`} />
                    <button className="ep-text" onClick={() => go(`/item/${e.Id}`)}>
                      <span className="ep-name">
                        {e.IndexNumber}. {e.Name}
                      </span>
                      <span className="ep-meta">{[runtimeText(e.RunTimeTicks), e.UserData?.Played ? "Watched" : null].filter(Boolean).join("  ·  ")}</span>
                      {e.Overview && <span className="ep-overview">{e.Overview}</span>}
                    </button>
                  </div>
                ))}
                {!eps && Array.from({ length: 3 }, (_, i) => <div key={i} className="ep-row card-skeleton" />)}
              </div>
            ) : (
              <div className="row-scroll">
                {(eps ?? []).map((e) => (
                  <Card key={e.Id} item={e} caption={`${e.IndexNumber ?? ""} · ${e.Name}`} />
                ))}
                {!eps && Array.from({ length: 5 }, (_, i) => <div key={i} className="card card-skeleton" />)}
              </div>
            )}
          </section>
        )}
        {item.Type === "Episode" && siblings.length > 0 && (
          <Row
            key={`eps-${item.SeasonId}`}
            title={item.SeasonName ?? "Episodes"}
            items={siblings.filter((e) => e.SeasonId === item.SeasonId)}
            onOpen={(e) => go(`/item/${e.Id}`, true)}
          />
        )}
        {cast.length > 0 && (
          <section className="row">
            <h2 className="row-title">Cast</h2>
            <div className="row-scroll">
              {cast.map((p) => {
                const img = personImage(p);
                return (
                  <div key={p.Id + (p.Role ?? "")} className="person">
                    {img ? <img src={img} alt="" loading="lazy" /> : <div className="person-blank" />}
                    <div className="person-text">
                      <span>{p.Name}</span>
                      {p.Role && <span className="dim">{p.Role}</span>}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        )}
        {loadSimilar && <Row key={`sim-${item.Id}`} title="More like this" load={loadSimilar} />}
      </div>

      {notice && <div className="toast">{notice}</div>}
      {showTrailer && trailerId && (
        <div className="trailer-modal" onClick={() => setShowTrailer(false)}>
          <video
            src={trailerStreamUrl(trailerId)}
            autoPlay
            controls
            onEnded={() => setShowTrailer(false)}
            onError={() => {
              setShowTrailer(false);
              flash("The trailer won't play in this browser.");
            }}
            onClick={(e) => e.stopPropagation()}
          />
          <button className="btn modal-close" data-nav data-autofocus onClick={() => setShowTrailer(false)}>
            Close
          </button>
        </div>
      )}
    </div>
  );
}
