// #/journeys/<id>, "See the path" (Journeys v1): the premise, every step in order with seen ticks, skipped marks, facts and the
// curated "why". Play is the way in (it starts the journey; no Start button). Started: Remove (server pause, progress kept) or
// Add back; Drop only behind a confirm with Keep it focused. Get it on any gettable step (the server marks those for admins
// only), with its own Asking… / On its way / Blocked: <reason>. A missing step says why and has no action.
import { useEffect, useRef, useState } from "react";
import { getStep, journeyAction, journeyEvent, useJourney } from "../api/journeysV1";
import { flashNote } from "../components/TasteButtons";
import { Backdrop } from "../components/Backdrop";
import { heroArt } from "../components/JourneyHero";
import { go } from "../lib/router";
import {
  askLabel, askText, btnLabel, canAsk, dot, facts, isCurrent, kicker, missingReason, nowIndex, pathButtons, progressLabel, sourceBadge, stepAction, stepDetailsPath, TOAST_ADDED,
  TOAST_DROPPED, TOAST_REMOVED, yourJourneyLine, type JvAsk, type JvBtn, type JvJourney, type JvStep,
} from "../lib/journeysV1";
import { playStep } from "./journeyPlay";
import "./Journeys.css";

const KIND = { curated: "Curated journey", list: "From a list", director: "A director's films", franchise: "A series in order" } as Record<string, string>;
const STATE = { done: "✓ Seen", now: "Tonight", skipped: "Skipped", gap: "", next: "" } as const;

export function Journey({ id }: { id: string }) {
  const L = useJourney(id);
  const j = L.value && L.value.summary.id === id ? L.value : null;
  if (!j) {
    return (
      <div className="journeys jv-page">
        {L.missing ? (
          <p className="empty">This journey isn't available.</p>
        ) : L.failures > 0 ? (
          <>
            <p className="empty">Couldn't load this journey. Trying again…</p>
            <button className="btn jv-btn" data-nav data-autofocus onClick={L.retryNow}>
              Retry now
            </button>
          </>
        ) : (
          <p className="empty">Loading…</p>
        )}
      </div>
    );
  }
  return <Path j={j} />;
}

function Path({ j }: { j: JvJourney }) {
  const id = j.summary.id;
  const s = j.summary;
  const now = nowIndex(j.steps);
  const nowStep = now != null ? j.steps[now] : null;
  const [confirming, setConfirming] = useState(false);
  const [asks, setAsks] = useState<Record<string, JvAsk>>({});
  const showing = useRef(id);
  showing.current = id;
  const dropRef = useRef<HTMLButtonElement>(null);
  const keepRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (confirming) keepRef.current?.focus({ preventScroll: true });
  }, [confirming]);

  const buttons = pathButtons(s.status, nowStep != null, confirming);
  const press = (b: JvBtn) => {
    switch (b) {
      case "play":
        if (now != null) playStep(j, now);
        return;
      case "remove":
        void journeyAction(id, "pause", s.status);
        flashNote(TOAST_REMOVED);
        return;
      case "addBack":
        void journeyAction(id, "start", s.status);
        flashNote(TOAST_ADDED);
        return;
      case "drop":
        setConfirming(true);
        return;
      case "dropConfirm":
        void journeyAction(id, "drop", s.status);
        flashNote(TOAST_DROPPED);
        setConfirming(false);
        return;
      case "dropCancel":
        setConfirming(false);
        requestAnimationFrame(() => dropRef.current?.focus({ preventScroll: true }));
        return;
    }
  };

  // Get it: one ask per step at a time; an answer for another journey (navigated away and back) is dropped
  const ask = (i: number) => {
    const st = j.steps[i];
    const key = `${i}:${st.tmdbId ?? st.title}`;
    if (!canAsk(asks[key])) return;
    setAsks((a) => ({ ...a, [key]: { state: "asking" } }));
    void getStep(id, j.steps, i)
      .catch((): JvAsk => ({ state: "error", reason: "Couldn't reach the server. Try again." }))
      .then((r) => {
        if (isCurrent(id, showing.current)) setAsks((a) => ({ ...a, [key]: r }));
      });
  };
  const skip = (st: JvStep) => {
    void journeyEvent(id, "skip", st.tmdbId);
    flashNote(`Skipped ${st.title}`);
  };

  const line = yourJourneyLine(s.title, s.status, now, j.steps.length) ?? (now != null ? kicker(KIND[s.kind] ?? "Journey", now, j.steps.length) : "Every step seen");
  const info = [j.maxRating ? `Rated ${j.maxRating}` : null, `${j.steps.length} films`, `${progressLabel(s)} seen`].filter(Boolean).join(" · ");
  const art = heroArt(s, nowStep);
  return (
    <div className="jv-path">
      <div className="jv-path-head">
        {art ? <Backdrop url={art} focal={null} /> : <div className="jv-hero-fallback" aria-hidden />}
        <div className="scrim-left" />
        <div className="scrim-bottom" />
        <div className="jv-path-text">
          <div className="hero-kicker jv-kicker">
            {line}
            {s.curated && <span className="jv-pill">Curated</span>}
          </div>
          <h1 className="title-text big">{s.title}</h1>
          {j.premise && <p className="overview">{j.premise}</p>}
          <div className="line dim">{info}</div>
          {confirming && <p className="line">Drop this journey? It leaves your rows; you can still open it from here.</p>}
          <div className="buttons jv-buttons">
            {buttons.map((b) => (
              <button
                key={b}
                ref={b === "drop" ? dropRef : b === "dropCancel" ? keepRef : undefined}
                className={`btn jv-btn${b === "play" || b === "dropCancel" ? " primary" : ""}${b === "drop" ? " ghost" : ""}`}
                data-nav
                {...(b === "play" && s.status !== "active" ? { "data-autofocus": "" } : {})}
                onClick={() => press(b)}
              >
                {btnLabel(b, nowStep?.title ?? null)}
              </button>
            ))}
          </div>
        </div>
      </div>
      <ol className="jv-steps">
        {j.steps.map((st, i) => {
          const d = dot(j.steps, i, now);
          const act = stepAction(st);
          const key = `${i}:${st.tmdbId ?? st.title}`;
          const a = asks[key];
          const details = stepDetailsPath(st);
          const reason = missingReason(st);
          return (
            <li key={key} className={`jv-step ${d}`} data-fid={`jv:${i}`}>
              <button className="jv-step-art" data-nav={details ? "" : undefined} tabIndex={details ? 0 : -1} disabled={!details} onClick={() => details && go(details)} aria-label={details ? `${st.title}: details` : st.title}>
                {st.poster ? <img src={st.poster} alt="" loading="lazy" draggable={false} /> : <span className="jv-step-noart">{st.title}</span>}
                <span className="jv-step-n" aria-hidden>
                  {i + 1}
                </span>
              </button>
              <div className="jv-step-body">
                <div className="jv-step-top">
                  <span className="jv-step-title">
                    {st.title}
                    {st.year ? <span className="jv-year"> ({st.year})</span> : null}
                  </span>
                  {STATE[d] && <span className={`jv-mark ${d}`}>{STATE[d]}</span>}
                </div>
                <div className="jv-step-meta">
                  {facts(st)}
                  <span className={`jv-src src-${st.source}`}>{sourceBadge(st.source)}</span>
                  {st.rewatch && <span className="jv-src">Rewatch</span>}
                </div>
                {st.why && <p className="jv-why">{st.why}</p>}
                <div className="jv-step-actions">
                  {act === "play" && (
                    <button className={`btn jv-btn${i === now ? " primary" : ""}`} data-nav onClick={() => playStep(j, i)}>
                      ▶ Play
                    </button>
                  )}
                  {act === "play" && details && (
                    <button className="btn jv-btn" data-nav onClick={() => go(details)}>
                      Details
                    </button>
                  )}
                  {i === now && !st.seen && (
                    <button className="btn jv-btn ghost" data-nav onClick={() => skip(st)}>
                      Skip this one
                    </button>
                  )}
                  {act === "get" && (
                    <button className={`btn jv-btn jv-get${a ? ` st-${a.state}` : ""}`} data-nav disabled={!canAsk(a)} onClick={() => ask(i)}>
                      {askLabel(a)}
                    </button>
                  )}
                  {act === "get" && a && a.state !== "asking" && <span className={`jv-ask st-${a.state}`}>{askText(a)}</span>}
                  {act === "get" && !a && <span className="jv-ask">Not here yet</span>}
                  {reason && <span className="jv-ask">{reason}</span>}
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
