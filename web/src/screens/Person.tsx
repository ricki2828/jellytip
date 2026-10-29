// Filmography for an actor/director (from a person spotlight): everything in the library they're in, newest first.
import { useEffect, useState } from "react";
import { getItems, imageUrl, jf, uid, type Item } from "../api/jellyfin";
import { go } from "../lib/router";
import { PosterCard } from "../components/specials/Specials";

export function Person({ id, name }: { id: string; name: string | null }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const [person, setPerson] = useState<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string } | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let alive = true;
    getItems({ PersonIds: id, Recursive: true, IncludeItemTypes: "Movie,Series", SortBy: "PremiereDate,SortName", SortOrder: "Descending,Ascending", Limit: 200 })
      .then((r) => alive && setItems(r.Items))
      .catch(() => alive && setFailed(true));
    jf<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string }>(`/Users/${uid()}/Items/${id}`)
      .then((p) => alive && setPerson(p))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [id]);
  const title = person?.Name ?? name ?? "";
  const tag = person?.ImageTags?.Primary;
  const unwatched = (items ?? []).filter((i) => !i.UserData?.Played).length;
  return (
    <div className="person-page">
      <div className="person-head">
        {tag && <img src={imageUrl(id, "Primary", { tag, fillWidth: 240, fillHeight: 360, quality: 85 })} alt="" />}
        <div>
          <h1 className="page-title">{title}</h1>
          {items && (
            <p className="line dim">
              {items.length} in the library{unwatched ? ` · ${unwatched} unwatched` : ""}
            </p>
          )}
          {person?.Overview && <p className="overview two">{person.Overview}</p>}
        </div>
      </div>
      {failed && <p className="empty">Couldn't load this filmography.</p>}
      <div className="poster-grid">
        {(items ?? []).map((i) => (
          <PosterCard key={i.Id} item={i} width={0} onOpen={() => go(`/item/${i.Id}`)} />
        ))}
      </div>
    </div>
  );
}
