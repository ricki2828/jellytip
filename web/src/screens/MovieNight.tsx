// #/movienight: a shared movie night. Start one from Tonight's shortlist (or join with a code / link), vote yes / maybe / no
// on each film, watch the tally fill in, then play the top match. Pure logic (tally, tie-break, codes) is lib/movieNight.ts.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getItems, imageUrl, uid, type Item } from "../api/jellyfin";
import { closeNight, createNight, getNight, joinNight, voteNight, type Result } from "../api/movieNight";
import { candidates } from "../lib/tonight";
import { load, toPool, ratedFor } from "./Tonight";
import { go } from "../lib/router";
import { MAX_FILMS, MIN_FILMS, finished, minutesLeft, nextToVote, parseCode, shareLink, shortlist, showCode, tally, winner, type Session, type Vote } from "../lib/movieNight";
import "./MovieNight.css";

const POLL_MS = 3000;
const keyFor = () => `jellytip.movienight.${uid()}`;
const undash = (s: string) => s.replace(/-/g, "");
const LABEL: Record<Vote, string> = { yes: "Yes", maybe: "Maybe", no: "No" };

/** The session behind a code, kept fresh every few seconds while the page is visible. */
function useSession(code: string | null, onGone: (msg: string) => void) {
  const [session, setSession] = useState<Session | null>(null);
  const [offline, setOffline] = useState(false);
  const [denied, setDenied] = useState(false);
  const goneRef = useRef(onGone);
  goneRef.current = onGone;
  const refresh = useRef<() => void>(() => undefined);

  useEffect(() => {
    setSession((prev) => (prev && prev.code === code ? prev : null));
    setOffline(false);
    setDenied(false);
    if (!code) return;
    let alive = true;
    // Every refresh starts a new generation: the old loop's pending timer and in-flight request are dropped, so only one loop runs.
    let gen = 0;
    let timer: number | undefined;
    let ctl: AbortController | null = null;
    const tick = async (mine: number) => {
      if (!alive || mine !== gen) return;
      if (document.visibilityState === "hidden") {
        timer = window.setTimeout(() => void tick(mine), POLL_MS);
        return;
      }
      ctl = new AbortController();
      const r = await getNight(code, ctl.signal);
      if (!alive || mine !== gen) return;
      if (r.ok) {
        setSession(r.session);
        setOffline(false);
      } else if (r.status === 404) {
        return goneRef.current("That movie night has ended or expired.");
      } else if (r.status === 401 || r.status === 403) {
        setDenied(true); // signed out: stop asking, the code is kept for when they sign back in
        return;
      } else setOffline(true);
      timer = window.setTimeout(() => void tick(mine), POLL_MS);
    };
    const start = () => {
      gen += 1;
      window.clearTimeout(timer);
      ctl?.abort();
      void tick(gen);
    };
    refresh.current = start;
    start();
    return () => {
      alive = false;
      gen += 1;
      window.clearTimeout(timer);
      ctl?.abort();
    };
  }, [code]);
  return { session, setSession, offline, denied, refresh: () => refresh.current() };
}

/** Titles and posters for the film ids (a film this person can't see simply has no entry). */
function useFilms(ids: string[]) {
  const [films, setFilms] = useState<Map<string, Item>>(new Map());
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const key = ids.join(",");
  useEffect(() => {
    if (!ids.length) return;
    let alive = true;
    setFailed(false);
    getItems({ Ids: ids.join(","), Fields: "Genres,PrimaryImageAspectRatio" }).then(
      (r) => alive && setFilms(new Map(r.Items.map((i) => [undash(i.Id), i]))),
      () => alive && setFailed(true),
    );
    return () => {
      alive = false;
    };
  }, [key, attempt]); // eslint-disable-line react-hooks/exhaustive-deps
  return { films, failed, retry: () => setAttempt((n) => n + 1) };
}

export function MovieNight({ joinParam }: { joinParam: string | null }) {
  const [code, setCode] = useState<string | null>(() => parseCode(joinParam ?? "") ? null : localStorage.getItem(keyFor()));
  const [notice, setNotice] = useState<string | null>(null);
  const remember = (c: string | null) => {
    if (c) localStorage.setItem(keyFor(), c);
    else localStorage.removeItem(keyFor());
    setCode(c);
  };
  const { session, setSession, offline, denied, refresh } = useSession(code, (msg) => {
    remember(null);
    setNotice(msg);
  });
  const [picking, setPicking] = useState(false);

  const enter = (r: Result): string | null => {
    if (!r.ok) return r.error;
    setSession(r.session);
    remember(r.session.code);
    setPicking(false);
    setNotice(null);
    return null;
  };

  return (
    <div className="mnight">
      <h1 className="page-title">Movie night</h1>
      {!code && !picking && <Start initialJoin={joinParam ?? ""} notice={notice} onPick={() => setPicking(true)} onJoined={enter} />}
      {!code && picking && <Pick onCancel={() => setPicking(false)} onCreated={enter} />}
      {code && denied && <p className="ask-sub" role="alert">You're signed out. Sign in again to get back to your movie night.</p>}
      {code && !denied && !session && <p className="ask-sub">{offline ? "Can't reach the server. Trying again…" : "Opening…"}</p>}
      {code && session && <Night session={session} offline={offline && !denied} onChange={(s) => (setSession(s), refresh())} onLeave={() => remember(null)} />}
    </div>
  );
}

// ---------- start / join ----------

function Start({ initialJoin, notice, onPick, onJoined }: { initialJoin: string; notice: string | null; onPick: () => void; onJoined: (r: Result) => string | null }) {
  const [text, setText] = useState(initialJoin);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const parsed = parseCode(text);
  const join = async () => {
    if (!parsed || busy) return;
    setBusy(true);
    setErr(onJoined(await joinNight(parsed)));
    setBusy(false);
  };
  return (
    <div className="mnight-start">
      {notice && <p className="ask-sub" role="status">{notice}</p>}
      <section className="mnight-card">
        <h2>Start one</h2>
        <p className="ask-sub">Pick a few films, share the code, and everyone in the house votes from their own phone.</p>
        <button className="btn primary" data-nav data-autofocus onClick={onPick}>
          Choose films
        </button>
      </section>
      <section className="mnight-card">
        <h2>Join one</h2>
        <form
          className="mnight-join"
          onSubmit={(e) => {
            e.preventDefault();
            void join();
          }}
        >
          <input className="mnight-code-input" data-nav value={text} onChange={(e) => (setText(e.target.value), setErr(null))} placeholder="Code, e.g. K7M2QX" aria-label="Movie night code" autoCapitalize="characters" autoComplete="off" spellCheck={false} />
          <button className="btn primary" data-nav disabled={!parsed || busy} type="submit">
            {busy ? "Joining…" : "Join"}
          </button>
        </form>
        {text.trim() && !parsed && <p className="ask-sub">A code is six letters and numbers.</p>}
        {err && <p className="mnight-err" role="alert">{err}</p>}
      </section>
    </div>
  );
}

// ---------- choose films ----------

function Pick({ onCancel, onCreated }: { onCancel: () => void; onCreated: (r: Result) => string | null }) {
  const [list, setList] = useState<{ id: string; item: Item; reason: string }[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [on, setOn] = useState<Set<string>>(new Set());
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    setFailed(false);
    setList(null);
    (async () => {
      const raw = await load();
      const rated = await ratedFor([]).catch(() => [] as Item[]);
      const cs = shortlist(candidates(toPool(raw, rated)));
      const byId = new Map<string, Item>();
      for (const x of [...raw.recs.map((r) => r.item), ...rated]) byId.set(undash(x.Id), x);
      const out = cs.flatMap((c) => (byId.get(c.key) ? [{ id: c.key, item: byId.get(c.key)!, reason: c.reason }] : []));
      if (alive) {
        setList(out);
        setOn(new Set(out.map((o) => o.id)));
      }
    })().catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [attempt]);

  const chosen = (list ?? []).filter((o) => on.has(o.id));
  const toggle = (id: string) =>
    setOn((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else if (n.size < MAX_FILMS) n.add(id);
      return n;
    });
  const start = async () => {
    if (busy || chosen.length < MIN_FILMS) return;
    setBusy(true);
    setErr(onCreated(await createNight(chosen.map((o) => o.id))));
    setBusy(false);
  };

  return (
    <div className="mnight-pick">
      <p className="ask-sub">Untick anything nobody would want. Films you haven't watched, from your library.</p>
      {failed && (
        <p className="ask-sub">
          Couldn't load your library.{" "}
          <button className="btn small" data-nav onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        </p>
      )}
      {!list && !failed && <p className="ask-sub">Looking…</p>}
      {list && list.length < MIN_FILMS && <p className="ask-sub">Not enough unwatched films to suggest yet.</p>}
      {list && list.length >= MIN_FILMS && (
        <ul className="mnight-list">
          {list.map((o) => (
            <li key={o.id}>
              <label className={`mnight-opt${on.has(o.id) ? " on" : ""}`}>
                <input type="checkbox" data-nav checked={on.has(o.id)} onChange={() => toggle(o.id)} />
                <Art item={o.item} />
                <span className="mnight-opt-text">
                  <strong>{o.item.Name}</strong>
                  <span className="tonight-sub">{[o.item.ProductionYear, o.reason].filter(Boolean).join(" · ")}</span>
                </span>
              </label>
            </li>
          ))}
        </ul>
      )}
      {err && <p className="mnight-err" role="alert">{err}</p>}
      <div className="mnight-actions">
        <button className="btn primary" data-nav disabled={chosen.length < MIN_FILMS || busy} onClick={start}>
          {busy ? "Starting…" : `Start with ${chosen.length} film${chosen.length === 1 ? "" : "s"}`}
        </button>
        <button className="btn ghost" data-nav onClick={onCancel}>
          Back
        </button>
      </div>
    </div>
  );
}

function Art({ item }: { item: Item | undefined }) {
  return <span className="mnight-art" aria-hidden>{item ? <img src={imageUrl(item.Id, "Primary", { maxHeight: 240, quality: 80 })} alt="" loading="lazy" /> : null}</span>;
}

// ---------- the night ----------

function Night({ session, offline, onChange, onLeave }: { session: Session; offline: boolean; onChange: (s: Session) => void; onLeave: () => void }) {
  const { films, failed: titlesFailed, retry: retryTitles } = useFilms(session.items);
  const [err, setErr] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const rows = useMemo(() => tally(session.items, session.counts), [session.items, session.counts]);
  const done = finished(session);
  const top = done ? winner(rows) : null;
  const next = nextToVote(session);
  const link = shareLink(location.href, session.code);
  const mins = minutesLeft(session, Math.floor(Date.now() / 1000));
  const total = session.items.length;
  const filmName = (id: string) => films.get(id)?.Name ?? (titlesFailed ? "Title not loaded" : films.size ? "A film you can't see" : "Loading…");

  const vote = useCallback(
    async (item: string, v: Vote) => {
      setErr(null);
      const r = await voteNight(session.code, item, v);
      if (r.ok) onChange(r.session);
      else setErr(r.error);
    },
    [session.code, onChange],
  );
  const close = async () => {
    setErr(null);
    const r = await closeNight(session.code);
    if (r.ok) onChange(r.session);
    else setErr(r.error);
  };
  const share = async () => {
    try {
      if (navigator.share) await navigator.share({ title: "Movie night", text: `Join our movie night, code ${showCode(session.code)}`, url: link });
      else {
        await navigator.clipboard.writeText(link);
        setCopied(true);
        window.setTimeout(() => setCopied(false), 2000);
      }
    } catch {
      /* cancelled share, or no clipboard: the code is on screen */
    }
  };

  return (
    <div className="mnight-night">
      <section className="mnight-card mnight-head">
        <div>
          <span className="mnight-label">Code</span>
          <div className="mnight-code" aria-label={`Code ${session.code.split("").join(" ")}`}>{showCode(session.code)}</div>
          <span className="tonight-sub">{session.status === "closed" ? "Voting closed" : `Open for about ${mins >= 60 ? `${Math.round(mins / 60)} h` : `${mins} min`}`}</span>
        </div>
        {session.status === "open" && (
          <button className="btn" data-nav onClick={share}>
            {copied ? "Link copied" : "Share link"}
          </button>
        )}
      </section>

      {offline && <p className="ask-sub" role="status">Can't reach the server. Showing the last update.</p>}
      {titlesFailed && (
        <p className="ask-sub" role="status">
          Couldn't load the film titles.{" "}
          <button className="btn small" data-nav onClick={retryTitles}>
            Try again
          </button>
        </p>
      )}

      <section className="mnight-people" aria-label="Who's in">
        {session.members.map((m, i) => (
          <span key={i} className={`mnight-person${m.voted >= total ? " done" : ""}`}>
            {m.me ? "You" : m.name || "Someone"} · {m.voted}/{total}
          </span>
        ))}
      </section>

      {done && (
        <section className="mnight-card mnight-winner" aria-live="polite">
          {top ? (
            <>
              <span className="mnight-label">Top match</span>
              <div className="mnight-winner-row">
                <Art item={films.get(top.id)} />
                <div className="mnight-winner-text">
                  <h2>{filmName(top.id)}</h2>
                  <p className="tonight-sub">{top.counts.yes} yes · {top.counts.maybe} maybe · {top.counts.no} no</p>
                  {films.get(top.id) && (
                    <button className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${top.id}`)}>
                      Play
                    </button>
                  )}
                </div>
              </div>
            </>
          ) : (
            <p className="ask-sub">Nobody wanted any of these. Start another with different films.</p>
          )}
        </section>
      )}

      {!done && (
        <p className="ask-sub" aria-live="polite">
          {session.members.length < 2 ? "Waiting for someone to join. Share the code." : next ? "Vote on each film below." : "You're done. Waiting for the others."}
        </p>
      )}

      <ul className="mnight-films">
        {rows.map((r, idx) => {
          const f = films.get(r.id);
          const mine = session.mine[r.id];
          const lead = done && top?.id === r.id;
          return (
            <li key={r.id} className={`mnight-film${lead ? " lead" : ""}`}>
              <Art item={f} />
              <div className="mnight-film-body">
                <strong>{filmName(r.id)}</strong>
                <span className="tonight-sub">{[f?.ProductionYear, `${r.counts.yes} yes · ${r.counts.maybe} maybe · ${r.counts.no} no`].filter(Boolean).join(" · ")}</span>
                {session.status === "open" ? (
                  <div className="mnight-votes" role="group" aria-label={`Your vote for ${filmName(r.id)}`}>
                    {(["yes", "maybe", "no"] as Vote[]).map((v) => (
                      <button key={v} className={`chip${mine === v ? " on" : ""}`} data-nav data-autofocus={idx === 0 && !done ? true : undefined} aria-pressed={mine === v} onClick={() => vote(r.id, v)}>
                        {LABEL[v]}
                      </button>
                    ))}
                  </div>
                ) : (
                  mine && <span className="tonight-sub">You said {LABEL[mine].toLowerCase()}</span>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      {err && <p className="mnight-err" role="alert">{err}</p>}
      <div className="mnight-actions">
        {session.host && session.status === "open" && (
          <button className="btn primary" data-nav onClick={close}>
            Close voting and show result
          </button>
        )}
        <button className="btn ghost" data-nav onClick={onLeave}>
          {done ? "Done" : session.host && session.status === "open" ? "Leave (the night stays open until you close it)" : "Leave (you can rejoin with the code)"}
        </button>
      </div>
    </div>
  );
}
