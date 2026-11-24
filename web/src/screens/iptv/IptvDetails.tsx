// Details for an IPTV film or series (#/iptv/item?id=<pid>:m|s:<sid>[&season=N]): the library details page's scaffold
// (components/DetailsScaffold) fed from the VOD detail (parity #7, "the same irrespective of source"). Same hero, button
// order and sections; the source shows only as the "IPTV" badge. Slots IPTV has no data for (age rating, IMDb/RT/Letterboxd,
// reviews, chapters, extras) are left out. Plays go through the IPTV player, skipping a copy that failed lately.
import { useEffect, useState } from "react";
import { errorText, streamUrl, vodFavourite, vodItem } from "../../api/iptv";
import { dropPersonalCaches } from "../../api/companion";
import { iptvPlayable } from "../../lib/trailerPreview";
import { useTrailerPreview } from "../../lib/useTrailerPreview";
import { emitSignal, useDetailDwell, useTrailerWatch } from "../../api/signals";
import { safeArt } from "../../components/IptvTile";
import { tmdbSized } from "../../components/FranchiseRow";
import { useIsPhone } from "../../lib/device";
import { targetPath, type PlayTarget } from "../../lib/iptvPlay";
import { vodTech, type VodDetail, type VodEpisode } from "../../lib/iptvVod";
import { go } from "../../lib/router";
import { focusFirst } from "../../lib/spatial";
import { detailBadges } from "../../lib/techBadges";
import { SimilarRow } from "../../components/SimilarRow";
import { CARD_W } from "../../components/Card";
import { WaysToWatch } from "../../components/WaysToWatch";
import { AvailBadge } from "../../components/AvailBadge";
import { availabilityOf, type IptvCopy } from "../../lib/availability";
import { vodPath } from "../../lib/iptvVod";
import { flashNote, IptvTasteButtons } from "../../components/TasteButtons";
import { seedTaste, setIptvPlayed, type IptvTasteSubject } from "../../api/iptvTaste";
import { failedLately, failOf, pickPlayable, skippedNote } from "../../lib/streamFail";
import { alternateEpisode } from "../../lib/streamCopies";
import { pickKey, recallPick, rememberedTarget, rememberPick } from "../../lib/copyPick";
import { askedSeason, DetailsHero, HeroLogo, HeroOverview, PeopleRow, TrailerModal, useFrozenPrimary, type Person } from "../../components/DetailsScaffold";
import { epPlayed, iptvOpenSeason, iptvSeriesPrimary, loadRetryDelay, primaryLabel, resumeAt, vodMins, type IptvPrimary } from "../../lib/iptvSeries";
import { iptvResumeAt } from "../../lib/splitNext";
import "./iptv.css";

function playPath(t: PlayTarget, title: string, resume = 0) {
  return `${targetPath(t)}&t=${encodeURIComponent(title)}${resume ? `&resume=${resume}` : ""}`;
}

const markEp = (e: VodEpisode, played: boolean): VodEpisode => ({ ...e, played, progress: played ? { position: 0, duration: e.progress?.duration ?? 1, played: true } : null });

export function IptvDetails({ id }: { id: string }) {
  const [d, setD] = useState<VodDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [season, setSeason] = useState<number | null>(null);
  const phone = useIsPhone();
  // My list: null = the server does not offer it (no button)
  const [fav, setFav] = useState<boolean | null>(null);
  const [favBusy, setFavBusy] = useState(false);
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [watchedBusy, setWatchedBusy] = useState(false);
  const [trailerUrl, setTrailerUrl] = useState<string | null>(null);
  // Taste signals: dwell, a trailer opened on purpose (5 s+), My list
  const sigTarget = d ? { kind: d.kind, iptvId: d.id, ...(d.tmdbId ? { tmdbId: d.tmdbId } : {}) } : null;
  useDetailDwell(sigTarget);
  const onTrailerTime = useTrailerWatch(!!trailerUrl, sigTarget);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setD(null);
    setErr(null);
    setTrailerUrl(null);
    setOverviewOpen(false);
    // Only a 404 means the title is gone; errors and 5xx are tried again with backoff, then shown with Try again
    const load = (n: number) => {
      const failed = (status: number, text: string) => {
        if (!alive) return;
        const wait = loadRetryDelay(status, n);
        if (wait != null) timer = setTimeout(() => load(n + 1), wait);
        else setErr(text);
      };
      vodItem(id).then(
        (a) => {
          if (!alive) return;
          if (a.status === 200 && a.data) {
            // The copy this person picked for the title last time opens first (replace: Back does not bounce between the two pages)
            const to = rememberedTarget(recallPick(pickKey(a.data)), a.data.id, a.data.copies, Date.now() / 1000);
            if (to) return go(vodPath(to), true);
            setD(a.data);
            setFav(a.data.favourite ?? null);
            seedTaste({ id: a.data.id, kind: a.data.kind, tmdbId: a.data.tmdbId }, a.data.thumb, a.data.notInterested);
            setTimeout(() => alive && focusFirst(document.querySelector(".details") ?? document), 0);
          } else if (a.status === 404) setErr("This title is no longer on your IPTV provider.");
          else failed(a.status, errorText(a));
        },
        (e) => failed(0, String(e?.message ?? e)),
      );
    };
    load(0);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id, attempt]);

  // The series button: decided once per visit, from the first answer (IPTV has no server claim to wait for). Watched
  // toggles and later answers never relabel it, move the Next pill or switch the season under him.
  const { primary } = useFrozenPrimary<IptvPrimary>(id, !!d && d.kind === "series", () => iptvSeriesPrimary(d?.seasons));
  // Open on ?season= (a chip picked before Back), else the next episode's season
  useEffect(() => {
    if (d?.kind === "series" && season === null) setSeason(iptvOpenSeason(d.seasons, askedSeason(), primary));
  }, [d, season, primary]);

  // The companion's cached trailer plays muted in the backdrop after a rest; off while the Trailer modal is open
  const playable = d && !trailerUrl ? iptvPlayable(d.trailer, streamUrl) : null;
  const preview = useTrailerPreview(playable ? { kind: "direct", key: `iptv:${d?.id}`, url: playable } : null, { trigger: "passive" });

  if (err)
    return (
      <div className="details">
        <p className="empty" style={{ paddingTop: 120 }}>
          {err}
        </p>
        {err !== "This title is no longer on your IPTV provider." && (
          <p className="empty">
            <button className="btn" data-nav data-autofocus onClick={() => setAttempt((n) => n + 1)}>
              Try again
            </button>
          </p>
        )}
      </div>
    );
  if (!d) return <div className="details loading" />;

  const series = d.kind === "series";
  const eps = d.seasons?.find((s) => s.season === season)?.episodes ?? [];
  const taste: IptvTasteSubject = { id: d.id, kind: d.kind, tmdbId: d.tmdbId };
  // Specials (season 0) don't count towards a show being watched (the server ignores them too)
  const regular = (d.seasons ?? []).filter((s) => s.season > 0);
  const allEps = regular.flatMap((s) => s.episodes);
  // Film: its own flag. Series: every known episode (and at least one)
  const watched = series ? allEps.length > 0 && allEps.every(epPlayed) : !!(d.played || d.progress?.played);
  const toggleWatched = async () => {
    setWatchedBusy(true);
    const e = await setIptvPlayed(d.id, !watched);
    setWatchedBusy(false);
    if (e) return flashNote(e);
    const now = !watched;
    setD((cur) =>
      cur && cur.id === d.id
        ? {
            ...cur,
            played: now,
            progress: now ? cur.progress : null,
            seasons: cur.seasons?.map((s) => (s.season > 0 ? { ...s, episodes: s.episodes.map((x) => markEp(x, now)) } : s)),
          }
        : cur,
    );
    flashNote(now ? "Marked watched" : "Marked not watched");
  };
  const toggleEp = async (e: VodEpisode) => {
    const now = !epPlayed(e);
    const err = await setIptvPlayed(e.id, now, d.id);
    if (err) return flashNote(err);
    setD((cur) => (cur && cur.id === d.id ? { ...cur, seasons: cur.seasons?.map((s) => ({ ...s, episodes: s.episodes.map((x) => (x.id === e.id ? markEp(x, now) : x)) })) } : cur));
  };
  const toggleFav = async () => {
    setFavBusy(true);
    const a = await vodFavourite(d.id, !fav);
    setFavBusy(false);
    if (a.status === 404) return setFav(null);
    if (a.status === 200 || a.status === 201 || a.status === 204) {
      const now = typeof a.data?.favourite === "boolean" ? a.data.favourite : !fav;
      setFav(now);
      if (now) emitSignal({ type: "mylist", at: Date.now(), kind: d.kind, iptvId: d.id, ...(d.tmdbId ? { tmdbId: d.tmdbId } : {}) });
      dropPersonalCaches(); // Home's My list rows change
    } else flashNote("Couldn't update My list. Try again.");
  };
  const trailer = trailerTarget(d.trailer);
  const formats = detailBadges(vodTech(d));
  const resume = resumeAt(d.progress);
  const facts = [d.year, series ? null : vodMins(d.runtime), d.genre].filter(Boolean).join("  ·  ");
  const seasonCount = d.seasons?.length ?? 0;
  // Play skips a copy that failed in the last 24 h for one that did not (and says so); picking a copy by hand never does:
  // a copy opened by hand from "Ways to watch" (the remembered pick) is never skipped
  const manual = recallPick(pickKey(d)) === d.id;
  const playEp = async (s: number, e: VodEpisode, from = iptvResumeAt(e)) => {
    const title = `${d.title} · S${s} E${e.episode}`;
    const nowS = Date.now() / 1000;
    if (!manual && failedLately(failOf(e.id, e.lastFail), nowS)) {
      const alt = await alternateEpisode(
        { season: s, episode: e.episode },
        d.id,
        (d.copies ?? []).map((c) => ({ id: c.id, name: c.providerTitle ?? "another copy", lastFail: c.lastFail })),
        async (id) => (await vodItem(id)).data,
        (id, lf) => failedLately(failOf(id, lf), nowS),
      );
      if (alt) {
        flashNote(skippedNote(d.providerName ?? d.title));
        return go(playPath({ series: alt.copy.id, episode: alt.episode.id }, title, from));
      }
    }
    go(playPath({ series: d.id, episode: e.id }, title, from));
  };
  const playPrimary = () => primary?.ep.iptv && playEp(primary.ep.season, primary.ep.iptv);
  const playFilm = (from: number) => {
    const first = { id: d.id, name: d.providerName ?? d.title, lastFail: d.lastFail };
    if (manual) return go(playPath({ vod: d.id }, d.title, from));
    const p = pickPlayable(first, (d.copies ?? []).map((c) => ({ id: c.id, name: c.providerTitle ?? "another copy", lastFail: c.lastFail })), Date.now() / 1000);
    if (p.skipped) flashNote(skippedNote(p.skipped));
    go(playPath({ vod: p.chosen.id }, d.title, from));
  };
  // Every copy the companion returned (own + shared providers, switched-on or not), already ranked; this page's own copy is among them.
  // Older servers: this page's copy first, then their short list.
  const ranked = (d.copies ?? []).some((c) => c.lang != null);
  const copies: IptvCopy[] = ranked
    ? d.copies!.map((c) => ({ id: c.id, providerName: c.providerName ?? c.providerTitle, quality: c.quality, res: c.res, language: c.langLabel, opted: c.opted }))
    : [
        { id: d.id, providerName: d.providerName, quality: d.quality, tech: d.tech },
        ...(d.copies ?? []).filter((c) => c.id !== d.id).map((c) => ({ id: c.id, providerName: c.providerTitle, quality: c.quality })),
      ];
  const ways = availabilityOf(null, { iptv: copies, iptvChecked: true, iptvRanked: ranked });
  const people: Person[] = d.castList?.length
    ? d.castList.map((p, i) => ({ key: `${p.name}${i}`, name: p.name, role: p.character, img: safeArt(p.profile, 192) }))
    : (d.cast ?? "")
        .split(",")
        .map((n) => n.trim())
        .filter(Boolean)
        .map((name, i) => ({ key: `${name}${i}`, name }));

  const mins = vodMins;
  // Episode still 404s: fall back once to the show art, then hide the broken image
  const showArt = tmdbSized(safeArt(d.backdrop ?? d.poster, 480), "w780");
  const stillFallback = (ev: React.SyntheticEvent<HTMLImageElement>) => {
    const img = ev.currentTarget;
    if (showArt && img.dataset.fb !== "1" && img.src !== showArt) {
      img.dataset.fb = "1";
      img.src = showArt;
    } else img.style.visibility = "hidden";
  };

  return (
    <div className="details iptv-details">
      <DetailsHero url={tmdbSized(safeArt(d.backdrop ?? d.poster, 960), phone ? "w1280" : "original")} focal={null} preview={preview}>
        <HeroLogo url={safeArt(d.logo, 480)} name={d.title} owner={d.id} big />
        <div className="line info">
          {facts && <span>{facts}</span>}
          {series && seasonCount > 0 && (
            <span>
              {seasonCount} season{seasonCount > 1 ? "s" : ""}
            </span>
          )}
          {formats.map((b) => (
            <span key={b} className="badge">
              {b}
            </span>
          ))}
          {d.langUnknown && (
            <span className="badge lang-unknown" title="Couldn't confirm this copy's audio language">
              Language unknown
            </span>
          )}
          <AvailBadge badge="IPTV" />
        </div>
        {d.director && <div className="line dim">Directed by {d.director}</div>}
        <HeroOverview text={d.overview} open={overviewOpen} onToggle={() => setOverviewOpen((o) => !o)} />
        {d.rating ? (
          <div className="scores">
            <span className="score">★ {d.rating.toFixed(1)}</span>
          </div>
        ) : null}
        <div className="buttons">
          {series ? (
            primary?.ep.iptv && (
              <button className="btn primary" data-nav data-autofocus onClick={playPrimary}>
                {primaryLabel(primary)}
              </button>
            )
          ) : (
            <button className="btn primary" data-nav data-autofocus onClick={() => playFilm(resume)}>
              {resume ? `Resume ${vodMins(resume)}` : "Play"}
            </button>
          )}
          {!series && resume > 0 && (
            <button className="btn" data-nav onClick={() => playFilm(0)}>
              From start
            </button>
          )}
          {trailer && (
            <button className="btn" data-nav onClick={() => (trailer.inApp ? setTrailerUrl(trailer.url) : window.open(trailer.url, "_blank", "noopener,noreferrer"))}>
              Trailer
            </button>
          )}
          <button className="btn" data-nav disabled={watchedBusy} aria-pressed={watched} onClick={toggleWatched}>
            {watched ? "Watched ✓" : "Mark watched"}
          </button>
          {fav !== null && (
            <button className={`btn${fav ? " on" : ""}`} data-nav disabled={favBusy} aria-pressed={fav} onClick={toggleFav}>
              {fav ? "✓ In my list" : "+ My list"}
            </button>
          )}
          <IptvTasteButtons subject={taste} />
        </div>
      </DetailsHero>

      <div className="details-below">
        {ways.length > 0 && (
          <WaysToWatch
            ways={ways}
            currentIptvId={d.id}
            onAct={(w) => {
              if (!w.iptvId) return;
              rememberPick(pickKey(d), w.iptvId);
              if (w.iptvId !== d.id) go(vodPath(w.iptvId));
              else if (series) playPrimary();
              else playFilm(resume);
            }}
          />
        )}
      </div>
      {d.kind === "series" && (d.seasons?.length ?? 0) > 0 && (
        <div className="details-below">
          <section className="row">
            <div className="chips">
              {d.seasons!.map((s) => (
                <button key={s.season} data-nav className={`chip${s.season === season ? " on" : ""}`} onClick={() => setSeason(s.season)}>
                  Season {s.season}
                </button>
              ))}
            </div>
            {phone ? (
              <div className="ep-list iptv-eps">
                {eps.map((e) => {
                  const still = tmdbSized(safeArt(e.still ?? d.backdrop ?? d.poster, 480), "w780");
                  const done = epPlayed(e);
                  return (
                    <div key={e.id} className={`ep-row${done ? " done" : ""}`}>
                      <button className="ep-still" data-nav tabIndex={-1} aria-hidden onClick={() => playEp(season!, e)}>
                        {still && <img src={still} alt="" loading="lazy" decoding="async" onError={stillFallback} />}
                        <span className="ep-no">{e.episode}</span>
                      </button>
                      <button className="ep-text" data-nav onClick={() => playEp(season!, e)}>
                        <span className="ep-name">
                          {e.episode}. {e.title ?? `Episode ${e.episode}`}
                        </span>
                        <span className="ep-meta">{[mins(e.runtime), done ? "Watched" : resumeAt(e.progress) ? `${mins(resumeAt(e.progress))} in` : null].filter(Boolean).join("  ·  ")}</span>
                        {e.overview && <span className="ep-overview">{e.overview}</span>}
                      </button>
                      <button className={`ep-watch${done ? " on" : ""}`} data-nav aria-pressed={done} aria-label={`Watched: episode ${e.episode}`} title={done ? "Watched: tap to clear" : "Mark watched"} onClick={() => toggleEp(e)}>
                        ✓
                      </button>
                    </div>
                  );
                })}
              </div>
            ) : (
              // Wide screens: the library's sideways strip of 16:9 episode cards, ✓ toggle in the corner
              <div className="row-scroll iptv-ep-strip">
                {eps.map((e) => {
                  const still = tmdbSized(safeArt(e.still ?? d.backdrop ?? d.poster, 480), "w780");
                  const done = epPlayed(e);
                  const at = resumeAt(e.progress);
                  const pct = at && e.progress?.duration ? Math.min(100, (at / e.progress.duration) * 100) : 0;
                  const name = `${e.episode} · ${e.title ?? `Episode ${e.episode}`}`;
                  return (
                    <div key={e.id} className={`iptv-ep-card${done ? " done" : ""}`} style={{ width: CARD_W }}>
                      <button className="card" data-nav style={{ width: CARD_W, height: Math.round((CARD_W * 9) / 16) }} title={e.overview ?? name} aria-label={`Play ${name}`} onClick={() => playEp(season!, e)}>
                        {still && <img className="card-img in" src={still} alt="" loading="lazy" decoding="async" onError={stillFallback} />}
                        <div className="card-fade" />
                        <span className="card-title">{name}</span>
                        {pct > 0 && (
                          <span className="card-progress">
                            <span style={{ width: `${pct}%` }} />
                          </span>
                        )}
                      </button>
                      <div className="iptv-ep-meta">
                        <span>{[mins(e.runtime), done ? "Watched" : at ? `${mins(at)} in` : null].filter(Boolean).join("  ·  ")}</span>
                        <button className={`ep-watch${done ? " on" : ""}`} data-nav aria-pressed={done} aria-label={`Watched: ${name}`} title={done ? "Watched: tap to clear" : "Mark watched"} onClick={() => toggleEp(e)}>
                          ✓
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        </div>
      )}
      <div className="details-below">
        <PeopleRow people={people} />
        <SimilarRow key={`sim-${d.id}`} query={{ kind: d.kind, tmdb: d.tmdbId, iptvId: d.id }} />
      </div>
      {trailerUrl && (
        <TrailerModal
          src={trailerUrl}
          onClose={() => setTrailerUrl(null)}
          onTimeUpdate={onTrailerTime}
          onError={() => {
            setTrailerUrl(null);
            flashNote("The trailer won't play in this browser.");
          }}
        />
      )}
    </div>
  );
}

/** Trailer address: a direct video (companion-relative path or a video file) plays in a modal; any other web link opens in a tab. */
function trailerTarget(t?: string | null): { url: string; inApp: boolean } | null {
  if (!t) return null;
  if (/^https?:\/\//i.test(t)) return { url: t, inApp: /\.(mp4|webm|m3u8)(\?|$)/i.test(t) };
  if (t.startsWith("//") || /^[a-z][a-z0-9+.-]*:/i.test(t)) return null;
  return { url: streamUrl(t), inApp: true };
}
