// Sort row + active-filter chips + the filter sheet (bottom sheet on phones, centred panel on desktop) for the All movies / All shows grid.
// Facets come from Jellyfin's /Items/Filters; studios and people are searched live (/Studios, /Persons).
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { jf, qs, uid } from "../api/jellyfin";
import {
  activeChips, clearAll, DECADES, dirWords, effectiveDir, facetsQuery, flipDir, isFiltered, MIN_RATINGS, parseFacets, parseSearchItems, removeChip, RUNTIME_BANDS, setSort, SORTS, toggle,
  type Facets, type Kind, type LibraryFilters, type Ref, type Watched,
} from "../lib/libraryFilters";
import "./LibraryFilterBar.css";

// Per user + kind, so one person's genres/ratings never show for another signed-in later.
const facetCache = new Map<string, Facets>();

function useFacets(kind: Kind, open: boolean) {
  const user = uid();
  const key = `${user}:${kind}`;
  const [facets, setFacets] = useState<Facets | null>(() => facetCache.get(key) ?? null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    setFacets(facetCache.get(key) ?? null);
    setFailed(false);
  }, [key]);
  useEffect(() => {
    if (!open || !user || facetCache.has(key)) return;
    let alive = true;
    setFailed(false);
    jf(`/Items/Filters${qs({ UserId: user, ...facetsQuery(kind) })}`)
      .then((raw) => {
        const f = parseFacets(raw);
        facetCache.set(key, f);
        if (alive) setFacets(f);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [open, key, kind, user, attempt]);
  return { facets, failed, retry: () => setAttempt((n) => n + 1) };
}

// Year facet: newest N shown, the rest behind "Older…" (still the same server-side Years filter)
const RECENT_YEARS = 30;

const WATCHED: [Watched, string][] = [
  ["all", "Any"],
  ["unplayed", "Unwatched"],
  ["played", "Watched"],
];

export function LibraryFilterBar({ kind, value, onChange }: { kind: Kind; value: LibraryFilters; onChange: (f: LibraryFilters) => void }) {
  const [open, setOpen] = useState(false);
  const chips = activeChips(value);
  const random = value.sort === "random";
  const dir = effectiveDir(value);
  return (
    <div className="lib-filters">
      <div className="chips lib-sorts" role="group" aria-label="Sort by">
        {SORTS.map(({ key, label }) => (
          <button key={key} data-nav className={`chip${key === value.sort ? " on" : ""}`} onClick={() => onChange(setSort(value, key))} aria-pressed={key === value.sort}>
            {label}
          </button>
        ))}
        {!random && (
          <button data-nav className="chip dir-toggle" onClick={() => onChange(flipDir(value))} aria-label={`Order: ${dirWords(value)}. Tap to reverse`} title="Reverse the order">
            {dir === "desc" ? "↓" : "↑"} {dirWords(value)}
          </button>
        )}
      </div>
      <div className="chips lib-active">
        <button data-nav className={`chip filters-btn${chips.length ? " on" : ""}`} onClick={() => setOpen(true)} aria-haspopup="dialog">
          Filters{chips.length ? ` (${chips.length})` : ""}
        </button>
        {chips.map((c) => (
          <button key={c.id} data-nav className="chip on removable" onClick={() => onChange(removeChip(value, c.id))} aria-label={`Remove filter ${c.label}`}>
            {c.label} ×
          </button>
        ))}
        {isFiltered(value) && (
          <button data-nav className="chip" onClick={() => onChange(clearAll(value))}>
            Clear all
          </button>
        )}
      </div>
      {open && <FilterSheet kind={kind} value={value} onChange={onChange} onClose={() => setOpen(false)} />}
    </div>
  );
}

function FilterSheet({ kind, value, onChange, onClose }: { kind: Kind; value: LibraryFilters; onChange: (f: LibraryFilters) => void; onClose: () => void }) {
  const [olderYears, setOlderYears] = useState(false);
  const { facets, failed, retry } = useFacets(kind, true);
  const box = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose); // onClose is a new arrow each render: the effect must not re-run (it refocuses Done)
  closeRef.current = onClose;
  const noun = kind === "movie" ? "movies" : "shows";

  // Focus moves into the sheet (not on touch), back to the opener on close; Escape closes it before it can mean "Back"
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    if (!matchMedia("(hover: none) and (pointer: coarse)").matches) box.current?.querySelector<HTMLElement>("[data-autofocus]")?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      const t = e.target as HTMLElement | null;
      if (t && t.tagName === "INPUT" && (t as HTMLInputElement).value) return; // first Esc clears nothing, but never closes mid-typing
      e.preventDefault();
      e.stopPropagation();
      closeRef.current();
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      opener?.focus?.({ preventScroll: true });
    };
  }, []);

  const set = (patch: Partial<LibraryFilters>) => onChange({ ...value, ...patch });
  // Portal: the bar is a sticky stacking context, which would keep the sheet below the phone tab bar
  return createPortal(
    <div className="filter-scrim" onClick={onClose}>
      <div ref={box} className="filter-sheet" data-modal role="dialog" aria-modal="true" aria-label="Filters" onClick={(e) => e.stopPropagation()}>
        <div className="filter-sheet-head">
          <strong>Filters</strong>
          {isFiltered(value) && (
            <button data-nav className="chip" onClick={() => onChange(clearAll(value))}>
              Clear all
            </button>
          )}
          <button data-nav data-autofocus className="btn done" onClick={onClose}>
            Done
          </button>
        </div>
        <div className="lib-filter-panel">
          <p className="dim lib-filter-scope">Filters search your Jellyfin {noun} only. IPTV titles are not included here.</p>
          <FilterGroup label="Watched">
            {WATCHED.map(([k, label]) => (
              <button key={k} data-nav className={`chip${value.watched === k ? " on" : ""}`} onClick={() => set({ watched: k })}>
                {label}
              </button>
            ))}
            <button data-nav className={`chip${value.favorite ? " on" : ""}`} onClick={() => set({ favorite: !value.favorite })}>
              Favourites
            </button>
          </FilterGroup>
          <FilterGroup label="Quality">
            <button data-nav className={`chip${value.res === "4k" ? " on" : ""}`} onClick={() => set({ res: value.res === "4k" ? "" : "4k" })}>
              4K
            </button>
            <button data-nav className={`chip${value.res === "hd" ? " on" : ""}`} onClick={() => set({ res: value.res === "hd" ? "" : "hd" })}>
              HD
            </button>
            <button data-nav className={`chip${value.subs ? " on" : ""}`} onClick={() => set({ subs: !value.subs })}>
              Has subtitles
            </button>
          </FilterGroup>
          <FilterGroup label="Rating">
            {MIN_RATINGS.map((n) => (
              <button key={n} data-nav className={`chip${value.minRating === n ? " on" : ""}`} onClick={() => set({ minRating: value.minRating === n ? null : n })}>
                {n}+
              </button>
            ))}
          </FilterGroup>
          <FilterGroup label="Decade">
            {DECADES.map((d) => (
              <button key={d} data-nav className={`chip${value.decades.includes(d) ? " on" : ""}`} onClick={() => set({ decades: toggle(value.decades, d) })}>
                {d}s
              </button>
            ))}
          </FilterGroup>
          {kind === "movie" && (
            <FilterGroup label="Runtime">
              {RUNTIME_BANDS.map(([k, label]) => (
                <button key={k} data-nav className={`chip${value.runtime === k ? " on" : ""}`} onClick={() => set({ runtime: value.runtime === k ? "" : k })}>
                  {label}
                </button>
              ))}
            </FilterGroup>
          )}
          <RefPicker label={kind === "movie" ? "Studio" : "Network"} endpoint="/Studios" kind={kind} selected={value.studios} onChange={(studios) => set({ studios })} placeholder={kind === "movie" ? "Search studios" : "Search networks"} />
          <RefPicker label="Cast / crew" endpoint="/Persons" kind={kind} selected={value.people} onChange={(people) => set({ people })} placeholder="Director or actor" />
          {failed && (
            <p className="dim">
              Couldn't load the genre / year lists.{" "}
              <button data-nav className="chip" onClick={retry}>
                Retry
              </button>
            </p>
          )}
          {!facets && !failed && <p className="dim">Loading options…</p>}
          {facets && (
            <>
              {facets.genres.length > 0 && (
                <FilterGroup label="Genre">
                  {facets.genres.map((g) => (
                    <button key={g} data-nav className={`chip${value.genres.includes(g) ? " on" : ""}`} onClick={() => set({ genres: toggle(value.genres, g) })}>
                      {g}
                    </button>
                  ))}
                </FilterGroup>
              )}
              {facets.ratings.length > 0 && (
                <FilterGroup label="Certificate">
                  {facets.ratings.map((r) => (
                    <button key={r} data-nav className={`chip${value.ratings.includes(r) ? " on" : ""}`} onClick={() => set({ ratings: toggle(value.ratings, r) })}>
                      {r}
                    </button>
                  ))}
                </FilterGroup>
              )}
              {facets.years.length > 0 && (
                <FilterGroup label="Year">
                  {(olderYears ? facets.years : facets.years.slice(0, RECENT_YEARS)).map((y) => (
                    <button key={y} data-nav className={`chip${value.years.includes(y) ? " on" : ""}`} onClick={() => set({ years: toggle(value.years, y) })}>
                      {y}
                    </button>
                  ))}
                  {facets.years.length > RECENT_YEARS && (
                    <button data-nav className="chip" onClick={() => setOlderYears((o) => !o)}>
                      {olderYears ? "Recent only" : "Older…"}
                    </button>
                  )}
                </FilterGroup>
              )}
            </>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}

/** Type-to-search picker over a Jellyfin name list (/Studios, /Persons); picked entries are toggled in `selected`. */
function RefPicker({ label, endpoint, kind, selected, onChange, placeholder }: { label: string; endpoint: "/Studios" | "/Persons"; kind: Kind; selected: Ref[]; onChange: (r: Ref[]) => void; placeholder: string }) {
  const [term, setTerm] = useState("");
  const [found, setFound] = useState<Ref[]>([]);
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");
  const seq = useRef(0);
  useEffect(() => {
    const t = term.trim();
    if (t.length < 2) {
      seq.current++;
      setFound([]);
      setState("idle");
      return;
    }
    const n = ++seq.current;
    setState("loading");
    const h = window.setTimeout(() => {
      const params = endpoint === "/Studios" ? { UserId: uid(), SearchTerm: t, IncludeItemTypes: kind === "movie" ? "Movie" : "Series", Recursive: true, Limit: 24 } : { UserId: uid(), searchTerm: t, limit: 24 };
      jf(`${endpoint}${qs(params)}`)
        .then((raw) => {
          if (n !== seq.current) return;
          setFound(parseSearchItems(raw));
          setState("idle");
        })
        .catch(() => n === seq.current && setState("failed"));
    }, 250);
    return () => window.clearTimeout(h);
  }, [term, endpoint, kind]);
  const pick = (r: Ref) => onChange(selected.some((s) => s.id === r.id) ? selected.filter((s) => s.id !== r.id) : [...selected, r]);
  return (
    <FilterGroup label={label}>
      <div className="ref-picker">
        <input data-nav className="ref-input" type="search" value={term} onChange={(e) => setTerm(e.target.value)} placeholder={placeholder} aria-label={`Search ${label.toLowerCase()}`} />
        <div className="chips">
          {selected.map((r) => (
            <button key={r.id} data-nav className="chip on" onClick={() => pick(r)} aria-label={`Remove ${r.name}`}>
              {r.name} ×
            </button>
          ))}
          {found
            .filter((r) => !selected.some((s) => s.id === r.id))
            .map((r) => (
              <button key={r.id} data-nav className="chip" onClick={() => pick(r)}>
                {r.name}
              </button>
            ))}
        </div>
        {state === "loading" && <span className="dim ref-note">Searching…</span>}
        {state === "failed" && <span className="dim ref-note">Search failed. Try again.</span>}
        {state === "idle" && term.trim().length >= 2 && found.length === 0 && <span className="dim ref-note">No match.</span>}
      </div>
    </FilterGroup>
  );
}

function FilterGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="lib-filter-group">
      <span className="lib-filter-label">{label}</span>
      <div className="lib-filter-body">{children}</div>
    </div>
  );
}
