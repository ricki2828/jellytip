// Details for movies, series and episodes. Backdrop = companion detailsIndex (≠ hero, ≠ tile), info block bottom-left only;
// series get season chips + episodes, episodes get prev/next; cast and more-like-this sit further down.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  getSession,
  episodes as getEpisodes,
  episodesComplete,
  seriesEpisodesComplete,
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
import { emitSignal, useDetailDwell, useTrailerWatch } from "../api/signals";
import { libraryTarget } from "../lib/signals";
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
import { SimilarRow } from "../components/SimilarRow";
import { ScoreLine } from "../components/ScoreLine";
import { DownloadButton, SeasonDownloadButton } from "../components/DownloadButton";
import { fileBadges, infoLine, TitleLogo } from "../components/TitleBlock";
import { DetailsQuote, ReviewsRow } from "../components/Reviews";
import { TasteButtons } from "../components/TasteButtons";
import { runtimeText } from "../api/jellyfin";
import { detailBadges, hasVideoTech, techOf } from "../lib/techBadges";
import { companionCall } from "../api/companionCall";
import { usePrefs } from "../api/me";
import { DeleteFromServer } from "../components/DeleteFromServer";
import { SubtitleSearchDialog } from "../components/SubtitleSearchDialog";
import { useCanManageSubtitles } from "../lib/useSubtitleAccess";
import { canSearchSeasons, defaultSearchLanguage } from "../lib/remoteSubtitles";
import { resultText, runSeasonSubtitles, type Progress } from "../lib/seasonSubtitles";
import { IPTV_SEASON_PREFIX, iptvSeasonKey, iptvSeasonRows, mergeEpisodes, mergeSeasonBlocks, mergeSeasons, presentEpisodes, episodeStatus, type EpRow } from "../lib/seasonEpisodes";
import { iptvResumeAt, seriesPrimary, splitEpisodes, type SeriesNextClaim, type SplitEp } from "../lib/splitNext";
import { seriesNextFor } from "../api/iptv";
import { iptvSeriesFor } from "../api/seasonIptv";
import type { VodDetail } from "../lib/iptvVod";
import { targetPath } from "../lib/iptvPlay";
import { EpisodePlaceholder } from "../components/EpisodePlaceholder";
import { GetItSheet } from "../components/GetItSheet";
import { WaysToWatch, useDownloaded } from "../components/WaysToWatch";
import { availabilityOf, type IptvCopy, type Way } from "../lib/availability";
import { iptvMovieFor, type MovieIptv } from "../api/movieIptv";
import { vodPath } from "../lib/iptvVod";

function fmtClock(ticks: number) {
  const s = Math.floor(ticks / TICKS_PER_SECOND);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

/** How long the details page waits (dark) for the companion's backdrop ranking before choosing without it. */
const RANK_WAIT_MS = 800;

type Primary = NonNullable<ReturnType<typeof seriesPrimary>>;

/**
 * The button when the merged list has no slot (episodes without numbers, a specials-only series): the old library rule over
 * the files, a started one first, else the first unwatched, else the first. Null = nothing to play.
 */
function libraryOnlyPrimary(all: Item[]): Primary | null {
  const files = all.filter((e) => e.LocationType !== "Virtual");
  const pick = files.find((e) => (e.UserData?.PlaybackPositionTicks ?? 0) > 0) ?? files.find((e) => !e.UserData?.Played) ?? files[0];
  if (!pick) return null;
  const ticks = pick.UserData?.PlaybackPositionTicks ?? 0;
  const lib = { id: pick.Id, season: pick.ParentIndexNumber ?? null, episode: pick.IndexNumber ?? null, name: pick.Name, played: !!pick.UserData?.Played, positionTicks: ticks };
  const ep: SplitEp = { season: pick.ParentIndexNumber ?? 0, episode: pick.IndexNumber ?? 0, lib, iptv: null, played: lib.played, inProgress: ticks > 0 && !lib.played };
  return { ep, resume: ep.inProgress, next: false };
}

/** How long the series page's primary button waits for the episodes, the IPTV lookup and the server's next */
const SPLIT_WAIT_MS = 6000;

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
  // Series: every episode (all seasons, with placeholders) and the server's merged next, for the primary button (lib/splitNext)
  const [seriesEps, setSeriesEps] = useState<Item[] | null>(null);
  const [claim, setClaim] = useState<{ v: SeriesNextClaim | null } | null>(null);
  const seasonPicked = useRef(false);
  const [seasonsDone, setSeasonsDone] = useState(false);
  // The primary button's choice, made ONCE per page (the first moment everything answered or SPLIT_WAIT_MS passed): late
  // answers still fill the tiles but never relabel the button, move the Next pill or switch the season under him
  const frozen = useRef<{ id: string; v: Primary | null } | null>(null);
  const [allEps, setAllEps] = useState<{ season: string; items: Item[] } | null>(null);
  const [iptvDone, setIptvDone] = useState(false);
  const [iptvSeries, setIptvSeries] = useState<VodDetail | null>(null);
  const [getIt, setGetIt] = useState(false);
  const [movieIptv, setMovieIptv] = useState<MovieIptv | null>(null);
  const [movieDone, setMovieDone] = useState(false);
  const [online, setOnline] = useState(() => typeof navigator === "undefined" || navigator.onLine !== false);
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
    setIptvDone(false);
    setMovieIptv(null);
    setMovieDone(false);
    setGetIt(false);
    setSiblings([]);
    setSeriesEps(null);
    setClaim(null);
    setSeasonsDone(false);
    seasonPicked.current = false;
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
            setSeasonsDone(true);
            const wanted = new URLSearchParams(location.hash.split("?")[1] ?? "").get("season");
            // Default: the first season with something unwatched, else the first
            // A ?season= that isn't one of this series' seasons (empty, stale) is ignored
            const asked = wanted ? s.find((x) => x.Id === wanted) : undefined;
            if (asked) seasonPicked.current = true; // ?season= wins over the next episode's season
            setSeason((asked ?? s.find((x) => !x.UserData?.Played) ?? s[0])?.Id ?? null);
          }, () => alive && setSeasonsDone(true));
          episodeArtFor(it.Id, { priority: "high" }).then(() => alive && bump((n) => n + 1));
          // A failure is an empty list / no claim for this visit (the button falls back), never remembered
          seriesEpisodesComplete(it.Id).then(
            (e) => alive && setSeriesEps(e),
            () => alive && setSeriesEps([]),
          );
          seriesNextFor(it.Id).then(
            (c) => alive && setClaim({ v: c }),
            () => alive && setClaim({ v: null }),
          );
          iptvSeriesFor(it.Name, it.ProviderIds?.Tmdb).then(
            (v) => {
              if (!alive) return;
              setIptvSeries(v);
              setIptvDone(true);
            },
            () => alive && setIptvDone(true),
          );
          // The primary button never waits past SPLIT_WAIT_MS: whatever hasn't answered by then counts as absent for this visit
          setTimeout(() => {
            if (!alive) return;
            setSeriesEps((e) => e ?? []);
            setClaim((c) => c ?? { v: null });
            setIptvDone(true);
            setSeasonsDone(true);
          }, SPLIT_WAIT_MS);
        }
        if (it.Type === "Movie") {
          iptvMovieFor(it.Name, it.ProviderIds?.Tmdb).then((v) => {
            if (!alive) return;
            setMovieIptv(v);
            setMovieDone(true);
          });
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
    if (season.startsWith(IPTV_SEASON_PREFIX)) return;
    episodesComplete(item.Id, season).then((e) => alive && setAllEps({ season, items: e }));
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
  const seasonEntries = useMemo(() => mergeSeasons(seasonList, iptvSeries?.seasons), [seasonList, iptvSeries]);
  const iptvOnlyNo = season?.startsWith(IPTV_SEASON_PREFIX) ? Number(season.slice(IPTV_SEASON_PREFIX.length)) : null;
  const cur = allEps && allEps.season === season ? allEps.items : null;
  const eps = useMemo(() => (cur ? presentEpisodes(cur) : null), [cur]);
  const seasonNo = seasonList.find((s) => s.Id === season)?.IndexNumber;
  const rows = useMemo(() => {
    const blocks = mergeSeasonBlocks(iptvSeries?.seasons);
    if (iptvOnlyNo != null) return iptvSeasonRows(blocks.find((s) => s.season === iptvOnlyNo)?.episodes ?? [], iptvSeries?.langUnknown);
    return cur ? mergeEpisodes(cur, blocks.find((s) => s.season === seasonNo)?.episodes, new Date()) : null;
  }, [cur, iptvSeries, seasonNo, iptvOnlyNo]);
  // The primary button over the merged library + IPTV list: the server's claim when the page can place it, else the order
  // rule (lib/splitNext). Waits for the episodes, the IPTV lookup and the claim, so its label never changes under a click.
  const splitReady = item?.Type === "Series" && item.Id === id && seriesEps !== null && iptvDone && claim !== null && seasonsDone;
  const split = useMemo<SplitEp[]>(
    () =>
      seriesEps && iptvDone
        ? splitEpisodes(
            seriesEps.map((e) => {
              const st = episodeStatus(e, new Date());
              return { id: e.Id, season: e.ParentIndexNumber ?? null, episode: e.IndexNumber ?? null, name: e.Name, played: !!e.UserData?.Played, positionTicks: e.UserData?.PlaybackPositionTicks ?? 0, virtual: st !== "present", unaired: st === "unaired" };
            }),
            iptvSeries?.seasons,
            seasonList.flatMap((s) => (s.IndexNumber != null ? [s.IndexNumber] : [])),
          )
        : [],
    [seriesEps, iptvDone, iptvSeries, seasonList],
  );
  if (splitReady && frozen.current?.id !== id) frozen.current = { id, v: seriesPrimary(split, claim?.v) ?? libraryOnlyPrimary(seriesEps ?? []) };
  const primary = frozen.current?.id === id ? frozen.current.v : null;
  const decided = frozen.current?.id === id;
  const isNextRow = (r: EpRow) =>
    !!primary?.next && (primary.ep.lib ? r.status === "present" && r.item?.Id === primary.ep.lib.id : !!primary.ep.iptv && r.iptv?.id === primary.ep.iptv.id);
  // Open on the next episode's season (unless ?season= asked or a chip was picked)
  const nextSeasonKey = primary?.next ? seasonList.find((s) => s.IndexNumber === primary.ep.season)?.Id ?? (primary.ep.lib ? null : iptvSeasonKey(primary.ep.season)) : null;
  useEffect(() => {
    // Not while he is in the episode list (the list would swap under his focus)
    if (nextSeasonKey && !seasonPicked.current && !document.activeElement?.closest(".details-below")) setSeason(nextSeasonKey);
  }, [nextSeasonKey]);
  // No button after all (nothing playable): focus that sat on the waiting button moves on, never to nothing
  useLayoutEffect(() => {
    if (decided && !primary && document.activeElement === document.body) focusFirst(document.querySelector(".details") ?? document);
  }, [decided, primary]);
  const admin = !!getSession()?.isAdmin;
  const tmdb = item?.ProviderIds?.Tmdb ? Number(item.ProviderIds.Tmdb) : 0;
  const playIptv = (episodeId: string, resume = 0) =>
    iptvSeries && go(`${targetPath({ series: iptvSeries.id, episode: episodeId })}&t=${encodeURIComponent(`${iptvSeries.title}`)}${resume ? `&resume=${resume}` : ""}`);
  const playSlot = (e: SplitEp) => {
    if (e.lib) go(`/play/${e.lib.id}`);
    else if (e.iptv && iptvSeries) go(`${targetPath({ series: iptvSeries.id, episode: e.iptv.id })}&t=${encodeURIComponent(`${iptvSeries.title} · S${e.season} E${e.episode}`)}${iptvResumeAt(e.iptv) ? `&resume=${iptvResumeAt(e.iptv)}` : ""}`);
  };
  const ph = (r: NonNullable<typeof rows>[number]) => (
    <EpisodePlaceholder key={r.key} row={r} now={new Date()} onIptv={playIptv} onRequest={admin && iptvDone && tmdb > 0 ? () => setGetIt(true) : undefined} />
  );

  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => {
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
    };
  }, []);
  const downloaded = useDownloaded(item?.Id);
  const ways = useMemo<Way[]>(() => {
    if (!item) return [];
    const iptv: IptvCopy[] =
      item.Type === "Series" && iptvSeries
        ? [{ id: iptvSeries.id, providerName: iptvSeries.providerName, quality: iptvSeries.quality, tech: iptvSeries.tech }]
        : item.Type === "Movie"
          ? movieIptv?.copies ?? []
          : [];
    const missing = item.Type === "Series" && !!rows?.some((r) => r.status === "missing" && !r.iptv);
    return availabilityOf(item, {
      online,
      downloaded,
      iptv,
      allowedProviderIds: item.Type === "Movie" ? movieIptv?.providerIds : undefined,
      iptvChecked: item.Type === "Series" ? iptvDone : item.Type === "Movie" ? movieDone : false,
      requestable: admin && iptvDone && tmdb > 0 && missing,
    });
  }, [item, online, downloaded, iptvSeries, movieIptv, rows, admin, iptvDone, movieDone, tmdb]);

  // Companion mix of library + IPTV titles; Seasons/Episodes ask for their series. Falls back to Jellyfin's list.
  const loadSimilar = useMemo(() => (item && item.Type !== "Episode" ? () => similar(item.Id) : null), [item]);
  const similarQuery = useMemo(() => {
    if (!item) return null;
    const isSeries = item.Type === "Series" || item.Type === "Season" || item.Type === "Episode";
    if (!isSeries && item.Type !== "Movie") return null;
    const own = item.Type === "Series" || item.Type === "Movie";
    const jfId = own ? item.Id : item.SeriesId;
    if (!jfId) return null;
    return { kind: isSeries ? ("series" as const) : ("movie" as const), tmdb: own && tmdb > 0 ? tmdb : null, jfId };
  }, [item, tmdb]);

  // Muted trailer in the backdrop after a rest, as on the TV; off while the full trailer modal is open
  const preview = useTrailerPreview(item && !showTrailer ? jfTarget(item) : null, { trigger: "passive" });
  // Taste signals: dwell on this page, a trailer opened on purpose (5 s+), My List
  const sigTarget = item ? libraryTarget(item) : null;
  useDetailDwell(sigTarget);
  const onTrailerTime = useTrailerWatch(showTrailer, sigTarget);

  if (error) return <div className="empty">Couldn't load this title. {error}</div>;
  if (!item) return <div className="details loading" />;

  const ud = item.UserData;
  const resumeTicks = ud?.PlaybackPositionTicks ?? 0;
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
      const tg = libraryTarget(item);
      if (tg && !ud?.IsFavorite) emitSignal({ ...tg, type: "mylist", at: Date.now() });
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
              // One button element while it waits and once it's ready, so focus taken while waiting stays on it
              (primary || !decided) && (
                <button
                  className="btn primary"
                  data-nav
                  data-autofocus
                  aria-disabled={primary ? undefined : true}
                  aria-busy={primary ? undefined : true}
                  onClick={() => primary && playSlot(primary.ep)}
                >
                  {primary ? `${primary.resume && primary.ep.inProgress ? "Resume" : "Play"} S${primary.ep.season} E${primary.ep.episode}` : "Play"}
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
            {getSession()?.isAdmin && ["Movie", "Series", "Season", "Episode"].includes(item.Type) && <button className="btn" data-nav onClick={() => go(`/manage?item=${item.Id}`)}>Manage</button>}
            {item.Type !== "Episode" && <TasteButtons item={item} />}
            {["Movie", "Series", "Episode"].includes(item.Type) && (
              <DeleteFromServer id={item.Id} afterDelete={item.Type === "Episode" ? () => go(`/item/${item.SeriesId}`, true) : undefined} />
            )}
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
        {(item.Type === "Movie" || item.Type === "Series" || item.Type === "Episode") && ways.length > 0 && (
          <WaysToWatch
            ways={ways}
            onAct={(w) => {
              if (w.kind === "request") setGetIt(true);
              else if (w.kind === "iptv" && w.iptvId) go(item.Type === "Series" ? vodPath(w.iptvId) : `${targetPath({ vod: w.iptvId })}&t=${encodeURIComponent(item.Name)}`);
              else if (item.Type === "Series") primary && playSlot(primary.ep);
              else go(`/play/${item.Id}`);
            }}
          />
        )}
        {item.Type === "Series" && seasonEntries.length > 0 && (
          <section className="row">
            <div className="chips">
              {seasonEntries.map((s) => (
                <button
                  key={s.key}
                  data-nav
                  className={`chip${s.key === season ? " on" : ""}`}
                  onClick={() => {
                    seasonPicked.current = true;
                    setSeason(s.key);
                    // Back to this page reopens the season he picked (?season=, as a link into it does)
                    const [path, q] = location.hash.split("?");
                    const u = new URLSearchParams(q ?? "");
                    u.set("season", s.key);
                    history.replaceState(history.state, "", `${path}?${u}`);
                  }}
                >
                  {s.name}
                </button>
              ))}
              <SeasonDownloadButton episodes={eps ?? []} onError={flash} />
              {canSeasons && season && iptvOnlyNo == null && <button className="chip" data-nav disabled={subtitleActive} onClick={() => void searchSeasonSubtitles([season])}>Search subtitles for this season</button>}
              {canSeasons && <button className="chip" data-nav disabled={subtitleActive} onClick={() => void searchSeasonSubtitles(seasonList.map((s) => s.Id))}>Search subtitles for all seasons</button>}
              {season && iptvOnlyNo == null && (
                <DeleteFromServer
                  id={season}
                  className="chip danger"
                  label="Delete this season from server"
                  afterDelete={() => {
                    const left = seasonList.filter((x) => x.Id !== season);
                    setSeasonList(left);
                    setSeason(left[0]?.Id ?? null);
                  }}
                />
              )}
            </div>
            {canSeasons && subtitleProgress && <p role="status" aria-live="polite" className="line dim">{resultText(subtitleProgress)}</p>}
            {phone ? (
              // Phone: a vertical list, still + name/runtime/overview side by side, the whole row is the tap target
              <div className="ep-list">
                {(rows ?? []).map((r) => {
                  const e = r.item;
                  // Always wrapped: the Next pill arriving toggles a class, never remounts a tile that may have focus
                  if (r.status !== "present" || !e)
                    return (
                      <div key={r.key} className={`ep-slot${isNextRow(r) ? " is-next" : ""}`}>
                        {ph(r)}
                        {isNextRow(r) && <span className="ep-next-pill">Next</span>}
                      </div>
                    );
                  return (
                    <div key={e.Id} className={`ep-row${isNextRow(r) ? " is-next" : ""}`}>
                      {isNextRow(r) && <span className="ep-next-pill">Next</span>}
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
                {(rows ?? []).map((r) => {
                  const tile = r.status !== "present" || !r.item ? ph(r) : <Card key={r.key} item={r.item} caption={`${r.item.IndexNumber ?? ""} · ${r.item.Name}`} />;
                  return (
                    <div key={r.key} className={`ep-slot${isNextRow(r) ? " is-next" : ""}`}>
                      {tile}
                      {isNextRow(r) && <span className="ep-next-pill">Next</span>}
                    </div>
                  );
                })}
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
        {similarQuery ? (
          <SimilarRow key={`sim-${item.Id}`} query={similarQuery} fallback={loadSimilar ?? (item.SeriesId ? () => similar(item.SeriesId!) : undefined)} />
        ) : (
          loadSimilar && <Row key={`sim-${item.Id}`} title="More like this" load={loadSimilar} />
        )}
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
            onTimeUpdate={onTrailerTime}
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
