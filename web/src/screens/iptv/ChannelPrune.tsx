// Channel pruning for one live group (Settings → IPTV → Live TV groups → Channels): search, filter chips, checkboxes with
// select all / none on the filtered view, a sticky Hide / Show selected bar, the group's policy and a sport badge per row.
// The list is windowed (groups run to ~1,000 channels). Contract: ~/ai/plans/jellytip-live-sport.md "Channel pruning inside groups".
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { groupChannels, logoSrc, saveGroupChannels } from "../../api/iptv";
import { SheetClose, useEscapeClose } from "../../components/BackArrow";
import { CHIPS, counts, filterChannels, POLICIES, sportBadge, windowSlice, withHidden, type Chip, type Policy, type PruneChannel } from "../../lib/channelPrune";
import "./iptv.css";

const ROW_H = 52;

export function ChannelPrune({ pid, catId, name, onClose, onStats }: { pid: string | number; catId: string | number; name: string; onClose: () => void; onStats: (s: { total: number; hidden: number }) => void }) {
  const [channels, setChannels] = useState<PruneChannel[] | null>(null);
  const [policy, setPolicy] = useState<Policy>(null);
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [chip, setChip] = useState<Chip>("all");
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [scroll, setScroll] = useState(0);
  const [vh, setVh] = useState(420);
  const box = useRef<HTMLDivElement>(null);
  useEscapeClose(onClose);

  const load = useCallback(async () => {
    const a = await groupChannels(pid, catId);
    if (a === "missing") return setErr("The server can't list this group's channels yet.");
    if ("error" in a) return setErr(a.error);
    setErr(null);
    setChannels(a.list.channels);
    setPolicy(a.list.policy);
    onStats(counts(a.list.channels));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pid, catId]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setVh(el.clientHeight));
    ro.observe(el);
    return () => ro.disconnect();
  }, [channels]);

  const view = useMemo(() => filterChannels(channels ?? [], q, chip), [channels, q, chip]);
  const win = windowSlice(scroll, vh, ROW_H, view.length);
  const c = counts(channels ?? []);
  const chipCount = (k: Chip) => (channels ? filterChannels(channels, "", k).length : 0);

  const toggle = (sid: string) =>
    setSel((s) => {
      const n = new Set(s);
      if (n.has(sid)) n.delete(sid);
      else n.add(sid);
      return n;
    });
  const apply = async (hide: boolean) => {
    if (!channels || !sel.size) return;
    setBusy(true);
    const body: Record<string, boolean> = {};
    sel.forEach((sid) => (body[sid] = hide));
    const e = await saveGroupChannels(pid, catId, { hide: body });
    setBusy(false);
    if (e) return setErr(e);
    setErr(null);
    const next = withHidden(channels, sel, hide);
    setChannels(next);
    onStats(counts(next));
    setSel(new Set());
  };
  const setPol = async (v: Exclude<Policy, null> | "manual") => {
    setBusy(true);
    const e = await saveGroupChannels(pid, catId, { policy: v === "manual" ? null : v });
    setBusy(false);
    if (e) return setErr(e);
    await load(); // the server re-applies the rule to the channels: look again
  };

  return (
    <div className="livetv-sheet-back prune-back" onClick={onClose}>
      <div className="livetv-sheet prune" role="dialog" aria-label={`Channels in ${name}`} onClick={(e) => e.stopPropagation()}>
        <SheetClose onClose={onClose} />
        <h2>{name}</h2>
        <p className="iptv-hint">{channels ? `${c.total} channels · ${c.hidden} hidden` : "Loading…"}</p>
        {err && <p className="iptv-err">{err}</p>}
        {channels && (
          <>
            <div className="chips" role="radiogroup" aria-label="Which channels to keep">
              {POLICIES.map(([k, label]) => {
                const on = (policy ?? "manual") === k;
                return (
                  <button key={k} className={`chip${on ? " on" : ""}`} data-nav role="radio" aria-checked={on} disabled={busy} onClick={() => void setPol(k)}>
                    {label}
                  </button>
                );
              })}
            </div>
            <input className="iptv-search" data-nav type="search" placeholder="Search channels" value={q} onChange={(e) => setQ(e.target.value)} />
            <div className="chips" role="tablist" aria-label="Filter">
              {CHIPS.map(([k, label]) => (
                <button key={k} className={`chip${chip === k ? " on" : ""}`} data-nav role="tab" aria-selected={chip === k} onClick={() => setChip(k)}>
                  {label}
                  <span className="livetv-count">{chipCount(k)}</span>
                </button>
              ))}
            </div>
            <div className="buttons">
              <button className="btn small" data-nav disabled={!view.length} onClick={() => setSel((s) => new Set([...s, ...view.map((x) => x.sid)]))}>
                Select all ({view.length})
              </button>
              <button className="btn small" data-nav disabled={!sel.size} onClick={() => setSel(new Set())}>
                Select none
              </button>
            </div>
            <div ref={box} className="prune-list" onScroll={(e) => setScroll(e.currentTarget.scrollTop)}>
              <div style={{ height: view.length * ROW_H, position: "relative" }}>
                {view.slice(win.from, win.to).map((ch, i) => (
                  <label key={ch.sid} className={`prune-row${sel.has(ch.sid) ? " on" : ""}${ch.hidden ? " hid" : ""}`} style={{ position: "absolute", top: (win.from + i) * ROW_H, height: ROW_H, left: 0, right: 0 }}>
                    <input type="checkbox" data-nav checked={sel.has(ch.sid)} onChange={() => toggle(ch.sid)} />
                    {ch.logo && logoSrc(ch.logo, 96) ? <img className="onnow-logo" src={logoSrc(ch.logo, 96)!} alt="" loading="lazy" /> : <span className="onnow-logo blank">{ch.name.slice(0, 2)}</span>}
                    <span className="prune-name">{ch.name}</span>
                    <span className={`prune-badge ${ch.sports}`}>{sportBadge(ch)}</span>
                    {ch.hidden && <span className="prune-hidden">Hidden</span>}
                  </label>
                ))}
              </div>
              {view.length === 0 && <p className="iptv-hint">No channels match.</p>}
            </div>
            <div className="prune-bar">
              <span>{sel.size} selected</span>
              <span className="spacer" />
              <button className="btn small" data-nav disabled={!sel.size || busy} onClick={() => void apply(true)}>
                Hide selected
              </button>
              <button className="btn small primary" data-nav disabled={!sel.size || busy} onClick={() => void apply(false)}>
                Show selected
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
