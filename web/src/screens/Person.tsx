// Filmography for an actor/director (from a person spotlight): everything in the library they're in, newest first.
import { useEffect, useState } from "react";
import { getItems, imageUrl, jf, uid, type Item } from "../api/jellyfin";
import { go } from "../lib/router";
import { personCredits } from "../api/iptv";
import type { VodItem } from "../lib/iptvVod";
import { IptvPoster } from "../components/IptvTile";
import { PosterCard } from "../components/PosterCard";
import { FollowButton } from "../components/FollowButton";

export function Person({ id, name }: { id: string; name: string | null }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const [person, setPerson] = useState<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string } | null>(null);
  const [failed, setFailed] = useState(false);
  const [iptv, setIptv] = useState<VodItem[]>([]);
  useEffect(() => {
    let alive = true;
    getItems({ PersonIds: id, Recursive: true, IncludeItemTypes: "Movie,Series", SortBy: "PremiereDate,SortName", SortOrder: "Descending,Ascending", Limit: 200 })
      .then((r) => alive && setItems(r.Items))
      .catch(() => alive && setFailed(true));
    jf<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string }>(`/Users/${uid()}/Items/${id}`)
      .then((p) => alive && setPerson(p))
      .catch(() => undefined);
    // Titles they are in that the library lacks but this person's IPTV has. The first answer may be partial: once more after 5 s.
    let timer: number | undefined;
    setIptv([]);
    void personCredits(id).then((c) => {
      if (!alive || !c) return;
      setIptv(c.iptvItems);
      if (c.pending)
        timer = window.setTimeout(() => void personCredits(id).then((c2) => alive && c2 && setIptv(c2.iptvItems)), 5000);
    });
    return () => {
      alive = false;
      window.clearTimeout(timer);
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
          {title && (
            <div className="buttons">
              <FollowButton personId={id} name={title} />
            </div>
          )}
        </div>
      </div>
      {failed && <p className="empty">Couldn't load this filmography.</p>}
      <div className="poster-grid">
        {(items ?? []).map((i) => (
          <PosterCard key={i.Id} item={i} width={0} onOpen={() => go(`/item/${i.Id}`)} />
        ))}
      </div>
      {iptv.length > 0 && (
        <section className="row poster-row person-iptv">
          <h2 className="row-title">On IPTV</h2>
          <div className="poster-grid">
            {iptv.map((v) => (
              <IptvPoster key={v.id} item={v} width={0} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
