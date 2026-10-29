import { useEffect, useRef, useState } from "react";
import type { Item } from "../api/jellyfin";
import { Card } from "./Card";

interface Props {
  title: string;
  /** Items, or a loader called once the row nears the viewport. */
  items?: Item[];
  load?: () => Promise<Item[]>;
  onFocusItem?: (item: Item) => void;
  autoFocusFirst?: boolean;
  onOpen?: (item: Item) => void;
}

export function Row({ title, items, load, onFocusItem, autoFocusFirst, onOpen }: Props) {
  const ref = useRef<HTMLElement>(null);
  const [loaded, setLoaded] = useState<Item[] | null>(items ?? null);
  const [failed, setFailed] = useState(false);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (items) setLoaded(items);
  }, [items]);

  useEffect(() => {
    if (items || !loadRef.current || loaded) return;
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (es) => {
        if (!es.some((e) => e.isIntersecting)) return;
        io.disconnect();
        loadRef.current!()
          .then(setLoaded)
          .catch(() => setFailed(true));
      },
      { rootMargin: "400px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [items, loaded]);

  if (failed || (loaded && loaded.length === 0)) return null;
  return (
    <section className="row" ref={ref}>
      <h2 className="row-title">{title}</h2>
      <div className="row-scroll">
        {loaded
          ? loaded.map((it, i) => <Card key={it.Id} item={it} onFocusItem={onFocusItem} autoFocus={autoFocusFirst && i === 0} onOpen={onOpen} />)
          : Array.from({ length: 6 }, (_, i) => <div key={i} className="card card-skeleton" />)}
      </div>
    </section>
  );
}
