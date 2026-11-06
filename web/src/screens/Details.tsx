// Details for movies, series and episodes. Backdrop = companion detailsIndex (≠ hero, ≠ tile), info block bottom-left only;
// series get season chips + episodes, episodes get prev/next; cast and more-like-this sit further down.
import { useEffect, useMemo, useRef, useState } from "react";
import {
  getSession,
  episodes as getEpisodes,
  episodesComplete,
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
import { backdropInfo, episodeArtFor, extras, peekBackdropInfo, type Extras } from "../api/companion";
import { trailerStreamUrl } from "../api/playback";
import { heroItemId, heroPick, type ImagePick } from "../lib/art";
import { go } from "../lib/router";
import { focusFirst } from "../lib/spatial";
import { useIsPhone } from "../lib/device";
import { Backdrop } from "../components/Backdrop";
import { jfTarget } from "../lib/trailerPreview";
import { useTrailerPreview } from "../lib/useTrailerPreview";
import { PreviewVideo } from "../components/PreviewVideo";
import { Card } from "../components/Card";
import { Row } from "../components/Row";
import { ScoreLine } from "../components/ScoreLine";
import { DownloadButton, SeasonDownloadButton } from "../components/DownloadButton";
import { fileBadges, infoLine, TitleLogo } from "../components/TitleBlock";
import { DetailsQuote, ReviewsRow } from "../components/Reviews";
import { TasteButtons } from "../components/TasteButtons";
import { runtimeText } from "../api/jellyfin";
import { detailBadges, hasVideoTech, techOf } from "../lib/techBadges";
import { companionCall } from "../api/companionCall";
import { usePrefs } from "../api/me";
import { SubtitleSearchDialog } from "../components/SubtitleSearchDialog";
import { useCanManageSubtitles } from "../lib/useSubtitleAccess";
import { canSearchSeasons, defaultSearchLanguage } from "../lib/remoteSubtitles";
import { resultText, runSeasonSubtitles, type Progress } from "../lib/seasonSubtitles";
import { mergeEpisodes, presentEpisodes } from "../lib/seasonEpisodes";
import { iptvSeriesFor } from "../api/seasonIptv";
import type { VodDetail } from "../lib/iptvVod";
import { targetPath } from "../lib/iptvPlay";
import { EpisodePlaceholder } from "../components/EpisodePlaceholder";
import { GetItSheet } from "../components/GetItSheet";

function fmtClock(ticks: number) {
  const s = Math.floor(ticks / TICKS_PER_SECOND);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

/** How long the details page waits (dark) for the companion's backdrop ranking before choosing without it. */
const RANK_WAIT_MS = 800;

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
  const [allEps, setAllEps] = useState<Item[] | null>(null);
  const [iptvSeries, setIptvSeries] = useState<VodDetail | null>(null);
  const [getIt, setGetIt] = useState(false);
  const [siblings, setSiblings] = useState<Item[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [subtitleProgress, setSubtitleProgress] = useState<Progress | null>(null);
  const [subtitleActive, setSubtitleActive] = useState(false);
  const subtitleLock = useRef(false);
  const canSubs = useCanManageSubtitles();
  const canSeasons = canSearchSeasons(getSession());
  const [findSubs, setFindSubs] = useState(false);
  const { prefs } = usePrefs({ defer: true });
  const phone = useIsPhone();
  // Double feature (from the specials row): "Play both" plays this one, then the pair
  const pairParam = new URLSearchParams(location.hash.split("?")[1] ?? "").get("pair") ?? "";
  const pairId = /^[0-9a-f]{32}$/i.test(pairParam) ? pairParam : null;
  const [, bump] = useState(0);
  const [pairItem, setPairItem] = useState<Item | null>(null);
  useEffect(() => {
    setPairItem(null);
    if (pairId) getItem(pairId).then(setPairItem, () => undefined);
  }, [pairId]);

  useEffect(() => {
    let alive = true;
    setItem(null);
    setError(null);
    setExt(null);
    setTrailerId(null);
    setShowTrailer(false);
    setOverviewOpen(false);
    setSeasonList([]);
    setAllEps(null);
    setIptvSeries(null);
    setGetIt(false);
    setSiblings([]);
    getItem(id)
      .then((it) => {
        if (!alive) return;
        if (it.Type === "Season" && it.SeriesId) {
          go(`/item/${it.SeriesId}?season=${it.Id}`, true);
          return;
        }
        setItem(it);
        // One backdrop, never swapped: the ranking already cached by the hero / tiles is used at once; otherwise the
        // page stays dark until it arrives (at most RANK_WAIT_MS), and an answer after that is not applied
        const rankedId = heroItemId(it);
        if (peekBackdropInfo(rankedId)) setPick(heroPick(it, true));
        else {
          let settled = false;
          const settle = () => {
            if (settled || !alive) return;
            settled = true;
            setPick(heroPick(it, true));
          };
          const quick = setTimeout(settle, RANK_WAIT_MS);
          backdropInfo(rankedId, { priority: "high" }).then(() => {
            clearTimeout(quick);
            settle();
          });
        }
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
          iptvSeriesFor(it.Name, it.ProviderIds?.Tmdb).then((v) => alive && setIptvSeries(v));
        }
        if (it.Type === "Episode" && it.SeriesId) {
          getEpisodes(it.SeriesId).then((all) => alive && setSiblings(presentEpisodes(all)));
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
    setAllEps(null);
    episodesComplete(item.Id, season).then((e) => alive && setAllEps(e));
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

  // Files only for play / download / Resume; the list below also shows placeholders and IPTV-only episodes
  const eps = useMemo(() => (allEps ? presentEpisodes(allEps) : null), [allEps]);
  const seasonNo = seasonList.find((s) => s.Id === season)?.IndexNumber;
  const rows = useMemo(
    () => (allEps ? mergeEpisodes(allEps, iptvSeries?.seasons?.find((s) => s.season === seasonNo)?.episodes, new Date()) : null),
    [allEps, iptvSeries, seasonNo],
  );
  const admin = !!getSession()?.isAdmin;
  const tmdb = item?.ProviderIds?.Tmdb ? Number(item.ProviderIds.Tmdb) : 0;
  const playIptv = (episodeId: string) =>
    iptvSeries && go(`${targetPath({ series: iptvSeries.id, episode: episodeId })}&t=${encodeURIComponent(`${iptvSeries.title}`)}`);
  const ph = (r: NonNullable<typeof rows>[number]) => (
    <EpisodePlaceholder key={r.key} row={r} now={new Date()} onIptv={playIptv} onRequest={admin && tmdb > 0 ? () => setGetIt(true) : undefined} />
  );

  const loadSimilar = useMemo(() => (item && item.Type !== "Episode" ? () => similar(item.Id) : null), [item]);

  // Muted trailer in the backdrop after a rest, as on the TV; off while the full trailer modal is open
  const preview = useTrailerPreview(item && !showTrailer ? jfTarget(item) : null, { trigger: "passive" });

  if (error) return <div className="empty">Couldn't load this title. {error}</div>;
  if (!item) return <div className="details loading" />;

  const ud = item.UserData;
  const resumeTicks = ud?.PlaybackPositionTicks ?? 0;
  // Series: play the next unwatched / in-progress episode
  const seriesNext = item.Type === "Series" ? eps?.find((e) => (e.UserData?.PlaybackPositionTicks ?? 0) > 0) ?? eps?.find((e) => !e.UserData?.Played) : null;
  const director = item.People?.filter((p) => p.Type === "Director").map((p) => p.Name).join(", ");
  const dim = [director ? `Directed by ${director}` : null, item.ProductionLocations?.[0]].filter(Boolean).join("  ·  ");
  const cast = (item.People ?? []).filter((p) => p.Type === "Actor").slice(0, 18);
  // Premium formats from the companion (contract "Tech badges"); then the file's own facts minus what they cover
  const tech = techOf(item.Id);
  const techBadges = detailBadges(tech);
  const badges = [...techBadges, ...fileBadges(item).filter((b) => !(hasVideoTech(tech) && (b === "4K" || b === "HDR" || b === "Dolby Vision")))];
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
  const searchSeasonSubtitles = async (ids: string[]) => {
    if (subtitleLock.current || !ids.length) return;
    subtitleLock.current = true;
    setSubtitleActive(true);
    try {
      await runSeasonSubtitles(ids, defaultSearchLanguage(prefs?.playback.subtitleLangs), companionCall, setSubtitleProgress);
    } finally {
      subtitleLock.current = false;
      setSubtitleActive(false);
    }
  };

  return (
    <div className="details">
      <div className="details-hero">
        <Backdrop url={pick.url} focal={pick.focal} />
        <PreviewVideo url={preview} />
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
          {pairId && pairItem && item.Type === "Movie" && (
            <div className="line pair-line">
              Double feature with{" "}
              <button className="link" data-nav onClick={() => go(`/item/${pairItem.Id}?pair=${item.Id}`, true)}>
                {pairItem.Name}
              </button>
            </div>
          )}
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
            {pairId && item.Type === "Movie" && (
              <button className="btn" data-nav onClick={() => go(`/play/${item.Id}?then=${pairId}`)}>
                ▶ Play both
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
            {canSubs && (item.Type === "Movie" || item.Type === "Episode") && (
              <button className="btn" data-nav aria-haspopup="dialog" onClick={() => setFindSubs(true)}>
                Find subtitles
              </button>
            )}
            {item.Type !== "Episode" && <TasteButtons item={item} />}
          </div>
          {phone && <DetailsQuote item={item} phone />}
        </div>
        {!phone && <DetailsQuote item={item} phone={false} />}
      </div>

      {findSubs && canSubs && (
        <SubtitleSearchDialog
          itemId={item.Id}
          title={item.Type === "Episode" ? `${item.SeriesName ?? ""} · S${item.ParentIndexNumber} E${item.IndexNumber} · ${item.Name}` : item.Name}
          subtitleLangs={prefs?.playback.subtitleLangs}
          where="page"
          onClose={() => setFindSubs(false)}
          onAdded={(fresh) => setItem((cur) => (cur && cur.Id === fresh.Id ? { ...cur, MediaSources: fresh.MediaSources } : cur))}
        />
      )}

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
              {canSeasons && season && <button className="chip" data-nav disabled={subtitleActive} onClick={() => void searchSeasonSubtitles([season])}>Search subtitles for this season</button>}
              {canSeasons && <button className="chip" data-nav disabled={subtitleActive} onClick={() => void searchSeasonSubtitles(seasonList.map((s) => s.Id))}>Search subtitles for all seasons</button>}
            </div>
            {canSeasons && subtitleProgress && <p role="status" aria-live="polite" className="line dim">{resultText(subtitleProgress)}</p>}
            {phone ? (
              // Phone: a vertical list, still + name/runtime/overview side by side, the whole row is the tap target
              <div className="ep-list">
                {(rows ?? []).map((r) => {
                  const e = r.item;
                  if (r.status !== "present" || !e) return ph(r);
                  return (
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
                  );
                })}
                {!rows && Array.from({ length: 3 }, (_, i) => <div key={i} className="ep-row card-skeleton" />)}
              </div>
            ) : (
              <div className="row-scroll">
                {(rows ?? []).map((r) => (r.status !== "present" || !r.item ? ph(r) : <Card key={r.key} item={r.item} caption={`${r.item.IndexNumber ?? ""} · ${r.item.Name}`} />))}
                {!rows && Array.from({ length: 5 }, (_, i) => <div key={i} className="card card-skeleton" />)}
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
        <ReviewsRow item={item} />
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

      {getIt && tmdb > 0 && <GetItSheet pick={{ title: item.Name, year: item.ProductionYear, tmdbId: tmdb, mediaType: "tv" }} onClose={() => setGetIt(false)} />}
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
