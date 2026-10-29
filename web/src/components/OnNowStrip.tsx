// "On now": channels airing something right now (live sport first), as wide cards — logo, programme, progress,
// LIVE. Click plays the channel. Top of Live TV; on Home as "Live sport on now" (sport only, ≥ 2 live, else hidden).
import { useEffect, useState } from "react";
import { hasLiveTv, logoSrc, onNow } from "../api/iptv";
import { progressOf, type OnNow } from "../lib/onNow";
import { targetPath } from "../lib/iptvPlay";
import { go } from "../lib/router";

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
    const load = async () => {
      if (checkProvider && !(await hasLiveTv())) return alive && setItems([]);
      const list = await onNow(sportOnly ? 40 : 30);
      if (!alive) return;
      setItems(sportOnly ? list.filter((o) => o.sport) : list);
      setNow(Date.now() / 1000);
    };
    void load();
    const t = setInterval(load, REFRESH_MS);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [sportOnly, checkProvider]);

  const live = (items ?? []).filter((o) => o.stop > now);
  if (live.length < min) return null;
  return (
    <section className="row onnow-row">
      <h2 className="row-title">{title}</h2>
      <div className="row-scroll">
        {live.map((o) => (
          <button
            key={o.channelId}
            className="onnow-card"
            data-nav
            aria-label={`${o.title} on ${o.channel}, live`}
            title={`${o.title} · ${o.channel}`}
            onClick={() => go(`${targetPath({ channel: o.channelId })}&t=${encodeURIComponent(o.channel)}`)}
          >
            <span className="onnow-top">
              <Logo o={o} />
              <span className="onnow-live">Live</span>
            </span>
            <span className="onnow-title">{o.title}</span>
            <span className="onnow-channel">{o.channel}</span>
            <span className="onnow-progress" aria-hidden>
              <span style={{ width: `${progressOf(o, now) * 100}%` }} />
            </span>
          </button>
        ))}
      </div>
    </section>
  );
}
