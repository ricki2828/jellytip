// "On now": channels airing something right now (live sport first), as wide cards — logo, programme, progress,
// LIVE. Click plays the channel. Top of Live TV; on Home as "Live sport on now" (sport only, ≥ 2 live, else hidden).
import { useEffect, useState } from "react";
import { hasLiveTv, logoSrc, onNow } from "../api/iptv";
import { progressOf, type OnNow } from "../lib/onNow";
import { afterStartup } from "../lib/startup";
import { targetPath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import { MuteButton } from "./SportMute";
import { MUTED_EVENT } from "../api/sportTaste";
import { SportBadges } from "./SportBadges";
import { artClass, SportArt } from "./SportArt";

const REFRESH_MS = 60_000;

function Logo({ o }: { o: OnNow }) {
  const src = logoSrc(o.logo, 96);
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <span className="onnow-logo blank">{o.channel.replace(/^[A-Z]{2,3}\s*[|:-]\s*/, "").slice(0, 2)}</span>;
  return <img className="onnow-logo" src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}

export function OnNowStrip({ title, sportOnly = false, min = 1, checkProvider = false }: { title: string; sportOnly?: boolean; min?: number; checkProvider?: boolean }) {
  const [items, setItems] = useState<OnNow[] | null>(null);
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    let alive = true;
    let t: number | undefined;
    const load = async () => {
      // Home: after the page's own rows and art have started
      if (checkProvider) await afterStartup();
      if (!alive) return;
      if (checkProvider && !(await hasLiveTv())) {
        clearInterval(t);
        return alive && setItems([]);
      }
      const list = await onNow(sportOnly ? 20 : 30, sportOnly);
      if (!alive) return;
      setItems(sportOnly ? list.filter((o) => o.sport) : list);
      setNow(Date.now() / 1000);
    };
    // No provider (no Live TV): stop asking
    t = window.setInterval(load, REFRESH_MS);
    void load();
    // Something was muted or unmuted: look again now
    const onMuted = () => void load();
    window.addEventListener(MUTED_EVENT, onMuted);
    return () => {
      window.removeEventListener(MUTED_EVENT, onMuted);
      alive = false;
      clearInterval(t);
    };
  }, [sportOnly, checkProvider]);

  const [openAlt, setOpenAlt] = useState<string | null>(null);
  const live = (items ?? []).filter((o) => o.stop > now);
  if (live.length < min) return null;
  const play = (id: string, name: string) => go(`${targetPath({ channel: id })}&t=${encodeURIComponent(name)}`);
  // Newer servers send names and quality for each alternate; older ones only ids (names then come from the list, if there)
  const alts = (o: OnNow) =>
    o.info.alternateChannels.length
      ? o.info.alternateChannels
      : o.info.alternates.map((id) => ({ id, name: (items ?? []).find((x) => x.channelId === id)?.channel ?? "Another channel", num: null, logo: null, quality: null }));
  return (
    <section className="row onnow-row">
      <h2 className="row-title">{title}</h2>
      <div className="row-scroll">
        {live.map((o) => (
          <div key={o.channelId} className="onnow-item">
            <MuteButton
              target={{ title: o.title, league: o.info.league, sportName: o.info.sportName }}
              className={`onnow-card${artClass(o.info.art)}`}
              data-nav
              aria-label={`${o.title} on ${o.channel}, ${o.info.kind === "replay" || o.info.kind === "highlights" ? "replay" : "live"}`}
              title={`${o.title} · ${o.channel}`}
              onClick={() => play(o.channelId, o.channel)}
            >
              <SportArt art={o.info.art} />
              <span className="onnow-top">
                <Logo o={o} />
                {o.info.kind ? <SportBadges kind={o.info.kind} sport={o.sport} size="sm" /> : <span className="onnow-live">Live</span>}
              </span>
              <span className="onnow-title">
                {o.info.match && (o.info.match.team || o.info.match.league) && <span className="sport-star" title={`Your ${o.info.match.team ? "team" : "league"}: ${o.info.match.team ?? o.info.match.league}`}>★ </span>}
                {o.title}
              </span>
              <span className="onnow-channel">{o.channel}</span>
              <SportBadges quality={o.info.quality} size="sm" />
              <span className="onnow-progress" aria-hidden>
                <span style={{ width: `${progressOf(o, now) * 100}%` }} />
              </span>
            </MuteButton>
            {alts(o).length > 0 && (
              <>
                <button className="onnow-also" data-nav aria-expanded={openAlt === o.channelId} onClick={() => setOpenAlt(openAlt === o.channelId ? null : o.channelId)}>
                  Also on {alts(o).length}
                </button>
                {openAlt === o.channelId && (
                  <div className="onnow-alts">
                    {alts(o).map((a) => (
                      <button key={a.id} data-nav onClick={() => play(a.id, a.name)}>
                        <Logo o={{ ...o, logo: a.logo, channel: a.name }} />
                        <span className="onnow-alt-name">{a.name}</span>
                        <SportBadges quality={a.quality} size="sm" />
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}
