// Live-sport badges for a programme / channel: red LIVE, grey REPLAY, a sport icon, the stream's quality and "may buffer".
// Shows nothing for fields the server did not send.
import { kindPill, mayBuffer, qualityLabel, type Kind, type Quality } from "../lib/sport";

export function SportIcon() {
  return (
    <svg className="sport-icon" viewBox="0 0 24 24" aria-label="Sport" role="img">
      <circle cx="12" cy="12" r="9" />
      <path d="M3.5 9.5c4 1.2 7 1.2 17 0M3.5 14.5c4-1.2 7-1.2 17 0M12 3v18" />
    </svg>
  );
}

export function SportBadges({ kind, sport, quality, size = "md" }: { kind?: Kind | null; sport?: boolean; quality?: Quality | null; size?: "sm" | "md" }) {
  const pill = kindPill(kind);
  const q = qualityLabel(quality ?? null);
  const buffer = mayBuffer(quality ?? null);
  if (!pill && !sport && !q && !buffer) return null;
  return (
    <span className={`sport-badges ${size}`}>
      {pill && <span className={`sport-pill ${pill.tone}`}>{pill.text}</span>}
      {sport && <SportIcon />}
      {q && <span className="sport-q">{q}</span>}
      {buffer && <span className="sport-buffer">may buffer</span>}
    </span>
  );
}
