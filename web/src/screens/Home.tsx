import { useCallback, useEffect, useState } from "react";
import { getItems, nextUp, resumeItems, type Item } from "../api/jellyfin";
import { manifest, type Carousel, type LetterboxdRow } from "../api/companion";
import { Hero } from "../components/Hero";
import { Row } from "../components/Row";

const MAX_CAROUSEL_ROWS = 10;
const MAX_LETTERBOXD_ROWS = 6;
const ROW_LIMIT = 24;

export const LETTERBOXD_PREFIX = "Letterboxd · ";

function sortFor(order: string): { SortBy?: string; SortOrder?: string } {
  switch (order) {
    case "random":
      return { SortBy: "Random", SortOrder: "Ascending" };
    case "release":
    case "release.desc":
      return { SortBy: "PremiereDate,SortName", SortOrder: "Descending,Ascending" };
    case "added.desc":
      return { SortBy: "DateCreated,SortName", SortOrder: "Descending,Ascending" };
    default:
      return {};
  }
}

export const carouselLoader = (c: Carousel) => async () => (await getItems({ ParentId: c.id, Limit: ROW_LIMIT, ...sortFor(c.order) })).Items;

export const letterboxdLoader = (l: LetterboxdRow) => async () => {
  // Keep the list's own order; the server returns Ids in library order
  const r = await getItems({ Ids: [...new Set(l.itemIds)].join(","), IncludeItemTypes: "Movie" });
  const byId = new Map(r.Items.map((i) => [i.Id, i]));
  return [...new Set(l.itemIds)].map((id) => byId.get(id)).filter((x): x is Item => !!x);
};

export function Home() {
  const [continueItems, setContinue] = useState<Item[] | null>(null);
  const [carousels, setCarousels] = useState<Carousel[]>([]);
  const [lists, setLists] = useState<LetterboxdRow[]>([]);
  const [focused, setFocused] = useState<Item | null>(null);

  useEffect(() => {
    let alive = true;
    Promise.all([resumeItems().catch(() => []), nextUp().catch(() => [])]).then(([resume, next]) => {
      if (!alive) return;
      // Continue Watching first, then Next Up episodes of shows not already in it
      const seen = new Set(resume.map((i) => i.SeriesId ?? i.Id));
      const merged = [...resume, ...next.filter((n) => !seen.has(n.SeriesId ?? n.Id))];
      setContinue(merged);
      if (merged[0]) setFocused((f) => f ?? merged[0]);
    });
    manifest().then((m) => {
      if (!alive || !m) return;
      setCarousels(m.carousels.slice(0, MAX_CAROUSEL_ROWS));
      setLists(m.letterboxd.slice(0, MAX_LETTERBOXD_ROWS));
    });
    return () => {
      alive = false;
    };
  }, []);

  // No Continue Watching: the hero starts on the first carousel's first title
  useEffect(() => {
    if (focused || continueItems === null || continueItems.length || !carousels[0]) return;
    carouselLoader(carousels[0])().then((items) => items[0] && setFocused((f) => f ?? items[0]));
  }, [focused, continueItems, carousels]);

  const onFocusItem = useCallback((i: Item) => setFocused(i), []);

  return (
    <div className="home">
      <Hero item={focused} />
      <div className="rows">
        {continueItems && continueItems.length > 0 && <Row title="Continue Watching" items={continueItems} onFocusItem={onFocusItem} autoFocusFirst />}
        {carousels.map((c) => (
          <Row key={c.id} title={c.title} load={carouselLoader(c)} onFocusItem={onFocusItem} />
        ))}
        {lists.map((l) => (
          <Row key={l.slug} title={LETTERBOXD_PREFIX + l.name} load={letterboxdLoader(l)} onFocusItem={onFocusItem} />
        ))}
      </div>
    </div>
  );
}
