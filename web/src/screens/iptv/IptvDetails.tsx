// Details for an IPTV film or series (#/iptv/item?id=<pid>:m|s:<sid>): TMDB backdrop, title, facts, "via IPTV", Play /
// Resume through the IPTV player; series get season chips and an episode list.
import { useEffect, useState } from "react";
import { errorText, vodFavourite, vodItem } from "../../api/iptv";
import { dropPersonalCaches } from "../../api/companion";
import { Backdrop } from "../../components/Backdrop";
import { IptvDot } from "../../components/IptvTile";
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

  useEffect(() => {
    let alive = true;
    setD(null);
    setErr(null);
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
  const formats = detailBadges(vodTech(d));
  const resume = resumeAt(d.progress);
  const facts = [d.year, mins(d.runtime), d.genre, d.rating ? `★ ${d.rating.toFixed(1)}` : null].filter(Boolean).join("  ·  ");
  const playEp = (s: number, e: VodEpisode, from = resumeAt(e.progress)) => go(playPath({ series: d.id, episode: e.id }, `${d.title} · S${s} E${e.episode}`, from));

  return (
    <div className="details iptv-details">
      <div className="details-hero">
        <Backdrop url={tmdbSized(d.backdrop && /^https:\/\/image\.tmdb\.org\//.test(d.backdrop) ? d.backdrop : null, phone ? "w1280" : "original")} focal={null} />
        <div className="scrim-left" />
        <div className="scrim-bottom" />
        <div className="details-text">
          <h1 className="title-text big">{d.title}</h1>
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
    </div>
  );
}
