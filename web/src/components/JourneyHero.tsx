// Journeys v1 pieces shared by #/journeys and the Home hero slide: the hero (tonight's step of a journey: backdrop, "YOUR JOURNEY:
// <title> · step n of m" once started, ▶ Play: <film> and See the path), the path strip, and a journey card for the rows.
import { Backdrop } from "./Backdrop";
import { imageUrl } from "../api/jellyfin";
import { go } from "../lib/router";
import {
  btnLabel, dot, journeyPath, kicker, meta, nowIndex, pathWindow, progress, progressLabel, yourJourneyLine, type JvJourney, type JvStep, type JvSummary,
} from "../lib/journeysV1";
import { useEffect } from "react";
import { playTonight, warmIptvResume } from "../screens/journeyPlay";
import "../screens/Journeys.css";

/** The step's art, else the journey's, else the library's own backdrop, else the IPTV copy's; null = the gradient. */
export function heroArt(s: JvSummary, step: JvStep | null): string | null {
  return (
    step?.backdrop ??
    s.backdrop ??
    (step?.source === "library" && step.itemId ? imageUrl(step.itemId, "Backdrop", { maxWidth: 1920 }) : null) ??
    step?.iptv?.backdrop ??
    null
  );
}

export function JourneyHero({ s, j }: { s: JvSummary; j: JvJourney | null }) {
  const steps = j?.steps ?? null;
  const now = steps ? nowIndex(steps) : s.nowIndex;
  const step = steps && now != null ? steps[now] : null;
  const total = steps ? steps.length : s.total;
  const status = j?.summary.status ?? s.status;
  const film = step?.title ?? s.nowTitle;
  const art = heroArt(s, step);
  const line =
    yourJourneyLine(s.title, status, now, total) ?? (status === "done" || now == null ? `${s.title} · every step seen` : kicker(s.title, now, total));
  const facts = step ? [step.year, meta(step)].filter(Boolean).join(" · ") : null;
  useEffect(() => {
    if (step) warmIptvResume([step]);
  }, [step]);
  return (
    <div className="hero jv-hero" data-journey={s.id}>
      {art ? <Backdrop url={art} focal={null} kenBurns /> : <div className="jv-hero-fallback" aria-hidden />}
      <div className="hero-click" onClick={() => go(journeyPath(s.id))} aria-hidden />
      <div className="scrim-left" />
      <div className="scrim-bottom" />
      <div className="hero-text">
        <div className="hero-kicker jv-kicker">
          {line}
          {s.curated && <span className="jv-pill">Curated</span>}
        </div>
        <h1 className="title-text big">{film ?? s.title}</h1>
        {facts && <div className="line">{facts}</div>}
        {step?.why && <p className="overview two">{step.why}</p>}
        <div className="buttons">
          {now != null && film && (
            <button className="btn primary jv-btn" data-nav onClick={() => playTonight(s.id)}>
              {btnLabel("play", film)}
            </button>
          )}
          <button className="btn jv-btn" data-nav onClick={() => go(journeyPath(s.id))}>
            See the path
          </button>
        </div>
      </div>
    </div>
  );
}

const DOT_MARK = { done: "✓", now: "●", next: "○", gap: "○", skipped: "↷" } as const;
const DOT_WORD = { done: "Seen", now: "Tonight", next: "Next", gap: "Not here yet", skipped: "Skipped" } as const;

/** The path under the hero: up to 5 steps around tonight's ("+n" either side), in a row that scrolls sideways on a phone. */
export function PathStrip({ j }: { j: JvJourney }) {
  const now = nowIndex(j.steps);
  const w = pathWindow(j.steps.length, now);
  if (w.to < 0) return null;
  const open = () => go(journeyPath(j.summary.id));
  return (
    <section className="row jv-strip" aria-label="The path">
      {/* no counts in row titles: the title is the row's key for Back (lib/scrollMemory) and must not change with progress */}
      <h2 className="row-title">The path: {j.summary.title}</h2>
      <div className="row-scroll">
        {w.before > 0 && (
          <button className="jv-step-chip more" data-nav onClick={open} aria-label={`${w.before} earlier steps`}>
            +{w.before}
          </button>
        )}
        {j.steps.slice(w.from, w.to + 1).map((st, k) => {
          const i = w.from + k;
          const d = dot(j.steps, i, now);
          const word = d === "gap" && st.source === "missing" ? "Not available" : DOT_WORD[d];
          return (
            <button key={`${st.n}:${st.tmdbId ?? st.title}`} className={`jv-step-chip ${d}`} data-nav onClick={open} aria-label={`Step ${i + 1}: ${st.title}, ${word}`}>
              <span className="jv-dot" aria-hidden>
                {DOT_MARK[d]}
              </span>
              <span className="jv-chip-text">
                <span className="jv-chip-n">{i + 1}</span> {st.title}
              </span>
              <span className="jv-chip-state">{word}</span>
            </button>
          );
        })}
        {w.after > 0 && (
          <button className="jv-step-chip more" data-nav onClick={open} aria-label={`${w.after} more steps`}>
            +{w.after}
          </button>
        )}
      </div>
    </section>
  );
}

/** A journey in a row: its poster (or a titled gradient), title, a progress bar and tonight's film. */
export function JourneyCard({ s }: { s: JvSummary }) {
  const next = s.status === "done" || s.nowTitle == null ? "Every step seen" : `Next: ${s.nowTitle}`;
  return (
    <button className="jv-card" data-nav data-jid={s.id} onClick={() => go(journeyPath(s.id))} aria-label={`${s.title}, ${progressLabel(s)} seen. ${next}`}>
      <span className="jv-card-art">
        {s.poster ? <img src={s.poster} alt="" loading="lazy" draggable={false} /> : <span className="jv-card-fallback">{s.title}</span>}
        {s.curated && <span className="jv-pill on-art">Curated</span>}
      </span>
      <span className="jv-card-title">{s.title}</span>
      <span className="jv-bar" aria-hidden>
        <span style={{ width: `${Math.round(progress(s) * 100)}%` }} />
      </span>
      <span className="jv-card-sub">
        {progressLabel(s)} · {next}
      </span>
    </button>
  );
}
