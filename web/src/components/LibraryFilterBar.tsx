// Filter panel + active-filter chips for the All movies / All shows grid. Facets come from Jellyfin's /Items/Filters.
import { useEffect, useState } from "react";
import { jf, qs, uid } from "../api/jellyfin";
import { activeChips, clearAll, facetsQuery, isFiltered, MIN_RATINGS, parseFacets, removeChip, toggle, type Facets, type Kind, type LibraryFilters, type Watched } from "../lib/libraryFilters";
import "./LibraryFilterBar.css";

// Per user + kind, so one person's genres/ratings never show for another signed-in later.
const facetCache = new Map<string, Facets>();

function useFacets(kind: Kind, open: boolean) {
  const user = uid();
  const key = `${user}:${kind}`;
  const [facets, setFacets] = useState<Facets | null>(() => facetCache.get(key) ?? null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setFacets(facetCache.get(key) ?? null);
    setFailed(false);
  }, [key]);
  useEffect(() => {
    if (!open || !user || facetCache.has(key)) return;
    let alive = true;
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
  }, [open, key, kind, user]);
  return { facets, failed };
}

const WATCHED: [Watched, string][] = [
  ["all", "Any"],
  ["unplayed", "Unwatched"],
  ["played", "Watched"],
];

export function LibraryFilterBar({ kind, value, onChange }: { kind: Kind; value: LibraryFilters; onChange: (f: LibraryFilters) => void }) {
  const [open, setOpen] = useState(false);
  const { facets, failed } = useFacets(kind, open);
  const chips = activeChips(value);
  const noun = kind === "movie" ? "movies" : "shows";
  return (
    <div className="lib-filters">
      <div className="chips">
        <button data-nav className={`chip${open ? " on" : ""}`} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
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
      {open && (
        <div className="lib-filter-panel">
          <p className="dim lib-filter-scope">Filters search your Jellyfin {noun} only. IPTV titles are not included here.</p>
          <FilterGroup label="Watched">
            {WATCHED.map(([k, label]) => (
              <button key={k} data-nav className={`chip${value.watched === k ? " on" : ""}`} onClick={() => onChange({ ...value, watched: k })}>
                {label}
              </button>
            ))}
            <button data-nav className={`chip${value.favorite ? " on" : ""}`} onClick={() => onChange({ ...value, favorite: !value.favorite })}>
              Favourites
            </button>
          </FilterGroup>
          <FilterGroup label="Rating">
            {MIN_RATINGS.map((n) => (
              <button key={n} data-nav className={`chip${value.minRating === n ? " on" : ""}`} onClick={() => onChange({ ...value, minRating: value.minRating === n ? null : n })}>
                {n}+
              </button>
            ))}
          </FilterGroup>
          {failed && <p className="dim">Couldn't load the genre / year lists.</p>}
          {!facets && !failed && <p className="dim">Loading options…</p>}
          {facets && (
            <>
              {facets.genres.length > 0 && (
                <FilterGroup label="Genre">
                  {facets.genres.map((g) => (
                    <button key={g} data-nav className={`chip${value.genres.includes(g) ? " on" : ""}`} onClick={() => onChange({ ...value, genres: toggle(value.genres, g) })}>
                      {g}
                    </button>
                  ))}
                </FilterGroup>
              )}
              {facets.ratings.length > 0 && (
                <FilterGroup label="Certificate">
                  {facets.ratings.map((r) => (
                    <button key={r} data-nav className={`chip${value.ratings.includes(r) ? " on" : ""}`} onClick={() => onChange({ ...value, ratings: toggle(value.ratings, r) })}>
                      {r}
                    </button>
                  ))}
                </FilterGroup>
              )}
              {facets.years.length > 0 && (
                <FilterGroup label="Year">
                  {facets.years.map((y) => (
                    <button key={y} data-nav className={`chip${value.years.includes(y) ? " on" : ""}`} onClick={() => onChange({ ...value, years: toggle(value.years, y) })}>
                      {y}
                    </button>
                  ))}
                </FilterGroup>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function FilterGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="lib-filter-group">
      <span className="lib-filter-label">{label}</span>
      <div className="chips">{children}</div>
    </div>
  );
}
