// Edit Home: the person's rows in their order with pin / hide / move, and how many specials a day. Saved to the
// server (PATCH /v1/me/prefs {home}), which applies it to every device; hidden rows come back from here.
import { useEffect, useRef, useState } from "react";
import { savePrefs } from "../api/me";
import { editRows, homeFrom, move, SPECIALS_MAX, type EditRow, type Prefs } from "../lib/prefs";
import "./HomeEdit.css";

const TITLES_KEY = "jellytip.rowTitles";

/** Row titles seen on this device, so hidden rows (absent from today's rows) still have a name. */
export function rememberRowTitles(rows: { id: string; title: string }[]) {
  try {
    const t = JSON.parse(localStorage.getItem(TITLES_KEY) ?? "{}") as Record<string, string>;
    for (const r of rows) t[r.id] = r.title;
    const keys = Object.keys(t);
    for (const k of keys.slice(0, Math.max(0, keys.length - 500))) delete t[k];
    localStorage.setItem(TITLES_KEY, JSON.stringify(t));
  } catch {
    // storage full / blocked: hidden rows fall back to a generic label
  }
}
const rowTitles = (): Record<string, string> => {
  try {
    return JSON.parse(localStorage.getItem(TITLES_KEY) ?? "{}") as Record<string, string>;
  } catch {
    return {};
  }
};

export function HomeEdit({ rows, prefs, onClose }: { rows: { id: string; title: string }[]; prefs: Prefs; onClose: (saved: boolean) => void }) {
  const [list, setList] = useState<EditRow[]>(() => editRows(rows, prefs.home, rowTitles()));
  const [spd, setSpd] = useState(prefs.home.specialsPerDay);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    box.current?.querySelector<HTMLElement>("[data-autofocus]")?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      close.current(false);
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, []);

  const toggle = (id: string, k: "pinned" | "hidden") => setList((l) => l.map((r) => (r.id === id ? { ...r, [k]: !r[k], ...(k === "hidden" && !r.hidden ? { pinned: false } : {}) } : r)));
  // Hiding moves the row to the hidden list at the bottom; un-hiding puts it back last among the visible ones
  const ordered = [...list.filter((r) => !r.hidden), ...list.filter((r) => r.hidden)];
  const visible = ordered.filter((r) => !r.hidden);

  const save = async () => {
    setBusy(true);
    setErr(null);
    const e = await savePrefs({ home: homeFrom(ordered, spd) });
    setBusy(false);
    if (e) setErr(e);
    else onClose(true);
  };

  return (
    <div className="home-edit-back" onClick={() => onClose(false)}>
      <div className="home-edit" ref={box} role="dialog" aria-label="Edit Home" onClick={(e) => e.stopPropagation()}>
        <div className="home-edit-head">
          <h2>Edit Home</h2>
          <p className="home-edit-hint">Pinned rows stay at the top every day. Hidden rows never show. The rest rotate in your order.</p>
        </div>
        <ul className="home-edit-list">
          {ordered.map((r) => {
            const i = visible.indexOf(r);
            return (
              <li key={r.id} className={`home-edit-row${r.hidden ? " hidden" : ""}${r.pinned ? " pinned" : ""}`}>
                <span className="home-edit-title">{r.title}</span>
                {!r.hidden && (
                  <>
                    <button className="btn small" data-nav aria-label={`Move ${r.title} up`} disabled={i <= 0} onClick={() => setList((l) => move([...l.filter((x) => !x.hidden), ...l.filter((x) => x.hidden)], r.id, -1))}>
                      ↑
                    </button>
                    <button className="btn small" data-nav aria-label={`Move ${r.title} down`} disabled={i >= visible.length - 1} onClick={() => setList((l) => move([...l.filter((x) => !x.hidden), ...l.filter((x) => x.hidden)], r.id, 1))}>
                      ↓
                    </button>
                    <button className={`btn small${r.pinned ? " primary" : ""}`} data-nav aria-pressed={r.pinned} onClick={() => toggle(r.id, "pinned")}>
                      {r.pinned ? "Pinned" : "Pin"}
                    </button>
                  </>
                )}
                <button className="btn small" data-nav aria-pressed={r.hidden} onClick={() => toggle(r.id, "hidden")}>
                  {r.hidden ? "Show" : "Hide"}
                </button>
              </li>
            );
          })}
        </ul>
        <div className="home-edit-specials">
          <span>Specials a day</span>
          <button className="btn small" data-nav aria-label="Fewer specials" disabled={spd <= 0} onClick={() => setSpd((n) => n - 1)}>
            −
          </button>
          <span className="home-edit-count" aria-live="polite">
            {spd === 0 ? "None" : spd}
          </span>
          <button className="btn small" data-nav aria-label="More specials" disabled={spd >= SPECIALS_MAX} onClick={() => setSpd((n) => n + 1)}>
            +
          </button>
        </div>
        {err && <p className="home-edit-err">{err}</p>}
        <div className="buttons">
          <button className="btn primary" data-nav data-autofocus onClick={save} disabled={busy}>
            {busy ? "Saving…" : "Save"}
          </button>
          <button className="btn" data-nav onClick={() => onClose(false)}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
