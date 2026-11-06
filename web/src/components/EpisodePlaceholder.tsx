// A season-list row for an episode with no file: "Not in your library", "Airs 12 Dec" or "Available via IPTV".
import { reasonDetail, reasonLabel, type EpRow } from "../lib/seasonEpisodes";
import "./EpisodePlaceholder.css";

export function EpisodePlaceholder({
  row,
  now,
  onIptv,
  onRequest,
}: {
  row: EpRow;
  now: Date;
  onIptv?: (iptvId: string) => void;
  onRequest?: () => void;
}) {
  const why = reasonLabel(row, now);
  return (
    <div className={`ep-ph ep-ph-${row.status}`} data-testid="ep-placeholder">
      <span className="ep-ph-name">
        {row.index || ""}. {row.name}
      </span>
      <span className="ep-ph-why">{why}</span>
      {reasonDetail(row, now) && (
        <span className="ep-ph-why dim">{reasonDetail(row, now)}</span>
      )}
      {row.iptv && onIptv && (
        <button className="btn" data-nav onClick={() => onIptv(row.iptv!.id)}>
          Play via IPTV
        </button>
      )}
      {row.status === "missing" && !row.iptv && onRequest && (
        <button className="btn" data-nav onClick={onRequest}>
          Request
        </button>
      )}
    </div>
  );
}
