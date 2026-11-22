// #/ask: plain-language discovery. "something funny under 90 minutes" → removable chips → up to 24 library picks.
// Deterministic (lib/askParse.ts), no LLM. Library only: IPTV and requests are not searched here. "Narrow it down" offers
// refine chips drawn from what was found (people, genres to keep / leave out, decades, kind, length, unwatched).
import { useEffect, useMemo, useRef, useState } from "react";
import { getItems, uid, type Item } from "../api/jellyfin";
import { activeChips, appliedNotes, buildQuery, EXAMPLES, filterItems, parseAsk, pickFrom, PICKS, refineOptions, removeChip, type Chip, type Picks } from "../lib/askParse";
import { askIptvPicks, vodNote, vodQueries } from "../lib/askVod";
import { parseVodItems, type VodItem } from "../lib/iptvVod";
import { hasLiveTv, iptvGet } from "../api/iptv";
import { Card } from "../components/Card";
import { IptvCard } from "../components/IptvTile";
import { go } from "../lib/router";
import "./Ask.css";

// kept = the fetched titles that pass every check (what the refine chips are drawn from)
type State = { kind: "idle" } | { kind: "loading" } | { kind: "error" } | { kind: "done"; picks: Picks; kept: Item[] };

export function Ask() {
  const userId = uid();
  const [text, setText] = useState("");
  const [asked, setAsked] = useState("");
  const [removed, setRemoved] = useState<string[]>([]);
  // Refine chips he tapped (added after the reading; removable like the others)
  const [added, setAdded] = useState<Chip[]>([]);
  // The shown picks with their people (a second, small request): the director / actor refine chips
  const [withPeople, setWithPeople] = useState<{ key: string; items: Item[] } | null>(null);
  const [shuffle, setShuffle] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<State>({ kind: "idle" });
  // The IPTV half (source-neutral: same grid, after the library picks); key = the library answer it belongs to
  const [iptv, setIptv] = useState<{ key: number; items: VodItem[]; note: string | null; pending: boolean } | null>(null);
  const loadSeq = useRef(0);
  const input = useRef<HTMLInputElement>(null);
  const chipBox = useRef<HTMLDivElement>(null);
  const refocus = useRef<number | null>(null);

  const reading = useMemo(() => parseAsk(asked), [asked]);
  const chips = useMemo(() => {
    const read = activeChips(reading, removed);
    return [...read, ...added.filter((c) => !removed.includes(c.id) && !read.some((r) => r.id === c.id))];
  }, [reading, removed, added]);
  const chipKey = chips.map((c) => c.id).join("|");

  useEffect(() => {
    if (chips.length === 0) {
      setState({ kind: "idle" });
      return;
    }
    let alive = true;
    const seq = ++loadSeq.current;
    setState({ kind: "loading" });
    setIptv(null);
    const q = buildQuery(chips, { shuffle, refine: true });
    // ProviderIds: the TMDB ids that keep an IPTV twin of a library pick out of the grid
    getItems({ ...q, Fields: [q.Fields, "ProviderIds"].filter(Boolean).join(",") })
      .then((r) => {
        if (!alive) return;
        const items = r.Items as Item[];
        const picks = pickFrom(items, r.TotalRecordCount, chips, shuffle);
        setState({ kind: "done", picks, kept: filterItems(items, chips) });
        void loadIptv(seq, picks.items, () => alive);
        // People only for the shown picks; a failure just means no people chips
        const ids = picks.items.map((it) => it.Id);
        if (ids.length > 1) {
          getItems({ Ids: ids.join(","), Fields: "People", Limit: ids.length })
            .then((pr) => alive && setWithPeople({ key: ids.join("|"), items: pr.Items as Item[] }))
            .catch(() => {});
        }
      })
      .catch(() => alive && setState({ kind: "error" }));
    return () => {
      alive = false;
    };
    // chipKey stands for the chips; userId so a different signed-in user never sees another's picks
  }, [chipKey, shuffle, nonce, userId]); // eslint-disable-line react-hooks/exhaustive-deps

  /** IPTV titles that fit, after the library picks (twins of a library pick left out); errors = library only. */
  async function loadIptv(seq: number, libraryPicks: Item[], alive: () => boolean) {
    const queries = vodQueries(chips);
    const note = vodNote(chips);
    // Pending before the IPTV check too: no "Nothing fits." flash while it answers
    setIptv({ key: seq, items: [], note: null, pending: true });
    const has = await hasLiveTv().catch(() => false);
    if (!alive()) return;
    // No IPTV, or nothing IPTV can be asked (a person chip, kids with no known rating): the note says why, when he has IPTV
    if (!has || !queries.length) {
      setIptv(has && note ? { key: seq, items: [], note, pending: false } : null);
      return;
    }
    try {
      const answers = await Promise.all(
        queries.map((vq) =>
          iptvGet<{ items?: unknown }>(`v1/iptv/vod?${new URLSearchParams(vq.params)}`)
            .then((a) => (a.status === 200 ? parseVodItems(a.data?.items) : []))
            .catch(() => [] as VodItem[]),
        ),
      );
      if (!alive()) return;
      const fetched = answers.flat();
      const ordered = shuffle ? shuffled(fetched) : fetched;
      const lib = new Set(
        libraryPicks.flatMap((it) => {
          const t = it.ProviderIds?.Tmdb;
          return t ? [`${it.Type === "Series" ? "series" : "movie"}:${t}`] : [];
        }),
      );
      const { shown, kept } = askIptvPicks(ordered, chips, lib, PICKS);
      const checked = fetched.length ? `IPTV: ${shown.length} more from the top ${fetched.length} by rating (${kept} fit).` : null;
      setIptv({ key: seq, items: shown, note: [checked, note].filter(Boolean).join(" ") || null, pending: false });
    } catch {
      if (alive()) setIptv(null);
    }
  }
  const iptvNow = iptv && state.kind === "done" && iptv.key === loadSeq.current ? iptv : null;

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
    setAdded([]);
    setWithPeople(null);
    setShuffle(false);
    setNonce((n) => n + 1);
  };
  const notes = [...reading.notes, ...appliedNotes(chips)];
  const refine = useMemo(() => {
    if (state.kind !== "done") return [];
    const people = withPeople?.key === state.picks.items.map((it) => it.Id).join("|") ? withPeople.items : [];
    return refineOptions(state.kept, people, chips);
  }, [state, withPeople, chips]);
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
        <button type="button" className="btn" data-nav onClick={() => go("/ask/chat")}>
          Chat
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
          {chips.length > 0 || reading.chips.length > 0 ? (
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
                <button
                  className="chip"
                  data-nav
                  onClick={() => {
                    refocus.current = 0;
                    setRemoved([]);
                  }}
                >
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
      {state.kind === "error" && (
        <div className="rows-error" role="alert">
          <p>Couldn't reach your library.</p>
          <button className="btn" data-nav onClick={() => setNonce((n) => n + 1)}>
            Try again
          </button>
        </div>
      )}
      {(state.kind === "done" || state.kind === "loading") && (
        <section>
          <div className="ask-bar">
            <p className="ask-coverage" role="status">
              {state.kind === "done"
                ? [state.picks.items.length === 0 && !iptvNow?.items.length && !iptvNow?.pending ? "Nothing fits." : "", state.picks.note, iptvNow?.note ?? ""].filter(Boolean).join(" ")
                : "Looking…"}
            </p>
            <button
              className="btn small"
              data-nav
              disabled={state.kind === "loading"}
              onClick={() => {
                setShuffle(true);
                setNonce((n) => n + 1);
              }}
            >
              Shuffle
            </button>
          </div>
          {refine.length > 0 && (
            <div className="chips ask-refine" aria-label="Narrow it down">
              <span className="ask-refine-label">Narrow it down</span>
              {refine.map((c) => (
                <button
                  key={c.id}
                  className="chip"
                  data-nav
                  onClick={() => {
                    // Something he removed earlier comes back as asked, not as a no-op
                    setRemoved((r) => r.filter((id) => id !== c.id));
                    setAdded((a) => [...a, c]);
                  }}
                >
                  + {c.label}
                </button>
              ))}
            </div>
          )}
          {state.kind === "loading" ? (
            <div className="grid ask-grid" aria-busy="true">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="card fluid card-skeleton" />
              ))}
            </div>
          ) : (
            <div className="grid ask-grid">
              {state.picks.items.map((it) => (
                <Card key={it.Id} item={it} width={0} />
              ))}
              {iptvNow?.items.map((v) => <IptvCard key={`v:${v.id}`} item={v} width={0} />)}
            </div>
          )}
        </section>
      )}
    </div>
  );
}

/** Fisher-Yates (unbiased). */
function shuffled<T>(xs: T[]): T[] {
  const a = [...xs];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
