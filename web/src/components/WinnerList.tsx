// A Polymarket winner market (F1 race winner, golf tournament winner): a ranked probability list with bars. Broadcast styling.
import { memo } from "react";
import type { Market } from "../lib/liveStats";
import { pct, volumeText, agoText } from "../lib/liveStats";
import { Odo } from "./Odo";
import "../screens/iptv/iptv.css";

const Row = memo(function Row({ rank, name, p, top, color }: { rank: number; name: string; p: number; top: number; color: string }) {
  return (
    <li className={`wl-row${rank === 1 ? " lead" : ""}`}>
      <b className="rk">{rank}</b>
      <span className="nm">{name}</span>
      <b className="pc">
        <Odo value={pct(p)} />
      </b>
      <i className="bar" style={{ width: `${Math.max(2, (p / Math.max(top, 0.01)) * 100)}%`, background: color }} aria-hidden />
    </li>
  );
});

export function WinnerList({ market, color, now }: { market: Market; color: string; now: number }) {
  const top = market.outcomes[0]?.p ?? 1;
  return (
    <>
      {market.question && <p className="iptv-hint">{market.question}</p>}
      <ol className="wl">
        {market.outcomes.map((o, i) => (
          <Row key={o.name} rank={i + 1} name={o.name} p={o.p} top={top} color={color} />
        ))}
      </ol>
      <p className="iptv-hint">{[market.source === "polymarket" ? "Polymarket" : market.source, volumeText(market.volume), agoText(market.updatedAt, now)].filter(Boolean).join(" · ")}</p>
    </>
  );
}
