// Quiet one-line scores with small source marks, as on the TV (JellytipSourceMarks.kt / scoreLine):
// "[IMDb] 8.1 · [tomato] 95% · [popcorn] 89% · [MC] · [LB] 3.9/5 · [Trakt] 74% · [LB] You ★4½/5"
import type { Extras, LetterboxdMine, Score } from "../api/companion";

const H = "0.95em";

function Imdb() {
  return (
    <svg className="mark" viewBox="0 0 43 16" style={{ height: H }} aria-label="IMDb">
      <rect width="43" height="16" rx="3" fill="#F5C518" />
      <text x="21.5" y="12" textAnchor="middle" fontSize="10.5" fontWeight="900" fill="#000" fontFamily="Arial, sans-serif">
        IMDb
      </text>
    </svg>
  );
}

function Tomato({ fresh }: { fresh: boolean }) {
  return fresh ? (
    <svg className="mark" viewBox="0 0 16 16" style={{ height: H }} aria-label="Rotten Tomatoes fresh">
      <circle cx="8" cy="9.3" r="7" fill="#FA320A" />
      <path d="M8 4.8 L4.8 1.9 L7.4 3.2 L8 0.3 L9 3.2 L11.5 1.6 Z" fill="#00912D" />
    </svg>
  ) : (
    <svg className="mark" viewBox="0 0 16 16" style={{ height: H }} aria-label="Rotten Tomatoes rotten">
      <circle cx="8" cy="8.3" r="4.8" fill="#0AC855" />
      {Array.from({ length: 8 }, (_, i) => {
        const a = ((i * 45 + 12) * Math.PI) / 180;
        const len = i % 2 === 0 ? 6.4 : 5.3;
        return <circle key={i} cx={8 + Math.cos(a) * len} cy={8.3 + Math.sin(a) * len} r="2.1" fill="#0AC855" />;
      })}
    </svg>
  );
}

function Popcorn({ upright }: { upright: boolean }) {
  return (
    <svg className="mark" viewBox="0 0 16 16" style={{ height: H }} aria-label="Audience score">
      <g transform={upright ? undefined : "rotate(-32 8 14.4)"}>
        <path d="M2.2 6.1 H13.8 L11.8 15.7 H4.2 Z" fill="#FA320A" />
        <rect x="7" y="6.1" width="2" height="9.6" fill="#fff" />
        {[
          [4.2, 4.8],
          [7.4, 3.5],
          [10.6, 4.8],
          [5.8, 5.8],
          [9.3, 5.8],
        ].map(([x, y], i) => (
          <circle key={i} cx={x} cy={y} r="2.4" fill="#FFE08A" />
        ))}
      </g>
    </svg>
  );
}

function Metacritic({ score }: { score: number }) {
  const bg = score >= 61 ? "#66CC33" : score >= 40 ? "#FFCC33" : "#FF0000";
  return (
    <svg className="mark" viewBox="0 0 22 16" style={{ height: H }} aria-label={`Metacritic ${score}`}>
      <rect width="22" height="16" rx="2.6" fill={bg} />
      <text x="11" y="12.2" textAnchor="middle" fontSize="11.5" fontWeight="900" fill="#000" fontFamily="Arial, sans-serif">
        {score}
      </text>
    </svg>
  );
}

function Letterboxd() {
  return (
    <svg className="mark" viewBox="0 0 34 16" style={{ height: H }} aria-label="Letterboxd">
      <circle cx="8" cy="8" r="7" fill="#FF8000" />
      <circle cx="17" cy="8" r="7" fill="#00E054" fillOpacity="0.92" />
      <circle cx="26" cy="8" r="7" fill="#40BCF4" fillOpacity="0.92" />
    </svg>
  );
}

function Trakt() {
  return (
    <svg className="mark" viewBox="0 0 16 16" style={{ height: H }} aria-label="Trakt">
      <circle cx="8" cy="8" r="8" fill="#ED1C24" />
      <path d="M4.5 8.3 L7.2 10.9 L12 5.4" stroke="#fff" strokeWidth="2.1" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const fmt = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(1));

function Chip({ s }: { s: Score }) {
  const v = s.value;
  switch (s.source) {
    case "imdb":
      return (
        <>
          <Imdb /> {fmt(v)}
        </>
      );
    case "rt_critic":
      return (
        <>
          <Tomato fresh={v >= 60} /> {fmt(v)}%
        </>
      );
    case "rt_audience":
      return (
        <>
          <Popcorn upright={v >= 60} /> {fmt(v)}%
        </>
      );
    case "metacritic":
      return <Metacritic score={Math.round(Math.min(Math.max(v, 0), 100))} />;
    case "letterboxd": {
      const five = s.max && s.max > 0 ? (v / s.max) * 5 : v;
      return (
        <>
          <Letterboxd /> {five.toFixed(1)}/5
        </>
      );
    }
    case "trakt":
      return (
        <>
          <Trakt /> {fmt(v)}%
        </>
      );
    default:
      return (
        <>
          {s.label} {fmt(v)}
        </>
      );
  }
}

function stars(r: number): string {
  const whole = Math.floor(r);
  const half = r - whole >= 0.25;
  return whole === 0 && half ? "½" : half ? `${whole}½` : `${whole}`;
}

function Mine({ lb }: { lb: LetterboxdMine }) {
  const parts: string[] = [];
  if (lb.myRating != null) parts.push(`You ★${stars(lb.myRating)}/5`);
  if (lb.liked) parts.push("♥");
  if (!parts.length) return null;
  return (
    <span className="mine">
      {lb.myRating != null && <Letterboxd />} {parts.join("  ")}
    </span>
  );
}

export function ScoreLine({ extras }: { extras: Extras | null }) {
  if (!extras) return null;
  // Same first four the TV shows, plus Letterboxd / Trakt when present
  const scores = extras.scores.slice(0, 6);
  const mine = extras.letterboxd && (extras.letterboxd.myRating != null || extras.letterboxd.liked) ? extras.letterboxd : null;
  if (!scores.length && !mine) return null;
  return (
    <div className="scores">
      {scores.map((s, i) => (
        <span key={s.source + i} className="score">
          {i > 0 && <span className="dot">·</span>}
          <Chip s={s} />
        </span>
      ))}
      {mine && (
        <span className="score">
          {scores.length > 0 && <span className="dot">·</span>}
          <Mine lb={mine} />
        </span>
      )}
    </div>
  );
}
