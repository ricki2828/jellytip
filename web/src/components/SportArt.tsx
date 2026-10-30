// Real sport artwork behind a live tile (the league's fanart, dimmed) with the home v away badges, else the league
// badge. Absent or failing art draws nothing, leaving today's tile. Everything loads through the companion's art proxy.
import { useState } from "react";
import { logoSrc } from "../api/iptv";
import type { SportArt as Art } from "../lib/sport";

/** Class to put on the tile so the artwork sits behind its text. */
export const artClass = (art: Art | null | undefined) => (art && (art.fanart || art.home || art.away || art.leagueBadge) ? " has-art" : "");

function Badge({ path, name }: { path: string; name: string }) {
  const [failed, setFailed] = useState(false);
  const src = logoSrc(path, 96);
  if (!src || failed) return null;
  return <img className="art-badge" src={src} alt={name} title={name} loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}

export function SportArt({ art }: { art: Art | null | undefined }) {
  const [bgFailed, setBgFailed] = useState(false);
  if (!art) return null;
  const bg = !bgFailed ? logoSrc(art.fanart, 480) : null;
  const both = art.home && art.away;
  return (
    <>
      {bg && <img className="art-bg" src={bg} alt="" loading="lazy" decoding="async" onError={() => setBgFailed(true)} />}
      {(both || art.leagueBadge) && (
        <span className="art-badges" aria-hidden={!both}>
          {both ? (
            <>
              <Badge path={art.home!.badge} name={art.home!.name} />
              <span className="art-v">v</span>
              <Badge path={art.away!.badge} name={art.away!.name} />
            </>
          ) : (
            <Badge path={art.leagueBadge!} name="" />
          )}
        </span>
      )}
    </>
  );
}
