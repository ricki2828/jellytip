// #/tonight: "Pick for tonight". Three picks with one factual reason each, tap-able constraints, "Different three".
// One batch of parallel requests on every visit (lib/tonight.ts does the ranking).
import { useEffect, useMemo, useRef, useState } from "react";
import { getItems, imageUrl, LIST_FIELDS, nextUp, resumeItems, uid, type Item } from "../api/jellyfin";
import { manifest, serverRows } from "../api/companion";
import { iptvContinue, iptvNext } from "../api/iptv";
import { signalOf } from "../api/me";
import { buildContinue, readDismissed } from "../lib/continueRow";
import type { ContinueEntry } from "../lib/continue";
import type { VodItem } from "../lib/iptvVod";
import { buildQuery } from "../lib/askParse";
import { CONSTRAINTS, HIGH_RATING, candidates, chipsFor, nextThree, shownFor, type Cand, type ConstraintId, type Pool } from "../lib/tonight";
import { go } from "../lib/router";
import { logoSrc } from "../api/iptv";
import "./Tonight.css";

const undash = (s: string) => s.replace(/-/g, "");
const ROWS = 5;
const PER_ROW = 8;

export interface Raw {
  resume: Item[];
  next: Item[];
  ic: ContinueEntry[];
  inx: ContinueEntry[];
  recs: Pool["recs"];
  iptv: VodItem[];
}

/** Highly rated unwatched films; the kids rating and mood genres go to the server through askParse's own mapping. */
export const ratedFor = (ids: ConstraintId[]): Promise<Item[]> =>
  getItems({
    ...buildQuery(chipsFor(ids), { limit: 60 }),
    IncludeItemTypes: "Movie",
    Filters: "IsUnplayed",
    MinCommunityRating: HIGH_RATING,
    SortBy: "CommunityRating,SortName",
    SortOrder: "Descending",
    Fields: LIST_FIELDS,
  }).then((r) => r.Items);

// Nothing here is cached across visits: what was just watched or stopped must not come back. (The companion layer caches
// the static row definitions itself.)
export async function load(): Promise<Raw> {
  const [resume, next, ic, inx, rows, mf] = await Promise.all([
    resumeItems(),
    nextUp(),
    iptvContinue().catch(() => [] as ContinueEntry[]),
    iptvNext().catch(() => [] as ContinueEntry[]),
    serverRows("home").catch(() => null),
    manifest().catch(() => null),
  ]);
  const from = new Map<string, { title: string; list?: string | null }>();
  const order: string[] = [];
  const take = (ids: string[], f: { title: string; list?: string | null }) => {
    for (const id of ids.slice(0, PER_ROW)) {
      const k = undash(id);
      if (!from.has(k)) {
        from.set(k, f);
        order.push(k);
      }
    }
  };
  const recRows = (rows ?? []).filter((r) => r.source !== "iptv" && !r.person && !r.collection && !r.iptvItems.length && r.mediaType === "movie" && !["fresh", "ratings", "awards"].includes(r.family) && !r.id.startsWith("trending"));
  for (const r of recRows.slice(0, ROWS)) take(r.itemIds, { title: r.title });
  // Only the Letterboxd lists from the manifest are called Letterboxd
  for (const l of (mf?.letterboxd ?? []).filter((x) => x.mediaType !== "tv").slice(0, 3)) take(l.itemIds, { title: l.name, list: l.name });
  const items = order.length ? await getItems({ Ids: order.join(","), Fields: "Genres,PrimaryImageAspectRatio" }).then((r) => r.Items).catch(() => [] as Item[]) : [];
  const byId = new Map(items.map((i) => [undash(i.Id), i]));
  const recs = order.flatMap((k) => (byId.get(k) ? [{ item: byId.get(k)!, from: from.get(k)! }] : []));
  const iptv = (rows ?? []).flatMap((r) => r.iptvItems).slice(0, 40);
  return { resume, next, ic, inx, recs, iptv };
}

export const toPool = (r: Raw, rated: Item[]): Pool => {
  const dismissed = readDismissed(localStorage, uid());
  const hidden = new Set(Object.keys(dismissed));
  const entries = buildContinue(r.resume, r.next, r.ic, r.inx, { dismissed });
  const blocked = new Set<string>();
  for (const i of [...r.recs.map((x) => x.item), ...rated, ...r.resume, ...r.next]) {
    if (signalOf(i.Id).notInterested || signalOf(undash(i.Id)).notInterested) blocked.add(undash(i.Id));
    if (i.SeriesId && (signalOf(i.SeriesId).notInterested || signalOf(undash(i.SeriesId)).notInterested)) blocked.add(undash(i.SeriesId));
  }
  return { entries, recs: r.recs, rated, iptv: r.iptv, hidden, blocked };
};

// Which sets of picks were shown this session, per constraint set (resets when the page is reloaded)
const shownStore = new Map<string, Set<string>>();

export function Tonight() {
  const [data, setData] = useState<Raw | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [on, setOn] = useState<ConstraintId[]>([]);
  // The rated films fetched for a set of constraints, and which set that was
  const [view, setView] = useState<{ rated: Item[]; ids: ConstraintId[] } | null>(null);
  const [picks, setPicks] = useState<Cand[] | null>(null);
  const [exhausted, setExhausted] = useState(false);
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    setFailed(false);
    setData(null);
    load().then((r) => alive && setData(r), () => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [attempt]);

  const key = on.join("+");
  useEffect(() => {
    if (!data) return;
    let alive = true;
    ratedFor(on).then(
      (rated) => alive && setView({ rated, ids: on }),
      // the rated fetch is one source of several: without it the rest still answers
      () => alive && setView({ rated: [], ids: on }),
    );
    return () => {
      alive = false;
    };
  }, [data, key]); // eslint-disable-line react-hooks/exhaustive-deps

  const cands = useMemo(() => (data && view ? candidates(toPool(data, view.rated)) : null), [data, view]);

  // New data or constraints: a fresh first three
  useEffect(() => {
    if (!cands || !view) return;
    shownStore.delete([...view.ids].sort().join("+"));
    setPicks(nextThree(cands, view.ids, shownFor(shownStore, view.ids)));
    setExhausted(false);
  }, [cands]); // eslint-disable-line react-hooks/exhaustive-deps

  const refocus = () => requestAnimationFrame(() => list.current?.querySelector<HTMLElement>("[data-autofocus]")?.focus());
  const more = () => {
    if (!cands || !view) return;
    const n = nextThree(cands, view.ids, shownFor(shownStore, view.ids));
    if (n.length) {
      setPicks(n);
      refocus();
    } else setExhausted(true);
  };
  const startOver = () => {
    if (!cands || !view) return;
    shownStore.delete([...view.ids].sort().join("+"));
    setPicks(nextThree(cands, view.ids, shownFor(shownStore, view.ids)));
    setExhausted(false);
    refocus();
  };
  const toggle = (id: ConstraintId) => setOn((o) => (o.includes(id) ? o.filter((x) => x !== id) : [...o, id]));

  return (
    <div className="tonight">
      <h1 className="page-title">Pick for tonight</h1>
      <div className="tonight-more">
        <button className="btn" data-nav onClick={() => go("/movienight")}>
          Movie night with others
        </button>
      </div>
      <div className="chips" aria-label="Narrow it down">
        {CONSTRAINTS.map((c) => (
          <button key={c.id} className={`chip${on.includes(c.id) ? " on" : ""}`} data-nav aria-pressed={on.includes(c.id)} onClick={() => toggle(c.id)}>
            {c.label}
          </button>
        ))}
      </div>
      {failed && (
        <p className="ask-sub">
          Couldn't load your library.{" "}
          <button className="btn small" data-nav data-autofocus onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        </p>
      )}
      {!data && !failed && <p className="ask-sub">Looking…</p>}
      {picks && (
        <div className="tonight-list" ref={list} aria-live="polite">
          {picks.map((c, i) => (
            <article key={c.key} className="tonight-pick">
              <div className="tonight-art" aria-hidden>
                {c.artId ? <img src={imageUrl(c.artId, "Primary", { maxHeight: 360, quality: 80 })} alt="" loading="lazy" /> : c.poster ? <img src={logoSrc(c.poster, 480) ?? c.poster} alt="" loading="lazy" /> : null}
              </div>
              <div className="tonight-body">
                <h2>{c.title}</h2>
                {c.sub && <p className="tonight-sub">{c.sub}</p>}
                <p className="tonight-reason">{c.reason}</p>
                <button className="btn primary" data-nav data-autofocus={i === 0 ? true : undefined} onClick={() => go(c.play)}>
                  {c.playLabel}
                </button>
              </div>
            </article>
          ))}
          {picks.length === 0 && <p className="ask-sub">{on.length ? "Nothing fits all of that. Try fewer." : "Nothing to suggest yet."}</p>}
        </div>
      )}
      {cands && picks && picks.length > 0 && (
        <div className="tonight-more">
          {exhausted ? (
            <p className="ask-sub">
              That's everything that fits. Start over?{" "}
              <button className="btn" data-nav onClick={startOver}>
                Start over
              </button>
            </p>
          ) : (
            <button className="btn" data-nav onClick={more}>
              Different three
            </button>
          )}
        </div>
      )}
    </div>
  );
}
