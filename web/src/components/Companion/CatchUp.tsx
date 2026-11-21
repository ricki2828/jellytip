// Catch me up (TV port, SportWow.kt CatchUpPolicy + JellytipWowCards.kt): the late-join choice card and the recap cards, drawn over
// the live picture. The player decides when to show them (lib/catchUp.ts) and holds the score back meanwhile. Keys: arrows move
// between the choices, Enter picks, Escape / Backspace = Live. Captured on window so the player's own keys (Up = panel, Escape =
// leave) never act underneath.
import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { CARD_MS, CHOICE_TIMEOUT_MS, choices, type CatchCard } from "../../lib/catchUp";
import { FULLTIME_SHOW_MS } from "../../lib/sportWow";
import { GOLD, onColor, teamColors } from "../../lib/broadcast";
import type { Fixture } from "../../lib/matchInfo";
import "./CatchUp.css";

export type CatchChoice = "kickoff" | "catchup" | "live";

/** While open, arrows / Enter / Escape belong to the card. */
/** [scoped]: keys only while focus is on the card (or nowhere), for cards that may sit beside an open panel (full time). The
 *  catch-up cards are not scoped: while the score is held, no key may reach the player (panel, leave). */
export function useCardKeys(root: RefObject<HTMLDivElement | null>, onEscape: () => void, scoped = false) {
  const esc = useRef(onEscape);
  esc.current = onEscape;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const btns = Array.from(root.current?.querySelectorAll<HTMLButtonElement>("button") ?? []);
      if (!btns.length) return;
      // keys belong to the card only while focus is on it (or nowhere): an open panel or a menu keeps its own
      const ae = document.activeElement;
      if (scoped && ae && ae !== document.body && !root.current?.contains(ae)) return;
      const at = btns.indexOf(document.activeElement as HTMLButtonElement);
      let handled = true;
      // focus lost (a click on the backdrop): arrows come back to the default button (Live / Next), not the first
      const home = Math.max(0, btns.findIndex((b) => b.dataset.choice === "live" || b.hasAttribute("data-next")));
      if (e.key === "Escape" || e.key === "Backspace" || e.key === "GoBack" || e.key === "BrowserBack") esc.current();
      else if (e.key === "ArrowLeft" || e.key === "ArrowUp") btns[at < 0 ? home : Math.max(0, at - 1)].focus();
      else if (e.key === "ArrowRight" || e.key === "ArrowDown") btns[at < 0 ? home : Math.min(btns.length - 1, at + 1)].focus();
      else if (e.key === "Enter" || e.key === " ") (at >= 0 ? btns[at] : btns[btns.length - 1]).click();
      else handled = false;
      if (handled) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [root]);
}

function Slabs({ fixture, middle }: { fixture: Fixture | null; middle?: ReactNode }) {
  const c = teamColors(fixture);
  const home = fixture?.home.name ?? "Home";
  const away = fixture?.away.name ?? "Away";
  const bg = (hex: string, fallback: string) => (c.known ? hex : fallback);
  return (
    <div className={`cu-slabs${middle != null ? " mid" : ""}`}>
      <div className="cu-slab" style={{ background: bg(c.home, "#2a2e35"), color: c.known ? onColor(c.home) : "#fff" }}>
        <span className="full">{home}</span>
        <span className="short">{fixture?.home.short ?? home}</span>
      </div>
      {middle}
      <div className="cu-slab right" style={{ background: bg(c.away, "#2a2e35"), color: c.known ? onColor(c.away) : "#fff" }}>
        <span className="full">{away}</span>
        <span className="short">{fixture?.away.short ?? away}</span>
      </div>
    </div>
  );
}

/** The three choices when joining late; Live is focused and is what happens when the countdown runs out. */
export function CatchUpChoice({ fixture, joining, canKickOff, onChoose }: { fixture: Fixture | null; joining: string; canKickOff: boolean; onChoose: (c: CatchChoice) => void }) {
  const root = useRef<HTMLDivElement>(null);
  const [left, setLeft] = useState(Math.round(CHOICE_TIMEOUT_MS / 1000));
  const choose = useRef(onChoose);
  choose.current = onChoose;
  useCardKeys(root, () => choose.current("live"));
  useEffect(() => {
    root.current?.querySelector<HTMLButtonElement>('button[data-choice="live"]')?.focus({ preventScroll: true });
    const t = window.setInterval(() => setLeft((n) => n - 1), 1000);
    return () => window.clearInterval(t);
  }, []);
  useEffect(() => {
    if (left <= 0) choose.current("live");
  }, [left]);
  return (
    <div className="cu-wrap" role="dialog" aria-label="Joining late" ref={root}>
      <div className="cu-card">
        <Slabs fixture={fixture} />
        <div className="cu-body">
          <div className="cu-gold">{joining}</div>
          <p className="cu-dim">Score stays hidden until you choose.</p>
          <div className="cu-choices">
            {choices(canKickOff).map((c) => (
              <button key={c.id} className="cu-choice" data-choice={c.id} onClick={() => choose.current(c.id)}>
                <span className="t">{c.title}</span>
                <span className="s">{c.sub}</span>
              </button>
            ))}
          </div>
          <p className="cu-dim" aria-live="off">
            Goes to Live in {Math.max(0, left)} s
          </p>
        </div>
      </div>
    </div>
  );
}

/** The recap: one card at a time, 5 s each, then live. Next / Live buttons; Escape = straight to live. */
export function CatchUpRecap({ fixture, cards, onDone }: { fixture: Fixture | null; cards: CatchCard[]; onDone: () => void }) {
  const root = useRef<HTMLDivElement>(null);
  const [i, setI] = useState(0);
  const done = useRef(onDone);
  done.current = onDone;
  useCardKeys(root, () => done.current());
  useEffect(() => {
    root.current?.querySelector<HTMLButtonElement>("button[data-next]")?.focus({ preventScroll: true });
  }, []);
  useEffect(() => {
    const t = window.setTimeout(() => (i < cards.length - 1 ? setI(i + 1) : done.current()), CARD_MS);
    return () => window.clearTimeout(t);
  }, [i, cards.length]);
  const c = cards[Math.min(i, cards.length - 1)];
  const colors = teamColors(fixture);
  const tick = c.team ? (colors.known ? colors[c.team] : GOLD) : "transparent";
  const next = () => (i < cards.length - 1 ? setI(i + 1) : done.current());
  return (
    <div className="cu-wrap recap" role="dialog" aria-label="Catch me up" ref={root}>
      <div className="cu-card cu-recap">
        <div className="cu-head">
          <span className="cu-gold">Catch me up</span>
          <span className="cu-dim">
            {i + 1} / {cards.length}
          </span>
        </div>
        <div className="cu-play" aria-live="polite">
          {c.when && <span className="cu-when">{c.when}</span>}
          <span className="cu-tick" style={{ background: tick }} />
          <span className="cu-text">{c.text}</span>
          {c.points != null && c.points > 0 && <span className="cu-pts">+{c.points}</span>}
        </div>
        <div className="cu-buttons">
          <button className="btn small" data-next onClick={next}>
            {i < cards.length - 1 ? "Next" : "Done"}
          </button>
          <button className="btn small" onClick={() => done.current()}>
            Straight to live
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Full time (TV port, FullTimeCard): the released score between the team slabs, then what's next: the next game (Watch it) or,
 * with none within the hour, Pick for tonight. Stay keeps this channel; it tucks itself away after 30 s.
 */
export function FullTimeCard({ fixture, score, next, nextLine, onNext, onClose }: { fixture: Fixture | null; score: { home: number; away: number } | null; next: string | null; nextLine: string | null; onNext: () => void; onClose: () => void }) {
  const root = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useCardKeys(root, () => close.current(), true);
  useEffect(() => {
    root.current?.querySelector<HTMLButtonElement>("button[data-next]")?.focus({ preventScroll: true });
    const t = window.setTimeout(() => close.current(), FULLTIME_SHOW_MS);
    return () => window.clearTimeout(t);
  }, []);
  return (
    <div className="cu-wrap recap" role="dialog" aria-label="Full time" ref={root}>
      <div className="cu-card">
        <Slabs fixture={fixture} middle={<span className="cu-ft-score">{score ? `${score.home} – ${score.away}` : "–"}</span>} />
        <div className="cu-body">
          <div className="cu-gold">Full time</div>
          <div className="cu-choices">
            <button className="cu-choice" data-next onClick={onNext}>
              <span className="t">{next ? `Next: ${next}` : "Pick for tonight"}</span>
              <span className="s">{next ? nextLine : "Three films for tonight from your library"}</span>
            </button>
            <button className="cu-choice" onClick={() => close.current()}>
              <span className="t">Stay</span>
              <span className="s">Keep watching this channel</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
