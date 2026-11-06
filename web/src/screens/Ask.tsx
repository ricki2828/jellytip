// #/ask: plain-language discovery. "something funny under 90 minutes" → removable chips → up to 24 library picks.
// Deterministic (lib/askParse.ts), no LLM. Library only: IPTV and requests are not searched here.
import { useEffect, useMemo, useRef, useState } from "react";
import { getItems, uid, type Item } from "../api/jellyfin";
import { activeChips, appliedNotes, buildQuery, EXAMPLES, parseAsk, pickFrom, removeChip, type Picks } from "../lib/askParse";
import { Card } from "../components/Card";
import "./Ask.css";

type State = { kind: "idle" } | { kind: "loading" } | { kind: "error" } | { kind: "done"; picks: Picks };

export function Ask() {
  const userId = uid();
  const [text, setText] = useState("");
  const [asked, setAsked] = useState("");
  const [removed, setRemoved] = useState<string[]>([]);
  const [shuffle, setShuffle] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<State>({ kind: "idle" });
  const input = useRef<HTMLInputElement>(null);
  const chipBox = useRef<HTMLDivElement>(null);
  const refocus = useRef<number | null>(null);

  const reading = useMemo(() => parseAsk(asked), [asked]);
  const chips = useMemo(() => activeChips(reading, removed), [reading, removed]);
  const chipKey = chips.map((c) => c.id).join("|");

  useEffect(() => {
    if (chips.length === 0) {
      setState({ kind: "idle" });
      return;
    }
    let alive = true;
    setState({ kind: "loading" });
    getItems(buildQuery(chips, { shuffle }))
      .then((r) => alive && setState({ kind: "done", picks: pickFrom(r.Items as Item[], r.TotalRecordCount, chips, shuffle) }))
      .catch(() => alive && setState({ kind: "error" }));
    return () => {
      alive = false;
    };
    // chipKey stands for the chips; userId so a different signed-in user never sees another's picks
  }, [chipKey, shuffle, nonce, userId]); // eslint-disable-line react-hooks/exhaustive-deps

  // After a chip is removed its button is gone: land on its neighbour (or the box) so keyboard and remote do not lose their place
  useEffect(() => {
    if (refocus.current == null) return;
    const btns = chipBox.current?.querySelectorAll<HTMLElement>("button");
    const i = refocus.current;
    refocus.current = null;
    (btns && btns.length ? btns[Math.min(i, btns.length - 1)] : input.current)?.focus();
  }, [chipKey]);

  const submit = (v: string) => {
    const q = v.trim();
    setText(v);
    setAsked(q);
    setRemoved([]);
    setShuffle(false);
    setNonce((n) => n + 1);
  };
  const notes = appliedNotes(chips);
  const hasAsk = asked.length > 0;

  return (
    <div className="ask">
      <h1 className="page-title">Ask</h1>
      <p className="ask-sub">Say what you feel like. Searches your library only, not IPTV or requests.</p>
      <form
        className="ask-form"
        onSubmit={(e) => {
          e.preventDefault();
          submit(text);
        }}
      >
        <input
          ref={input}
          className="search-input"
          data-nav
          autoFocus
          type="search"
          enterKeyHint="search"
          aria-label="What do you feel like watching?"
          placeholder="e.g. something funny under 90 minutes"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <button type="submit" className="btn primary" data-nav>
          Ask
        </button>
      </form>

      {!hasAsk && (
        <section>
          <h2 className="row-title">Try</h2>
          <div className="chips">
            {EXAMPLES.map((q) => (
              <button key={q} className="chip" data-nav onClick={() => submit(q)}>
                {q}
              </button>
            ))}
          </div>
        </section>
      )}

      {hasAsk && (
        <section aria-live="polite">
          {reading.chips.length > 0 ? (
            <div className="chips ask-chips" ref={chipBox} aria-label="How this was read">
              {chips.map((c, i) => (
                <button
                  key={c.id}
                  className="chip on"
                  data-nav
                  aria-label={`Remove ${c.label}`}
                  onClick={() => {
                    refocus.current = i;
                    setRemoved((r) => removeChip(r, c.id));
                  }}
                >
                  {c.label}
                  <span aria-hidden> ×</span>
                </button>
              ))}
              {removed.length > 0 && (
                <button className="chip" data-nav onClick={() => setRemoved([])}>
                  Put back
                </button>
              )}
            </div>
          ) : (
            <p className="empty">Couldn't read a mood, length, decade or “unwatched” from that. Try one of the examples.</p>
          )}
          {reading.ignored.length > 0 && <p className="ask-ignored">ignored: {reading.ignored.join(", ")}</p>}
          {notes.map((n) => (
            <p key={n} className="ask-note">
              {n}
            </p>
          ))}
        </section>
      )}

      {hasAsk && chips.length === 0 && reading.chips.length > 0 && <p className="empty">Every filter is removed. Put one back, or ask again.</p>}
      {state.kind === "loading" && <div className="grid ask-grid" aria-busy="true">{Array.from({ length: 8 }, (_, i) => <div key={i} className="card fluid card-skeleton" />)}</div>}
      {state.kind === "error" && (
        <div className="rows-error" role="alert">
          <p>Couldn't reach your library.</p>
          <button className="btn" data-nav onClick={() => setNonce((n) => n + 1)}>
            Try again
          </button>
        </div>
      )}
      {state.kind === "done" && (
        <section>
          <div className="ask-bar">
            <p className="ask-coverage" role="status">
              {state.picks.items.length === 0 ? "Nothing fits. " : ""}
              {state.picks.note}
            </p>
            <button
              className="btn small"
              data-nav
              onClick={() => {
                setShuffle(true);
                setNonce((n) => n + 1);
              }}
            >
              Shuffle
            </button>
          </div>
          <div className="grid ask-grid">
            {state.picks.items.map((it) => (
              <Card key={it.Id} item={it} width={0} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
