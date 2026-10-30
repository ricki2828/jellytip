// Upcoming sport: a tile, the overlay it opens (matchup, time, channels best first with quality, Record, Watch when
// live) and the Home row. The page (screens/iptv/UpcomingPage) reuses the tile and overlay.
import { useState } from "react";
import { addRecording, errorText, logoSrc } from "../api/iptv";
import { refreshRecs } from "../api/recStore";
import { useUpcoming, refreshUpcoming } from "../api/upcomingStore";
import { targetPath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import { isLive, timeChip, type UpChannel, type UpEvent } from "../lib/upcoming";
import { SportBadges } from "./SportBadges";
import "../screens/iptv/iptv.css";

export function UpTile({ e, onOpen }: { e: UpEvent; onOpen: () => void }) {
  const live = isLive(e);
  const best = e.channels[0];
  return (
    <button className={`up-tile${live ? " live" : ""}`} data-nav onClick={onOpen} aria-label={`${e.title}, ${timeChip(e)}`} title={e.title}>
      <span className="up-top">
        <span className={`rec-chip ${live ? "rec" : "time"}`}>{timeChip(e)}</span>
        {e.favourite && <span className="sport-star" title="Your team or league">★</span>}
      </span>
      <span className="rec-title">{e.title}</span>
      <span className="rec-sub">{[e.league, best?.name].filter(Boolean).join(" · ")}</span>
      <SportBadges quality={best?.quality} size="sm" />
    </button>
  );
}

function ChannelLine({ c, live, onWatch }: { c: UpChannel; live: boolean; onWatch: () => void }) {
  const src = logoSrc(c.logo, 96);
  return (
    <li className="up-chan">
      {src ? <img className="onnow-logo" src={src} alt="" loading="lazy" /> : <span className="onnow-logo blank">{c.name.slice(0, 2)}</span>}
      <span className="up-chan-name">{c.name}</span>
      <SportBadges quality={c.quality} size="sm" />
      {live && (
        <button className="btn small primary" data-nav onClick={onWatch}>
          Watch
        </button>
      )}
    </li>
  );
}

export function UpcomingSheet({ e, onClose }: { e: UpEvent; onClose: () => void }) {
  const [msg, setMsg] = useState<string | null>(null);
  const [recorded, setRecorded] = useState(e.recordingId != null);
  const live = isLive(e);
  const best = e.channels[0];
  const when = `${timeChip(e)} · ${new Date(e.start * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}–${new Date(e.stop * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`;
  const record = async () => {
    const a = await addRecording(best.id, e.start);
    if (a.status === 200 || a.status === 201) {
      setRecorded(true);
      setMsg(a.data?.warning || "Recording scheduled. It'll appear on Home.");
      void refreshRecs(true);
      void refreshUpcoming(true);
    } else setMsg(errorText(a));
  };
  return (
    <div className="livetv-sheet-back" onClick={onClose}>
      <div className="livetv-sheet" role="dialog" aria-label={e.title} onClick={(ev) => ev.stopPropagation()}>
        <p className="iptv-hint">
          {e.favourite && <span className="sport-star">★ </span>}
          {[e.league, when].filter(Boolean).join(" · ")}
        </p>
        <h2>{e.title}</h2>
        <ul className="up-chans">
          {e.channels.map((c) => (
            <ChannelLine key={c.id} c={c} live={live} onWatch={() => go(`${targetPath({ channel: c.id })}&t=${encodeURIComponent(c.name)}`)} />
          ))}
        </ul>
        {msg && <p className="iptv-hint">{msg}</p>}
        <div className="buttons">
          {live && (
            <button className="btn primary" data-nav data-autofocus onClick={() => go(`${targetPath({ channel: best.id })}&t=${encodeURIComponent(best.name)}`)}>
              Watch now
            </button>
          )}
          {e.recordable && !recorded && e.stop > Date.now() / 1000 && (
            <button className={`btn${live ? "" : " primary"}`} data-nav data-autofocus={!live || undefined} onClick={() => void record()}>
              Record
            </button>
          )}
          {recorded && (
            <button className="btn" data-nav onClick={() => go("/recordings")}>
              Recording set · View
            </button>
          )}
          <button className="btn" data-nav onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/** Home: "Coming up in sport", the server's order (favourites first); hidden when there is nothing or no route. */
export function UpcomingRow() {
  const ev = useUpcoming();
  const [open, setOpen] = useState<UpEvent | null>(null);
  const now = Date.now() / 1000;
  const list = (ev ?? []).filter((e) => e.stop > now).slice(0, 14);
  if (!list.length) return null;
  return (
    <section className="row up-row">
      <h2 className="row-title">
        <button className="row-title-link" data-nav onClick={() => go("/upcoming")}>
          Coming up in sport
          <span className="row-title-more" aria-hidden>
            ›
          </span>
        </button>
      </h2>
      <div className="row-scroll">
        {list.map((e) => (
          <UpTile key={e.key} e={e} onOpen={() => setOpen(e)} />
        ))}
      </div>
      {open && <UpcomingSheet e={open} onClose={() => setOpen(null)} />}
    </section>
  );
}
