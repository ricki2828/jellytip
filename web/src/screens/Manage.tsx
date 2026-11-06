// Library management centre (admins only; the server refuses anyone else with 403): library overview, items with
// missing metadata / subtitles, refresh + find-subtitles per item, scan library, running server tasks.
// Delete is deliberately absent: the client has no existing delete flow to reuse.
import { useCallback, useEffect, useRef, useState } from "react";
import "./Manage.css";
import { getItem, getItems, getSession, jf, type Item } from "../api/jellyfin";
import { usePrefs } from "../api/me";
import { SubtitleSearchDialog } from "../components/SubtitleSearchDialog";
import { defaultSearchLanguage, languageName } from "../lib/remoteSubtitles";
import { PAGE_SIZE, SCAN_PATH, TASKS_PATH, buildCountQuery, buildItemsQuery, classify, createArm, errorText, filtersFor, loadPages, mapLibraries, mapTasks, mergeById, refreshPath, runningTasks, startIntervalPoll, validId, type Library, type ManageFilter, type ManageItem, type Task } from "../lib/manage";
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
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const langRef = useRef(lang);
  langRef.current = lang;
  const focusFrom = useRef<number | null>(null);
  const arm = useRef<ReturnType<typeof createArm> | null>(null);
  if (!arm.current) arm.current = createArm({ setTimer: (fn, ms) => window.setTimeout(fn, ms), clearTimer: (t) => window.clearTimeout(t as number), now: () => Date.now() }, setArmed);
  useEffect(() => () => arm.current?.dispose(), []);
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
      const r = await loadPages<Item & ManageItem>({ fetchPage: (at) => getItems(buildItemsQuery(lib, f, at, PAGE_SIZE)) as Promise<{ Items?: (Item & ManageItem)[]; TotalRecordCount?: number }>, filter: f, lang: langRef.current, start, current: () => my === gen.current });
      if (!r) return;
      if (!replace) focusFrom.current = itemsLen.current;
      setItems((cur) => (replace ? r.items : mergeById(cur, r.items)));
      setNext(r.next);
    } catch (e) {
      if (my === gen.current) setErr(errorText(e, "Reading the library"));
    } finally {
      if (my === gen.current) setLoading(false);
    }
  }, []);

  const itemsLen = useRef(0);
  itemsLen.current = items.length;
  // the language only matters to "No subtitles": changing it (prefs arriving) reloads that filter only
  const subLang = filter === "no-subs" ? lang : "";
  useEffect(() => {
    if (sel) void load(sel, filter, 0, true);
  }, [sel, filter, subLang, load]);

  // after "Load more": focus the first new row
  useEffect(() => {
    const from = focusFrom.current;
    if (from == null || loading) return;
    focusFrom.current = null;
    document.querySelectorAll<HTMLElement>(".manage-list .manage-row .manage-title")[from]?.focus();
  }, [items, loading]);

  const refresh = async (i: Item, full: boolean) => {
    const key = `${i.Id}:${full}`;
    if (busy.has(key)) return;
    setBusy((b) => new Set(b).add(key));
    setMsg(null);
    try {
      await jf(refreshPath(i.Id, { full }), { method: "POST" });
      setMsg(`Refresh queued for ${i.Name}${full ? " (full)" : ""}. It runs on the server; check Server jobs.`);
    } catch (e) {
      setMsg(errorText(e, "Refresh"));
    } finally {
      setBusy((b) => {
        const n = new Set(b);
        n.delete(key);
        return n;
      });
    }
  };

  const scan = async () => {
    if (!arm.current?.press("scan")) return;
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
    const flags = [c.overview && "no overview", c.poster && "no poster", c.subs && `no ${languageName(lang)} subtitles`, c.untagged && "untagged subtitles"].filter(Boolean).join(" · ");
    return (
      <li key={i.Id} className="manage-row">
        <button className="manage-title" data-nav onClick={() => go(`/item/${i.Id}`)}>{label(i)}</button>
        <span className="manage-dim">{i.Type}{flags ? ` · ${flags}` : ""}</span>
        <span className="manage-actions">
          <button className="chip" data-nav disabled={busy.has(`${i.Id}:false`)} onClick={() => void refresh(i, false)}>Refresh</button>
          <button className="chip" data-nav disabled={busy.has(`${i.Id}:true`)} onClick={() => void refresh(i, true)} title="Re-reads metadata from providers and local files; images are kept">Full refresh</button>
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
            <button key={l.id} data-nav className={`chip${sel?.id === l.id ? " on" : ""}`} onClick={() => { if (l.kind === "tvshows" && filter === "no-subs") setFilter("recent"); setSel(l); }}>
              {l.name}{counts[l.id] != null ? ` · ${counts[l.id]}` : ""}
            </button>
          ))}
          <button className={`chip manage-scan${armed ? " armed" : ""}`} data-nav onClick={() => void scan()}>
            {armed ? "Tap again to scan all libraries" : "Scan library"}
          </button>
        </div>
        <div className="chips">
          {filtersFor(sel?.kind ?? "other").map(([k, t]) => (
            <button key={k} data-nav className={`chip${filter === k ? " on" : ""}`} onClick={() => setFilter(k)}>{t}</button>
          ))}
        </div>
        {filter === "no-subs" && <p className="manage-dim">Looking for {languageName(lang)} (your preferred subtitle language). Series are skipped: check their episodes.</p>}
        <ul className="manage-list">{items.map(rowFor)}</ul>
        {!loading && items.length === 0 && !err && sel && <p className="manage-dim">Nothing found here.</p>}
        {(next != null || (loading && items.length > 0)) && (
          <button className="btn" data-nav disabled={loading} onClick={() => next != null && sel && void load(sel, filter, next, false)}>{loading ? "Loading…" : "Load more"}</button>
        )}
        {loading && items.length === 0 && <p className="manage-dim">Loading…</p>}
      </section>
      {subsFor && (
        <SubtitleSearchDialog itemId={subsFor.Id} title={label(subsFor)} subtitleLangs={prefs?.playback.subtitleLangs} where="page" onClose={() => setSubsFor(null)} />
      )}
    </div>
  );
}
