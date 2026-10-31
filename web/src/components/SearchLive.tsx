// Search rows for live TV (plan: ~/ai/plans/jellytip-search-10x.md "E. Search covers live channels and events"): "Live & upcoming"
// (phase pill, channel logo, quality, matchup; OK plays, or offers Remind me while it is still ahead; ⓘ opens match info) and
// "Channels" (OK tunes). Each row is hidden when the server did not send it.
import { useState } from "react";
import { logoSrc } from "../api/iptv";
import { addReminderFor } from "../api/reminderStore";
import { livePath, type LiveExtras } from "../lib/iptvPlay";
import { displayTitle } from "../lib/matchInfo";
import { isPre } from "../lib/preGame";
import { go } from "../lib/router";
import { livePill, type ChannelHit, type LiveHit } from "../lib/search";
import { timeChip } from "../lib/upcoming";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { IdentifiedMark, MatchInfoButton } from "./MatchInfo";
import { flashNote } from "./TasteButtons";
import { SportBadges } from "./SportBadges";
import "../screens/iptv/iptv.css";

const extrasOf = (h: LiveHit, now: number): LiveExtras => ({
  startsAt: isPre(h.startsAt, now, h.phase) ? h.startsAt : null,
  eventKey: h.fixture?.id ?? null,
  matchId: h.fixture?.id ?? null,
  alternates: h.alternates,
});

function ChannelLogo({ logo, name }: { logo: string | null; name: string }) {
  const src = logoSrc(logo, 96);
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <span className="onnow-logo blank">{name.replace(/^[A-Z]{2,3}\s*[|:-]\s*/, "").slice(0, 2)}</span>;
  return <img className="onnow-logo" src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}

export function LiveRow({ hits, title = "Live & upcoming" }: { hits: LiveHit[]; title?: string }) {
  const [ahead, setAhead] = useState<LiveHit | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEscapeClose(() => setAhead(null), !!ahead);
  if (!hits.length) return null;
  const now = Date.now() / 1000;
  const open = (h: LiveHit) => {
    // Still ahead and not within the pre-game window: offer a reminder instead of an empty channel
    if (h.start > now && !isPre(h.startsAt, now, h.phase)) {
      setMsg(null);
      return setAhead(h);
    }
    go(livePath(h.channel.id, h.channel.name, extrasOf(h, now)));
  };
  return (
    <section className="row onnow-row search-live">
      <h2 className="row-title">{title}</h2>
      <div className="row-scroll">
        {hits.map((h) => {
          const pill = livePill(h, now);
          return (
            <div key={h.key} className="onnow-item">
              <button className="onnow-card" data-nav onClick={() => open(h)} aria-label={`${displayTitle(h.title, h.fixture)}, ${pill.text}, ${h.channel.name}`} title={`${h.title} · ${h.channel.name}`}>
                <span className="onnow-top">
                  <ChannelLogo logo={h.channel.logo} name={h.channel.name} />
                  <span className={`sport-pill ${pill.tone === "rec" ? "live" : pill.tone === "dim" ? "replay" : pill.tone === "pre" ? "pre" : ""}`}>{pill.text}</span>
                </span>
                <span className="onnow-title">
                  {displayTitle(h.title, h.fixture)}
                  {h.fixture?.inferred && <IdentifiedMark />}
                </span>
                <span className="onnow-channel">{[h.league ?? h.fixture?.league, h.channel.name].filter(Boolean).join(" · ")}</span>
                <SportBadges quality={h.channel.quality} size="sm" />
              </button>
              <MatchInfoButton fixture={h.fixture} className="info-fab" />
            </div>
          );
        })}
      </div>
      {ahead && (
        <div className="livetv-sheet-back" onClick={() => setAhead(null)}>
          <div className="livetv-sheet" role="dialog" aria-label={ahead.title} onClick={(e) => e.stopPropagation()}>
            <SheetClose onClose={() => setAhead(null)} />
            <p className="iptv-hint">{[ahead.league ?? ahead.fixture?.league, ahead.channel.name, timeChip({ start: ahead.start, stop: ahead.stop })].filter(Boolean).join(" · ")}</p>
            <h2>{displayTitle(ahead.title, ahead.fixture)}</h2>
            {msg && <p className="iptv-hint">{msg}</p>}
            <div className="buttons">
              <button
                className="btn primary"
                data-nav
                data-autofocus
                onClick={async () => {
                  const err = await addReminderFor({ key: ahead.fixture?.id ?? `${ahead.channel.id}:${ahead.start}`, title: displayTitle(ahead.title, ahead.fixture), start: ahead.startsAt ?? ahead.start, stop: ahead.stop, channelId: ahead.channel.id, channelName: ahead.channel.name });
                  if (err) setMsg(err);
                  else {
                    flashNote("We'll remind you before it starts");
                    setAhead(null);
                  }
                }}
              >
                Remind me
              </button>
              <button className="btn" data-nav onClick={() => (setAhead(null), go(livePath(ahead.channel.id, ahead.channel.name, extrasOf(ahead, now))))}>
                Open channel
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

export function ChannelsRow({ hits }: { hits: ChannelHit[] }) {
  if (!hits.length) return null;
  return (
    <section className="row onnow-row search-channels">
      <h2 className="row-title">Channels</h2>
      <div className="row-scroll">
        {hits.map((c) => (
          <div key={c.id} className="onnow-item">
            <button className="onnow-card chan-card" data-nav onClick={() => go(livePath(c.id, c.name))} aria-label={`Tune to ${c.name}`} title={c.name}>
              <span className="onnow-top">
                <ChannelLogo logo={c.logo} name={c.name} />
                {c.now?.phase === "pre" ? <span className="sport-pill pre">PRE-GAME</span> : c.now ? <span className="onnow-live">On now</span> : null}
              </span>
              <span className="onnow-title">{c.name}</span>
              <span className="onnow-channel">{c.now?.title ?? c.group ?? ""}</span>
              <SportBadges quality={c.quality} size="sm" />
            </button>
          </div>
        ))}
      </div>
    </section>
  );
}
