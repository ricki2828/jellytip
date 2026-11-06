// Edit Home: the person's rows in their order with pin / hide / move, and how many specials a day. Saved to the
// server (PATCH /v1/me/prefs {home}), which applies it to every device; hidden rows come back from here.
import { useEffect, useRef, useState } from "react";
import {
  editRows,
  homeFrom,
  move,
  SPECIALS_MAX,
  type EditRow,
  type Prefs,
} from "../lib/prefs";
import { rowTitles } from "../lib/rowTitles";
import "./HomeEdit.css";

/** Where Home puts a row today: the first screen, behind More rows, or left out by the quality filter. */
export type Placement = "first" | "more" | "auto";
const PLACE_LABEL: Record<Placement, string> = {
  first: "First screen",
  more: "More rows",
  auto: "Left out: few or watched titles",
};

export function HomeEdit({
  rows,
  home,
  onSave,
  placement,
  specials = true,
  reorder = true,
  onClose,
}: {
  rows: { id: string; title: string }[];
  home: Prefs["home"];
  /** Saves the choices (server prefs, or this device when the server has none); resolves to an error message or null. */
  onSave: (home: Prefs["home"]) => Promise<string | null>;
  placement?: Record<string, Placement>;
  /** Show the specials-a-day control (server prefs only). */
  specials?: boolean;
  /** Show move up/down (server prefs keep the order; this device's choices don't). */
  reorder?: boolean;
  onClose: (saved: boolean) => void;
}) {
  const [list, setList] = useState<EditRow[]>(() =>
    editRows(rows, home, rowTitles()),
  );
  const [spd, setSpd] = useState(home.specialsPerDay);
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

  const toggle = (id: string, k: "pinned" | "hidden") =>
    setList((l) =>
      l.map((r) =>
        r.id === id
          ? {
              ...r,
              [k]: !r[k],
              ...(k === "hidden" && !r.hidden ? { pinned: false } : {}),
            }
          : r,
      ),
    );
  // Hiding moves the row to the hidden list at the bottom; un-hiding puts it back last among the visible ones
  const ordered = [
    ...list.filter((r) => !r.hidden),
    ...list.filter((r) => r.hidden),
  ];
  const visible = ordered.filter((r) => !r.hidden);

  const save = async () => {
    setBusy(true);
    setErr(null);
    const e = await onSave(homeFrom(ordered, spd));
    setBusy(false);
    if (e) setErr(e);
    else onClose(true);
  };

  return (
    <div className="home-edit-back" onClick={() => onClose(false)}>
      <div
        className="home-edit"
        ref={box}
        role="dialog"
        aria-label="Edit Home"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="home-edit-head">
          <h2>Edit Home</h2>
          <p className="home-edit-hint">
            Pinned rows stay on the first screen, always. Hidden rows never
            show. The rest follow your order; a few show first, the others sit
            behind More rows. Rows with very few or mostly watched titles are
            left out unless pinned.
          </p>
        </div>
        <ul className="home-edit-list">
          {ordered.map((r) => {
            const i = visible.indexOf(r);
            return (
              <li
                key={r.id}
                className={`home-edit-row${r.hidden ? " hidden" : ""}${r.pinned ? " pinned" : ""}`}
              >
                <span className="home-edit-title">{r.title}</span>
                {!r.hidden && placement?.[r.id] && !r.pinned && (
                  <span className="home-edit-place">
                    {PLACE_LABEL[placement[r.id]]}
                  </span>
                )}
                {!r.hidden && (
                  <>
                    {reorder && (
                      <>
                        <button
                          className="btn small"
                          data-nav
                          aria-label={`Move ${r.title} up`}
                          disabled={i <= 0}
                          onClick={() =>
                            setList((l) =>
                              move(
                                [
                                  ...l.filter((x) => !x.hidden),
                                  ...l.filter((x) => x.hidden),
                                ],
                                r.id,
                                -1,
                              ),
                            )
                          }
                        >
                          ↑
                        </button>
                        <button
                          className="btn small"
                          data-nav
                          aria-label={`Move ${r.title} down`}
                          disabled={i >= visible.length - 1}
                          onClick={() =>
                            setList((l) =>
                              move(
                                [
                                  ...l.filter((x) => !x.hidden),
                                  ...l.filter((x) => x.hidden),
                                ],
                                r.id,
                                1,
                              ),
                            )
                          }
                        >
                          ↓
                        </button>
                      </>
                    )}
                    <button
                      className={`btn small${r.pinned ? " primary" : ""}`}
                      data-nav
                      aria-pressed={r.pinned}
                      onClick={() => toggle(r.id, "pinned")}
                    >
                      {r.pinned ? "Pinned" : "Pin"}
                    </button>
                  </>
                )}
                <button
                  className="btn small"
                  data-nav
                  aria-pressed={r.hidden}
                  onClick={() => toggle(r.id, "hidden")}
                >
                  {r.hidden ? "Show" : "Hide"}
                </button>
              </li>
            );
          })}
        </ul>
        {specials && (
          <div className="home-edit-specials">
            <span>Specials a day</span>
            <button
              className="btn small"
              data-nav
              aria-label="Fewer specials"
              disabled={spd <= 0}
              onClick={() => setSpd((n) => n - 1)}
            >
              −
            </button>
            <span className="home-edit-count" aria-live="polite">
              {spd === 0 ? "None" : spd}
            </span>
            <button
              className="btn small"
              data-nav
              aria-label="More specials"
              disabled={spd >= SPECIALS_MAX}
              onClick={() => setSpd((n) => n + 1)}
            >
              +
            </button>
          </div>
        )}
        {err && <p className="home-edit-err">{err}</p>}
        <div className="buttons">
          <button
            className="btn primary"
            data-nav
            data-autofocus
            onClick={save}
            disabled={busy}
          >
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
