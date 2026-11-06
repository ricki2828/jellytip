// Library management centre (admins only; the server refuses anyone else with 403): library overview, items with
// missing metadata / subtitles, refresh + find-subtitles per item, scan library, running server tasks.
// Delete is deliberately absent: the client has no existing delete flow to reuse.
import { useCallback, useEffect, useRef, useState } from "react";
import "./Manage.css";
import { getItem, getItems, getSession, jf, type Item } from "../api/jellyfin";
import { usePrefs } from "../api/me";
import { SubtitleSearchDialog } from "../components/SubtitleSearchDialog";
import { defaultSearchLanguage, languageName } from "../lib/remoteSubtitles";
import { FILTERS, PAGE_SIZE, SCAN_PATH, TASKS_PATH, buildCountQuery, buildItemsQuery, classify, createTapGate, errorText, mapLibraries, mapTasks, matchesFilter, refreshPath, runningTasks, startIntervalPoll, validId, type Library, type ManageFilter, type ManageItem, type Task } from "../lib/manage";
import { go } from "../lib/router";

const asManage = (i: Item): ManageItem => i as unknown as ManageItem;
const label = (i: Item) => (i.Type === "Episode" ? `${i.SeriesName ?? ""} S${i.ParentIndexNumber ?? "?"}E${i.IndexNumber ?? "?"} · ${i.Name}` : `${i.Name}${i.ProductionYear ? ` (${i.ProductionYear})` : ""}`);

function Tasks() {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    const p = startIntervalPoll<Task[]>({
      fetch: async () => {
        try {
          return { kind: "ok", value: mapTasks(await jf(TASKS_PATH)) };
        } catch (e) {
          const s = (e as { status?: number }).status;
          return s === 401 || s === 403 ? { kind: "denied" } : { kind: "error" };
        }
      },
      onResult: (r) => {
        if (r.kind === "ok") (setTasks(r.value), setErr(null));
        else setErr(r.kind === "denied" ? "Admin only: the server refused this account." : "Could not read server tasks (will retry).");
      },
      env: { setTimer: (fn, ms) => window.setTimeout(fn, ms), clearTimer: (t) => window.clearTimeout(t as number), hidden: () => document.hidden, onVisible: (cb) => { const on = () => !document.hidden && cb(); document.addEventListener("visibilitychange", on); return () => document.removeEventListener("visibilitychange", on); } },
    });
    return () => p.cancel();
  }, []);
  const running = tasks ? runningTasks(tasks) : [];
  return (
    <section className="manage-card" aria-label="Server jobs">
      <h2>Server jobs</h2>
      {err && <p className="manage-err">{err}</p>}
      {tasks && running.length === 0 && !err && <p className="manage-dim">Nothing running ({tasks.length} scheduled tasks). Refreshes every 10 s.</p>}
      {running.map((t) => (
        <div key={t.id} className="manage-task">
          <span>{t.name}</span>
          <span className="manage-dim">{t.state === "Cancelling" ? "cancelling" : t.percent != null ? `${t.percent}%` : "running"}</span>
          <div className="manage-bar"><i style={{ width: `${t.percent ?? 0}%` }} /></div>
        </div>
      ))}
    </section>
  );
}

export function Manage({ itemId }: { itemId?: string | null }) {
  const session = getSession();
  const { prefs } = usePrefs({ defer: true });
  const lang = defaultSearchLanguage(prefs?.playback.subtitleLangs);
  const [libs, setLibs] = useState<Library[] | null>(null);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [sel, setSel] = useState<Library | null>(null);
  const [filter, setFilter] = useState<ManageFilter>("recent");
  const [items, setItems] = useState<Item[]>([]);
  const [next, setNext] = useState<number | null>(0);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [subsFor, setSubsFor] = useState<Item | null>(null);
  const [single, setSingle] = useState<Item | null>(null);
  const [armed, setArmed] = useState(false);
  const gate = useRef(createTapGate());
  const gen = useRef(0); // drops answers from a previous library / filter

  // libraries + counts
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const ls = mapLibraries(await jf("/Library/VirtualFolders"));
        if (!alive) return;
        setLibs(ls);
        setSel((cur) => cur ?? ls[0] ?? null);
        for (const l of ls) {
          getItems(buildCountQuery(l)).then((r) => alive && setCounts((c) => ({ ...c, [l.id]: r.TotalRecordCount ?? 0 }))).catch(() => undefined);
        }
      } catch (e) {
        if (alive) setErr(errorText(e, "Reading libraries"));
      }
    })();
    return () => { alive = false; };
  }, []);

  // single item (from Details)
  useEffect(() => {
    if (!itemId || !validId(itemId)) return setSingle(null);
    let alive = true;
    getItem(itemId).then((i) => alive && setSingle(i)).catch((e) => alive && setErr(errorText(e, "Reading the item")));
    return () => { alive = false; };
  }, [itemId]);

  const load = useCallback(async (lib: Library, f: ManageFilter, start: number, replace: boolean) => {
    const my = ++gen.current;
    setLoading(true);
    setErr(null);
    try {
      let at = start;
      let got: Item[] = [];
      let more: number | null = null;
      // client-classified filters can return a page with few hits: keep reading pages (bounded) until something shows up
      for (let hops = 0; hops < 6; hops++) {
        const r = await getItems(buildItemsQuery(lib, f, at, PAGE_SIZE));
        if (my !== gen.current) return;
        const page = r.Items ?? [];
        const total = r.TotalRecordCount ?? 0;
        at += page.length;
        more = f === "recent" || page.length === 0 || at >= total ? null : at;
        got = got.concat(page.filter((i) => matchesFilter(asManage(i), f, lang)));
        if (got.length >= 10 || more == null) break;
      }
      setItems((cur) => (replace ? got : cur.concat(got)));
      setNext(more);
    } catch (e) {
      if (my === gen.current) setErr(errorText(e, "Reading the library"));
    } finally {
      if (my === gen.current) setLoading(false);
    }
  }, [lang]);

  useEffect(() => {
    if (sel) void load(sel, filter, 0, true);
  }, [sel, filter, load]);

  const refresh = async (i: Item, full: boolean) => {
    setMsg(null);
    try {
      await jf(refreshPath(i.Id, { full }), { method: "POST" });
      setMsg(`Refresh queued for ${i.Name}${full ? " (full)" : ""}. It runs on the server; check Server jobs.`);
    } catch (e) {
      setMsg(errorText(e, "Refresh"));
    }
  };

  const scan = async () => {
    const now = Date.now();
    if (!gate.current.isArmed("scan", now)) {
      gate.current.arm("scan", now);
      setArmed(true);
      window.setTimeout(() => setArmed(false), 8000);
      return;
    }
    if (!gate.current.confirm("scan", now)) return;
    setArmed(false);
    try {
      await jf(SCAN_PATH, { method: "POST" });
      setMsg("Library scan started on the server.");
    } catch (e) {
      setMsg(errorText(e, "Library scan"));
    }
  };

  if (!session?.isAdmin) return <div className="manage"><p className="manage-err">Admin only.</p></div>;

  const rowFor = (i: Item) => {
    const c = classify(asManage(i), lang);
    const flags = [c.overview && "no overview", c.poster && "no poster", c.subs && `no ${languageName(lang)} subtitles`].filter(Boolean).join(" · ");
    return (
      <li key={i.Id} className="manage-row">
        <button className="manage-title" data-nav onClick={() => go(`/item/${i.Id}`)}>{label(i)}</button>
        <span className="manage-dim">{i.Type}{flags ? ` · ${flags}` : ""}</span>
        <span className="manage-actions">
          <button className="chip" data-nav onClick={() => void refresh(i, false)}>Refresh</button>
          <button className="chip" data-nav onClick={() => void refresh(i, true)} title="Full refresh: re-reads all metadata sources (existing images and edits are kept)">Full refresh</button>
          {(i.Type === "Movie" || i.Type === "Episode") && <button className="chip" data-nav aria-haspopup="dialog" onClick={() => setSubsFor(i)}>Find subtitles</button>}
        </span>
      </li>
    );
  };

  return (
    <div className="manage">
      <h1>Manage library</h1>
      {err && <p className="manage-err" role="alert">{err}</p>}
      {msg && <p className="manage-msg" role="status">{msg}</p>}
      <Tasks />
      {single && (
        <section className="manage-card" aria-label="This item">
          <h2>{label(single)}</h2>
          <ul className="manage-list">{rowFor(single)}</ul>
        </section>
      )}
      <section className="manage-card" aria-label="Libraries">
        <h2>Libraries</h2>
        <div className="chips">
          {(libs ?? []).map((l) => (
            <button key={l.id} data-nav className={`chip${sel?.id === l.id ? " on" : ""}`} onClick={() => setSel(l)}>
              {l.name}{counts[l.id] != null ? ` · ${counts[l.id]}` : ""}
            </button>
          ))}
          <button className={`chip manage-scan${armed ? " armed" : ""}`} data-nav onClick={() => void scan()}>
            {armed ? "Tap again to scan all libraries" : "Scan library"}
          </button>
        </div>
        <div className="chips">
          {FILTERS.map(([k, t]) => (
            <button key={k} data-nav className={`chip${filter === k ? " on" : ""}`} onClick={() => setFilter(k)}>{t}</button>
          ))}
        </div>
        {filter === "no-subs" && <p className="manage-dim">Looking for {languageName(lang)} (your preferred subtitle language). Series are skipped: check their episodes.</p>}
        <ul className="manage-list">{items.map(rowFor)}</ul>
        {!loading && items.length === 0 && !err && sel && <p className="manage-dim">Nothing found here.</p>}
        {loading && <p className="manage-dim">Loading…</p>}
        {next != null && !loading && <button className="btn" data-nav onClick={() => sel && void load(sel, filter, next, false)}>Load more</button>}
      </section>
      {subsFor && (
        <SubtitleSearchDialog itemId={subsFor.Id} title={label(subsFor)} subtitleLangs={prefs?.playback.subtitleLangs} where="page" onClose={() => setSubsFor(null)} />
      )}
    </div>
  );
}
