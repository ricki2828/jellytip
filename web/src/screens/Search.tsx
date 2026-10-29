import { useEffect, useState } from "react";
import { search, type Item } from "../api/jellyfin";
import { go } from "../lib/router";
import { Card } from "../components/Card";

export function Search({ initial }: { initial: string }) {
  const [q, setQ] = useState(initial);
  const [results, setResults] = useState<Item[] | null>(null);

  useEffect(() => {
    const term = q.trim();
    go(term ? `/search?q=${encodeURIComponent(term)}` : "/search", true);
    if (term.length < 2) {
      setResults(null);
      return;
    }
    let alive = true;
    const t = setTimeout(() => {
      search(term)
        .then((r) => alive && setResults(r))
        .catch(() => alive && setResults([]));
    }, 250);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [q]);

  const groups: [string, Item[]][] = results
    ? ([
        ["Movies", results.filter((r) => r.Type === "Movie")],
        ["Shows", results.filter((r) => r.Type === "Series")],
        ["Episodes", results.filter((r) => r.Type === "Episode")],
      ] as [string, Item[]][]).filter(([, l]) => l.length > 0)
    : [];

  return (
    <div className="search">
      <input
        className="search-input"
        data-nav
        autoFocus
        placeholder="Search movies, shows, episodes"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            (document.querySelector(".search .card") as HTMLElement | null)?.focus();
          }
        }}
      />
      {results && groups.length === 0 && <p className="empty">Nothing found for “{q.trim()}”.</p>}
      {groups.map(([label, list]) => (
        <section key={label} className="grid-section">
          <h2 className="row-title">{label}</h2>
          <div className="grid">
            {list.map((it) => (
              <Card key={it.Id} item={it} width={0} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
