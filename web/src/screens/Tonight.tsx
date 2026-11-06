// #/tonight: "Pick for tonight". Three picks with one factual reason each, tap-able constraints, "Different three".
// One batch of parallel requests, cached per session for 10 minutes (lib/tonight.ts does the ranking).
import { useEffect, useMemo, useRef, useState } from "react";
import { getItems, imageUrl, nextUp, resumeItems, uid, type Item } from "../api/jellyfin";
import { manifest, serverRows } from "../api/companion";
import { iptvContinue, iptvNext } from "../api/iptv";
import { signalOf } from "../api/me";
import { buildContinue, readDismissed } from "../lib/continueRow";
import type { ContinueEntry } from "../lib/continue";
import type { VodItem } from "../lib/iptvVod";
import { CACHE_MS, CONSTRAINTS, HIGH_RATING, candidates, nextThree, shownFor, type Cand, type ConstraintId, type Pool } from "../lib/tonight";
import { go } from "../lib/router";
import { logoSrc } from "../api/iptv";
import "./Tonight.css";

const undash = (s: string) => s.replace(/-/g, "");
const ROWS = 5;
const PER_ROW = 8;

interface Raw {
  resume: Item[];
  next: Item[];
  ic: ContinueEntry[];
  inx: ContinueEntry[];
  recs: Pool["recs"];
  rated: Item[];
  iptv: VodItem[];
}

const cache = new Map<string, { at: number; p: Promise<Raw> }>();
window.addEventListener("jellytip-session", () => cache.clear());

async function load(): Promise<Raw> {
  const [resume, next, ic, inx, rows, mf, rated] = await Promise.all([
    resumeItems().catch(() => [] as Item[]),
    nextUp().catch(() => [] as Item[]),
    iptvContinue().catch(() => [] as ContinueEntry[]),
    iptvNext().catch(() => [] as ContinueEntry[]),
    serverRows("home").catch(() => null),
    manifest().catch(() => null),
    getItems({ IncludeItemTypes: "Movie", Recursive: true, Filters: "IsUnplayed", SortBy: "CommunityRating,SortName", SortOrder: "Descending", MinCommunityRating: HIGH_RATING, Limit: 60 })
      .then((r) => r.Items)
      .catch(() => [] as Item[]),
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
  for (const r of recRows.slice(0, ROWS)) take(r.itemIds, { title: r.title, list: r.list ? r.title : null });
  for (const l of (mf?.letterboxd ?? []).filter((x) => x.mediaType !== "tv").slice(0, 3)) take(l.itemIds, { title: l.name, list: l.name });
  const items = order.length ? await getItems({ Ids: order.join(","), Fields: "Genres,PrimaryImageAspectRatio" }).then((r) => r.Items).catch(() => [] as Item[]) : [];
  const byId = new Map(items.map((i) => [undash(i.Id), i]));
  const recs = order.flatMap((k) => (byId.get(k) ? [{ item: byId.get(k)!, from: from.get(k)! }] : []));
  const iptv = (rows ?? []).flatMap((r) => r.iptvItems).slice(0, 40);
  return { resume, next, ic, inx, recs, rated, iptv };
}

function raw(): Promise<Raw> {
  const k = uid();
  const hit = cache.get(k);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.p;
  const p = load();
  cache.set(k, { at: Date.now(), p });
  p.catch(() => cache.delete(k));
  return p;
}

const toPool = (r: Raw): Pool => {
  const dismissed = readDismissed(localStorage, uid());
  const hidden = new Set(Object.keys(dismissed));
  const entries = buildContinue(r.resume, r.next, r.ic, r.inx, { dismissed });
  const blocked = new Set<string>();
  for (const i of [...r.recs.map((x) => x.item), ...r.rated, ...r.resume, ...r.next]) if (signalOf(i.Id).notInterested || signalOf(undash(i.Id)).notInterested) blocked.add(undash(i.Id));
  return { entries, recs: r.recs, rated: r.rated, iptv: r.iptv, hidden, blocked };
};

// Which sets of picks were shown this session, per constraint set (resets when the page is reloaded)
const shownStore = new Map<string, Set<string>>();

export function Tonight() {
  const [data, setData] = useState<Raw | null>(null);
  const [failed, setFailed] = useState(false);
  const [on, setOn] = useState<ConstraintId[]>([]);
  const [picks, setPicks] = useState<Cand[] | null>(null);
  const [round, setRound] = useState(0);
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    raw().then((r) => alive && setData(r), () => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  const cands = useMemo(() => (data ? candidates(toPool(data)) : null), [data]);

  // Constraint change: a fresh first three. "Different three": the next ones, none shown before for this constraint set.
  const key = on.join("+");
  useEffect(() => {
    if (!cands) return;
    shownStore.delete([...on].sort().join("+"));
    setPicks(nextThree(cands, on, shownFor(shownStore, on)));
    setRound(0);
  }, [cands, key]); // eslint-disable-line react-hooks/exhaustive-deps

  const more = () => {
    if (!cands) return;
    const n = nextThree(cands, on, shownFor(shownStore, on));
    if (n.length) {
      setPicks(n);
      setRound((r) => r + 1);
      requestAnimationFrame(() => list.current?.querySelector<HTMLElement>("[data-autofocus]")?.focus());
    }
  };
  const toggle = (id: ConstraintId) => setOn((o) => (o.includes(id) ? o.filter((x) => x !== id) : [...o, id]));
  const exhausted = !!picks && picks.length < 3 && round > 0;

  return (
    <div className="tonight">
      <h1 className="page-title">Pick for tonight</h1>
      <div className="chips" aria-label="Narrow it down">
        {CONSTRAINTS.map((c) => (
          <button key={c.id} className={`chip${on.includes(c.id) ? " on" : ""}`} data-nav aria-pressed={on.includes(c.id)} onClick={() => toggle(c.id)}>
            {c.label}
          </button>
        ))}
      </div>
      {failed && <p className="ask-sub">Couldn't load your library. Try again in a moment.</p>}
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
          {picks.length === 0 && <p className="ask-sub">{round > 0 ? "That's everything that fits." : on.length ? "Nothing fits all of that. Try fewer." : "Nothing to suggest yet."}</p>}
          {exhausted && <p className="ask-sub">That's everything that fits.</p>}
        </div>
      )}
      {cands && (
        <div className="tonight-more">
          <button className="btn" data-nav onClick={more} disabled={!picks || (picks.length === 0 && round > 0)}>
            Different three
          </button>
        </div>
      )}
    </div>
  );
}
