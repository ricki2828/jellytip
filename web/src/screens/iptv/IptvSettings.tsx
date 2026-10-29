// IPTV settings (#/iptv/settings): each person's own provider(s). Add / test / edit / remove (logins typed once, never
// shown back), connection status, opt-in groups for live channels, films and series (English only by default), sync
// progress, refresh, sharing (admins pick users), recordings with storage against the cap.
import { useCallback, useEffect, useRef, useState } from "react";
import {
  addProvider,
  categories as getCategories,
  deleteProvider,
  editProvider,
  errorText,
  iptvChanged,
  iptvGet,
  optCategories,
  providers as getProviders,
  refreshProvider,
  users as getUsers,
  type Answer,
  type CatType,
  type Category,
  type CategoryList,
  type JfUser,
  type Preview,
  type Provider,
  type SyncStage,
} from "../../api/iptv";
import { getSession } from "../../api/jellyfin";
import { Recordings } from "./Recordings";
import "./iptv.css";

const POLL_MS = 3000;

const fmtDate = (v: number | string | null | undefined) => {
  if (v == null || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  const d = Number.isFinite(n) ? new Date(n < 1e12 ? n * 1000 : n) : new Date(String(v));
  return Number.isNaN(d.getTime()) ? null : d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
};
const ago = (t?: number | null) => {
  if (!t) return null;
  const s = Date.now() / 1000 - (t > 1e12 ? t / 1000 : t);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400 * 2) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} days ago`;
};
const n = (v?: number) => (v ?? 0).toLocaleString();

// ---------- add / edit form ----------

function ProviderForm({ existing, onDone, onCancel }: { existing?: Provider; onDone: (p: Provider) => void; onCancel: () => void }) {
  const [kind, setKind] = useState<"xtream" | "m3u">(existing?.kind ?? "xtream");
  const [name, setName] = useState(existing?.name ?? "");
  const [server, setServer] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [playlistUrl, setPlaylistUrl] = useState("");
  const [xmltvUrl, setXmltvUrl] = useState("");
  const [englishOnly, setEnglishOnly] = useState(existing?.englishOnly ?? true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const editing = !!existing;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    const body: Record<string, unknown> = { name: name.trim() || undefined, englishOnly };
    if (kind === "xtream") {
      if (!editing && (!server.trim() || !username.trim() || !password)) return setErr("Server, username and password are all needed.");
      // Editing: blank login fields stay as they are on the server
      if (server.trim()) body.server = server.trim();
      if (username.trim()) body.username = username.trim();
      if (password) body.password = password;
    } else {
      if (!editing && !playlistUrl.trim()) return setErr("The playlist (M3U) address is needed.");
      if (playlistUrl.trim()) body.playlistUrl = playlistUrl.trim();
      if (xmltvUrl.trim()) body.xmltvUrl = xmltvUrl.trim();
    }
    setBusy(true);
    const a = editing ? await editProvider(existing.id, body) : await addProvider({ kind, ...body });
    setBusy(false);
    setPassword("");
    if (a.status >= 200 && a.status < 300 && a.data) onDone(a.data);
    else setErr(a.data?.code === "rate_limited" ? "Too many login changes: try again in an hour." : errorText(a));
  };

  return (
    <form className="iptv-form" onSubmit={submit} autoComplete="off">
      {!editing && (
        <div className="chips" role="radiogroup" aria-label="Provider type">
          <button type="button" className={`chip${kind === "xtream" ? " on" : ""}`} data-nav role="radio" aria-checked={kind === "xtream"} onClick={() => setKind("xtream")}>
            Xtream login
          </button>
          <button type="button" className={`chip${kind === "m3u" ? " on" : ""}`} data-nav role="radio" aria-checked={kind === "m3u"} onClick={() => setKind("m3u")}>
            M3U playlist + XMLTV
          </button>
        </div>
      )}
      <label>
        <span>Name</span>
        <input data-nav value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. My IPTV" maxLength={60} />
      </label>
      {kind === "xtream" ? (
        <>
          <label>
            <span>Server</span>
            <input data-nav value={server} onChange={(e) => setServer(e.target.value)} placeholder={editing ? `${existing.server ?? "unchanged"} (leave blank to keep)` : "http://provider.example:8080"} inputMode="url" spellCheck={false} />
          </label>
          <label>
            <span>Username</span>
            <input data-nav value={username} onChange={(e) => setUsername(e.target.value)} placeholder={editing ? "unchanged" : ""} autoCapitalize="none" spellCheck={false} />
          </label>
          <label>
            <span>Password</span>
            <input data-nav type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={editing ? "unchanged" : ""} autoComplete="new-password" />
          </label>
        </>
      ) : (
        <>
          <label>
            <span>Playlist (M3U) address</span>
            <input data-nav value={playlistUrl} onChange={(e) => setPlaylistUrl(e.target.value)} placeholder={editing ? "unchanged" : "https://…/playlist.m3u"} inputMode="url" spellCheck={false} />
          </label>
          <label>
            <span>Guide (XMLTV) address, optional</span>
            <input data-nav value={xmltvUrl} onChange={(e) => setXmltvUrl(e.target.value)} placeholder={editing ? "unchanged" : "https://…/epg.xml"} inputMode="url" spellCheck={false} />
          </label>
        </>
      )}
      <label className="iptv-check">
        <input type="checkbox" data-nav checked={englishOnly} onChange={(e) => setEnglishOnly(e.target.checked)} />
        <span>English only (skip other-language groups and titles)</span>
      </label>
      <p className="iptv-hint">The login is checked once and kept only on the server; it is never shown again.</p>
      {err && <p className="iptv-err">{err}</p>}
      <div className="buttons">
        <button type="submit" className="btn primary" data-nav disabled={busy}>
          {busy ? "Checking…" : editing ? "Save" : "Add and check"}
        </button>
        <button type="button" className="btn" data-nav onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

// ---------- groups (opt-in) ----------

const TABS: [CatType, string][] = [
  ["live", "Live channels"],
  ["vod", "Films"],
  ["series", "Series"],
];

function Groups({ p, onSaved }: { p: Provider; onSaved: () => void }) {
  const [type, setType] = useState<CatType>("live");
  const [q, setQ] = useState("");
  const [list, setList] = useState<CategoryList | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  // Unsaved ticks per type (category id → opted); nothing reaches the server until Save
  const [pending, setPending] = useState<Record<CatType, Record<string, boolean>>>({ live: {}, vod: {}, series: {} });
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const load = useCallback(async () => {
    const a = await getCategories(p.id, type, q.trim());
    if (a.status === 200 && a.data) {
      setList(a.data);
      setErr(null);
    } else setErr(errorText(a));
  }, [p.id, type, q]);

  useEffect(() => {
    setList(null);
    const t = setTimeout(load, q ? 300 : 0);
    return () => clearTimeout(t);
  }, [load, q]);

  const opted = (c: Category) => pending[type][String(c.id)] ?? c.opted;
  const set = (cs: Category[], on: boolean) =>
    setPending((all) => {
      const mine = { ...all[type] };
      for (const c of cs) {
        if (c.opted === on) delete mine[String(c.id)];
        else mine[String(c.id)] = on;
      }
      return { ...all, [type]: mine };
    });
  const changes = (Object.keys(pending) as CatType[]).reduce((n, t) => n + Object.keys(pending[t]).length, 0);

  const save = async () => {
    setSaving(true);
    setErr(null);
    for (const t of Object.keys(pending) as CatType[]) {
      if (!Object.keys(pending[t]).length) continue;
      const a = await optCategories(p.id, { type: t, opt: pending[t] });
      if (a.status >= 200 && a.status < 300 && a.data) {
        setPreview(a.data);
        setPending((all) => ({ ...all, [t]: {} }));
      } else {
        setErr(errorText(a));
        break;
      }
    }
    setSaving(false);
    setSavedAt(Date.now());
    onSaved();
    load();
  };

  // English only applies to films and series; live groups are listed as they are (with a language tag)
  const filterLang = p.englishOnly && type !== "live";
  const cats = list?.categories ?? [];
  const hidden = filterLang && !showAll && !q ? cats.filter((c) => !c.english) : [];
  const shown = hidden.length ? cats.filter((c) => c.english) : cats;
  const tot = list?.totals?.[type];
  const fetchingSoon = savedAt && Date.now() - savedAt < 20000 && !p.syncing;

  return (
    <div className="iptv-groups">
      <div className="chips" role="tablist">
        {TABS.map(([t, label]) => {
          const k = t === "live" ? "opted_live" : t === "vod" ? "opted_vod" : "opted_series";
          const dirty = Object.keys(pending[t]).length;
          return (
            <button key={t} className={`chip${type === t ? " on" : ""}`} data-nav role="tab" aria-selected={type === t} onClick={() => setType(t)}>
              {label}
              {p.counts[k] ? ` · ${n(p.counts[k])}` : ""}
              {dirty ? " •" : ""}
            </button>
          );
        })}
      </div>
      <div className="iptv-groups-bar">
        <input className="iptv-search" data-nav type="search" placeholder="Search groups" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="btn small" data-nav onClick={() => set(shown, true)} disabled={!shown.length}>
          {q ? "Tick these" : filterLang ? "All English groups" : "All groups"}
        </button>
        <button className="btn small" data-nav onClick={() => set(q ? shown : cats, false)} disabled={!cats.length}>
          {q ? "Untick these" : "None"}
        </button>
      </div>
      {tot && (
        <p className="iptv-hint">
          {n(tot.optedCategories)} of {n(tot.categories)} groups picked · {n(tot.optedItems)} of {n(tot.items)} {type === "live" ? "channels" : "titles"}
          {list?.listsSyncedAt ? ` · lists updated ${ago(list.listsSyncedAt)}` : ""}
        </p>
      )}
      {err && <p className="iptv-err">{err}</p>}
      {list === null && !err && <p className="iptv-hint">Loading groups…</p>}
      <ul className="iptv-cats">
        {shown.map((c) => (
          <li key={c.id}>
            <label className={opted(c) ? "on" : ""}>
              <input type="checkbox" data-nav checked={opted(c)} onChange={() => set([c], !opted(c))} />
              <span className="iptv-cat-name">{c.name}</span>
              {c.lang !== "en" && <span className={`iptv-lang ${c.lang}`}>{c.lang === "foreign" ? "other language" : "language?"}</span>}
              <span className="iptv-cat-count">{n(c.count)}</span>
            </label>
          </li>
        ))}
      </ul>
      {list && shown.length === 0 && <p className="iptv-hint">{q ? "No groups match." : "No groups yet: the lists are still being fetched."}</p>}
      {hidden.length > 0 && (
        <button className="link" data-nav onClick={() => setShowAll(true)}>
          Show {hidden.length} other-language groups
        </button>
      )}
      <div className="iptv-savebar">
        {(fetchingSoon || p.syncing) && (
          <p className="iptv-progress" role="status">
            <span className="iptv-spinner" aria-hidden />
            {p.syncing ? syncText(p) : "Saved. Fetching starts in about 15 s…"}
          </p>
        )}
        {preview?.epgEstimate && !p.syncing && !fetchingSoon && (
          <p className="iptv-hint">
            Guide: {n(preview.epgEstimate.channels)} channels, ~{n(preview.epgEstimate.programmes)} programmes, {n(preview.epgEstimate.requestsPerRefresh)} requests per refresh
          </p>
        )}
        <span className="spacer" />
        {changes > 0 && (
          <button className="btn small" data-nav onClick={() => setPending({ live: {}, vod: {}, series: {} })} disabled={saving}>
            Undo
          </button>
        )}
        <button className="btn small primary" data-nav onClick={save} disabled={!changes || saving}>
          {saving ? "Saving…" : changes ? `Save ${changes} change${changes === 1 ? "" : "s"}` : "Saved"}
        </button>
      </div>
    </div>
  );
}

function syncText(p: Provider): string {
  const label: Record<string, string> = { lists: "lists", optin: "your groups", match: "film and series matching", epg: "guide" };
  // The server says a sync runs, not which step: show the latest step that finished in it
  const last = (Object.entries(p.sync) as [string, SyncStage | undefined][])
    .filter(([, v]) => v?.at && Date.now() / 1000 - v.at < 3600)
    .sort((x, y) => (y[1]!.at ?? 0) - (x[1]!.at ?? 0))[0];
  if (!last) return "Fetching…";
  const [k, v] = last;
  const what = v!.programmes ? `${n(v!.programmes)} guide entries` : v!.matched != null ? `${n(v!.matched)} titles matched` : null;
  return `Fetching… ${label[k] ?? k} ${v!.ok === false ? "failed" : "done"}${what ? ` (${what})` : ""}`;
}

// ---------- share ----------

function Share({ p, onSaved }: { p: Provider; onSaved: (p: Provider) => void }) {
  const [all, setAll] = useState<JfUser[] | null>(null);
  const [denied, setDenied] = useState(false);
  const [sel, setSel] = useState<Set<string>>(new Set(p.sharedWith ?? []));
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => {
    getUsers().then((a) => {
      if (a.status === 200 && a.data) setAll(a.data.users);
      else setDenied(true);
    });
  }, []);
  if (denied) return <p className="iptv-hint">Only an admin can share a provider with other people.</p>;
  if (!all) return <p className="iptv-hint">Loading people…</p>;
  const me = getSession()?.userId;
  return (
    <div className="iptv-share">
      <p className="iptv-hint">People you share with can watch your channels and titles. They never see the login.</p>
      <ul className="iptv-cats">
        {all
          .filter((u) => u.id !== me)
          .map((u) => (
            <li key={u.id}>
              <label className={sel.has(u.id) ? "on" : ""}>
                <input
                  type="checkbox"
                  data-nav
                  checked={sel.has(u.id)}
                  onChange={() =>
                    setSel((s) => {
                      const next = new Set(s);
                      if (next.has(u.id)) next.delete(u.id);
                      else next.add(u.id);
                      return next;
                    })
                  }
                />
                <span className="iptv-cat-name">{u.name}</span>
              </label>
            </li>
          ))}
      </ul>
      <div className="buttons">
        <button
          className="btn"
          data-nav
          onClick={async () => {
            const a = await editProvider(p.id, { sharedWith: [...sel] });
            if (a.status === 200 && a.data) {
              onSaved(a.data);
              setMsg("Saved.");
            } else setMsg(errorText(a));
          }}
        >
          Save sharing
        </button>
        {msg && <span className="iptv-hint">{msg}</span>}
      </div>
    </div>
  );
}

// ---------- one provider ----------

function StatusLine({ p }: { p: Provider }) {
  const s = p.status;
  const exp = fmtDate(s.expDate);
  const active = p.connections?.active?.length ?? s.activeCons ?? 0;
  const max = p.connections?.max ?? s.maxConnections;
  if (s.authFailed)
    return <p className="iptv-status bad">The provider refused the login. Nothing retries until you edit it (repeated failures can get the server banned).</p>;
  return (
    <p className={`iptv-status${s.ok === false ? " bad" : ""}`}>
      <span className={`iptv-dot ${s.ok === false ? "bad" : s.ok ? "ok" : ""}`} aria-hidden />
      {[
        s.ok === false ? s.message ?? s.state ?? "Not working" : s.state ? `${s.state[0].toUpperCase()}${s.state.slice(1)}` : "Checking",
        s.isTrial ? "trial" : null,
        exp ? `expires ${exp}` : null,
        max != null ? `${active} of ${max} connection${max === 1 ? "" : "s"} in use` : null,
        s.checkedAt ? `checked ${ago(s.checkedAt)}` : null,
      ]
        .filter(Boolean)
        .join(" · ")}
    </p>
  );
}

function ProviderCard({ p, onChange, onRemoved }: { p: Provider; onChange: (p: Provider) => void; onRemoved: () => void }) {
  const [panel, setPanel] = useState<"groups" | "edit" | "share" | "delete" | null>(p.counts.opted_live || p.counts.opted_vod || p.counts.opted_series ? null : "groups");
  const [msg, setMsg] = useState<string | null>(null);
  const holders = p.connections?.active ?? [];

  const check = async () => {
    setMsg("Checking…");
    const a = await iptvGet<Provider["status"]>(`v1/iptv/providers/${p.id}/status`);
    if (a.status === 200 && a.data) {
      onChange({ ...p, status: { ...p.status, ...a.data } });
      setMsg(null);
    } else setMsg(errorText(a));
  };
  const refresh = async () => {
    const a = await refreshProvider(p.id, "all");
    if (a.status === 202 || a.status === 200) {
      setMsg("Refreshing now.");
      onChange({ ...p, syncing: true });
    } else setMsg(a.data?.code === "too_soon" ? "Refreshed less than 5 minutes ago: try again shortly." : a.data?.code === "busy" ? "A refresh is already running." : errorText(a));
  };

  return (
    <section className="iptv-card">
      <div className="iptv-card-head">
        <div>
          <h2>{p.name}</h2>
          <p className="iptv-hint">
            {p.kind === "xtream" ? "Xtream" : "M3U"}
            {p.server ? ` · ${p.server}` : ""}
            {!p.owner ? " · shared with you" : ""}
            {p.owner && p.sharedWith?.length ? ` · shared with ${p.sharedWith.length}` : ""}
          </p>
        </div>
        {p.syncing && (
          <span className="iptv-progress" role="status">
            <span className="iptv-spinner" aria-hidden />
            {syncText(p)}
          </span>
        )}
      </div>
      <StatusLine p={p} />
      <p className="iptv-hint">
        {n(p.counts.opted_live)} live groups · {n(p.counts.opted_vod)} film groups · {n(p.counts.opted_series)} series groups picked · {n(p.counts.channels)} channels, {n(p.counts.movies)} films,{" "}
        {n(p.counts.series)} series, {n(p.counts.programmes)} guide entries
        {p.sync.epg?.at ? ` · guide updated ${ago(p.sync.epg.at)}` : ""}
      </p>
      {holders.length > 0 && (
        <p className="iptv-hint">
          In use: {holders.map((h) => `${h.kind === "recording" ? "recording" : "watching"}${h.label ? ` ${h.label}` : ""}${h.userName || h.user ? ` (${h.userName ?? h.user})` : ""}`).join(", ")}
        </p>
      )}
      {p.owner && (
        <div className="buttons iptv-actions">
          <button className={`btn small${panel === "groups" ? " primary" : ""}`} data-nav onClick={() => setPanel(panel === "groups" ? null : "groups")}>
            Groups
          </button>
          <button className="btn small" data-nav onClick={refresh} disabled={p.syncing}>
            Refresh now
          </button>
          <button className="btn small" data-nav onClick={check}>
            Check connection
          </button>
          <button className={`btn small${panel === "edit" ? " primary" : ""}`} data-nav onClick={() => setPanel(panel === "edit" ? null : "edit")}>
            Edit
          </button>
          <button className={`btn small${panel === "share" ? " primary" : ""}`} data-nav onClick={() => setPanel(panel === "share" ? null : "share")}>
            Share
          </button>
          <button className="btn small danger" data-nav onClick={() => setPanel("delete")}>
            Remove
          </button>
        </div>
      )}
      {msg && <p className="iptv-hint">{msg}</p>}
      {panel === "groups" && p.owner && <Groups p={p} onSaved={() => onChange({ ...p })} />}
      {panel === "edit" && (
        <ProviderForm
          existing={p}
          onCancel={() => setPanel(null)}
          onDone={(np) => {
            onChange(np);
            setPanel(null);
          }}
        />
      )}
      {panel === "share" && <Share p={p} onSaved={onChange} />}
      {panel === "delete" && (
        <div className="iptv-confirm" role="alertdialog" aria-label={`Remove ${p.name}`}>
          <p>Remove {p.name}? Its channels, guide, films, series and recordings are deleted from the server.</p>
          <div className="buttons">
            <button
              className="btn danger"
              data-nav
              data-autofocus
              onClick={async () => {
                const a = await deleteProvider(p.id);
                if (a.status === 200) onRemoved();
                else setMsg(errorText(a));
              }}
            >
              Remove
            </button>
            <button className="btn" data-nav onClick={() => setPanel(null)}>
              Keep
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

// ---------- page ----------

export function IptvSettings() {
  const [list, setList] = useState<Provider[] | null>(null);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    const a: Answer<{ providers: Provider[] }> = await getProviders();
    if (a.status === 200 && a.data) {
      setList(a.data.providers);
      setBlocked(null);
    } else if (a.status === 403) setBlocked(a.data?.code === "disabled" ? "IPTV isn't switched on for your account yet." : errorText(a));
    else setBlocked(errorText(a));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Poll while something is syncing (or right after changes), so progress shows without a reload
  const syncing = !!list?.some((p) => p.syncing);
  const [kick, setKick] = useState(0);
  useEffect(() => {
    if (!syncing && !kick) return;
    const t = setInterval(load, POLL_MS);
    const stop = kick ? setTimeout(() => setKick(0), 45000) : null;
    return () => {
      clearInterval(t);
      if (stop) clearTimeout(stop);
    };
  }, [syncing, kick, load]);

  // Opted channels may have changed: let the nav re-check whether to show Live TV
  const wasSyncing = useRef(false);
  useEffect(() => {
    if (wasSyncing.current && !syncing) iptvChanged();
    wasSyncing.current = syncing;
  }, [syncing]);

  const replace = (np: Provider) => {
    setList((l) => l?.map((x) => (x.id === np.id ? np : x)) ?? [np]);
    setKick(Date.now());
  };

  return (
    <div className="iptv-page">
      <h1 className="page-title">Live TV / IPTV</h1>
      {blocked && <p className="iptv-err iptv-pad">{blocked}</p>}
      {!blocked && list === null && <p className="iptv-hint iptv-pad">Loading…</p>}
      {list && list.length === 0 && !adding && (
        <div className="iptv-empty iptv-pad">
          <p>Add your own IPTV provider to watch live channels and bring its films and series in. It stays private to you unless you share it.</p>
        </div>
      )}
      {list?.map((p) => (
        <ProviderCard key={p.id} p={p} onChange={replace} onRemoved={() => (setList((l) => l?.filter((x) => x.id !== p.id) ?? null), iptvChanged())} />
      ))}
      {list && (
        <section className="iptv-card">
          {adding ? (
            <>
              <h2>Add a provider</h2>
              <ProviderForm
                onCancel={() => setAdding(false)}
                onDone={(p) => {
                  setAdding(false);
                  setList((l) => [...(l ?? []), p]);
                  setKick(Date.now());
                }}
              />
            </>
          ) : (
            <button className="btn primary" data-nav data-autofocus={list.length === 0 || undefined} onClick={() => setAdding(true)} disabled={list.filter((p) => p.owner).length >= 5}>
              Add a provider
            </button>
          )}
        </section>
      )}
      {list && list.length > 0 && <Recordings />}
    </div>
  );
}
