// Details for an IPTV film or series (#/iptv/item?id=<pid>:m|s:<sid>): TMDB backdrop, title, facts, "via IPTV", Play /
// Resume through the IPTV player; series get season chips and an episode list.
import { useEffect, useState } from "react";
import { errorText, streamUrl, vodFavourite, vodItem } from "../../api/iptv";
import { dropPersonalCaches } from "../../api/companion";
import { Backdrop } from "../../components/Backdrop";
import { IptvDot, safeArt } from "../../components/IptvTile";
import { tmdbSized } from "../../components/FranchiseRow";
import { useIsPhone } from "../../lib/device";
import { targetPath, type PlayTarget } from "../../lib/iptvPlay";
import { vodTech, type VodDetail, type VodEpisode, type VodProgress } from "../../lib/iptvVod";
import { go } from "../../lib/router";
import { detailBadges } from "../../lib/techBadges";
import "./iptv.css";

const mins = (s?: number | null) => (s ? (s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.round((s % 3600) / 60)}m` : `${Math.round(s / 60)}m`) : null);
/** Seconds to resume from (none when finished or barely started). */
const resumeAt = (p?: VodProgress | null) => (p && !p.played && p.position > 30 ? Math.floor(p.position) : 0);

function playPath(t: PlayTarget, title: string, resume = 0) {
  return `${targetPath(t)}&t=${encodeURIComponent(title)}${resume ? `&resume=${resume}` : ""}`;
}

export function IptvDetails({ id }: { id: string }) {
  const [d, setD] = useState<VodDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [season, setSeason] = useState<number | null>(null);
  const phone = useIsPhone();
  // My list: null = the server does not offer it (no button)
  const [fav, setFav] = useState<boolean | null>(null);
  const [favBusy, setFavBusy] = useState(false);
  const [logoFailed, setLogoFailed] = useState(false);
  const [trailerUrl, setTrailerUrl] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setD(null);
    setErr(null);
    setLogoFailed(false);
    setTrailerUrl(null);
    vodItem(id).then((a) => {
      if (!alive) return;
      if (a.status === 200 && a.data) {
        setD(a.data);
        setFav(a.data.favourite ?? null);
        // Open on the season of the first unfinished episode
        const s = a.data.seasons?.find((x) => x.episodes.some((e) => !e.progress?.played)) ?? a.data.seasons?.[0];
        setSeason(s?.season ?? null);
      } else setErr(a.status === 404 ? "This title is no longer on your IPTV provider." : errorText(a));
    });
    return () => {
      alive = false;
    };
  }, [id]);

  if (err)
    return (
      <div className="details">
        <p className="empty" style={{ paddingTop: 120 }}>
          {err}
        </p>
      </div>
    );
  if (!d) return <div className="details loading" />;

  const eps = d.seasons?.find((s) => s.season === season)?.episodes ?? [];
  const nextEp: { s: number; e: VodEpisode } | null = (() => {
    for (const s of d.seasons ?? []) for (const e of s.episodes) if (!e.progress?.played) return { s: s.season, e };
    return null;
  })();
  const logo = safeArt(d.logo, 480);
  const trailer = trailerTarget(d.trailer);
  const formats = detailBadges(vodTech(d));
  const resume = resumeAt(d.progress);
  const facts = [d.year, mins(d.runtime), d.genre, d.rating ? `★ ${d.rating.toFixed(1)}` : null].filter(Boolean).join("  ·  ");
  const playEp = (s: number, e: VodEpisode, from = resumeAt(e.progress)) => go(playPath({ series: d.id, episode: e.id }, `${d.title} · S${s} E${e.episode}`, from));

  return (
    <div className="details iptv-details">
      <div className="details-hero">
        <Backdrop url={tmdbSized(safeArt(d.backdrop ?? d.poster, 960), phone ? "w1280" : "original")} focal={null} />
        <div className="scrim-left" />
        <div className="scrim-bottom" />
        <div className="details-text">
          {logo && !logoFailed ? (
            <img
              className="title-logo big"
              src={logo}
              alt={d.title}
              onError={() => setLogoFailed(true)}
              onLoad={(e) => {
                // Tall / narrow logos render tiny: typeset the title instead
                if (e.currentTarget.naturalHeight > e.currentTarget.naturalWidth * 0.6) setLogoFailed(true);
              }}
            />
          ) : (
            <h1 className="title-text big">{d.title}</h1>
          )}
          <div className="line info">
            {facts && <span>{facts}</span>}
            {formats.map((b) => (
              <span key={b} className="badge">
                {b}
              </span>
            ))}
          </div>
          <div className="line dim iptv-via">
            <IptvDot />
            via IPTV{d.providerName ? ` · ${d.providerName}` : ""}
          </div>
          {d.overview && <p className="overview">{d.overview}</p>}
          {d.director && <div className="line dim">Directed by {d.director}</div>}
          {d.cast && <div className="line dim">{d.cast.split(",").slice(0, 4).join(", ")}</div>}
          <div className="buttons">
            {d.kind === "series" ? (
              nextEp && (
                <button className="btn primary" data-nav data-autofocus onClick={() => playEp(nextEp.s, nextEp.e)}>
                  {resumeAt(nextEp.e.progress) ? "Resume" : "Play"} S{nextEp.s} E{nextEp.e.episode}
                </button>
              )
            ) : (
              <>
                <button className="btn primary" data-nav data-autofocus onClick={() => go(playPath({ vod: d.id }, d.title, resume))}>
                  {resume ? `Resume ${mins(resume)}` : "Play"}
                </button>
                {resume > 0 && (
                  <button className="btn" data-nav onClick={() => go(playPath({ vod: d.id }, d.title))}>
                    From start
                  </button>
                )}
              </>
            )}
            {trailer && (
              <button className="btn" data-nav onClick={() => (trailer.inApp ? setTrailerUrl(trailer.url) : window.open(trailer.url, "_blank", "noopener,noreferrer"))}>
                Trailer
              </button>
            )}
            {fav !== null && (
              <button
                className={`btn${fav ? " on" : ""}`}
                data-nav
                disabled={favBusy}
                aria-pressed={fav}
                onClick={async () => {
                  setFavBusy(true);
                  const a = await vodFavourite(d.id, !fav);
                  setFavBusy(false);
                  if (a.status === 404) return setFav(null);
                  if (a.status === 200 || a.status === 201 || a.status === 204) {
                    setFav(typeof a.data?.favourite === "boolean" ? a.data.favourite : !fav);
                    dropPersonalCaches(); // Home's My list rows change
                  }
                }}
              >
                {fav ? "✓ In my list" : "+ My list"}
              </button>
            )}
          </div>
        </div>
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
            <div className="ep-list iptv-eps">
              {eps.map((e) => (
                <div key={e.id} className="ep-row">
                  <button className="ep-text" data-nav onClick={() => playEp(season!, e)}>
                    <span className="ep-name">
                      {e.episode}. {e.title ?? `Episode ${e.episode}`}
                    </span>
                    <span className="ep-meta">
                      {[mins(e.runtime), e.progress?.played ? "Watched" : resumeAt(e.progress) ? `${mins(resumeAt(e.progress))} in` : null].filter(Boolean).join("  ·  ")}
                    </span>
                    {e.overview && <span className="ep-overview">{e.overview}</span>}
                  </button>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}
      {(d.castList?.length ?? 0) > 0 && (
        <div className="details-below">
          <section className="row">
            <h2 className="row-title">Cast</h2>
            <div className="row-scroll">
              {d.castList!.map((p, i) => {
                const img = safeArt(p.profile, 192);
                return (
                  <div key={`${p.name}${i}`} className="person">
                    {img ? <img src={img} alt="" loading="lazy" /> : <div className="person-blank" />}
                    <div className="person-text">
                      <span>{p.name}</span>
                      {p.character && <span className="dim">{p.character}</span>}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        </div>
      )}
      {trailerUrl && (
        <div className="trailer-modal" onClick={() => setTrailerUrl(null)}>
          <video src={trailerUrl} autoPlay controls onEnded={() => setTrailerUrl(null)} onError={() => setTrailerUrl(null)} onClick={(e) => e.stopPropagation()} />
        </div>
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
