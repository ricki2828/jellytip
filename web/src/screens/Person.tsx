// Filmography for an actor/director (from a person spotlight): everything in the library they're in, newest first.
import { useEffect, useRef, useState } from "react";
import { getItems, imageUrl, jf, uid, type Item } from "../api/jellyfin";
import { go } from "../lib/router";
import { personCreditsChecked } from "../api/iptv";
import { browserEnv, startBoundedPoll, type PollState } from "../lib/boundedPoll";
import type { VodItem } from "../lib/iptvVod";
import { IptvPoster } from "../components/IptvTile";
import { PosterCard } from "../components/PosterCard";
import "./Person.css";
import { FollowButton } from "../components/FollowButton";

export function Person({ id, name }: { id: string; name: string | null }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const [person, setPerson] = useState<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string } | null>(null);
  const [failed, setFailed] = useState(false);
  const [iptv, setIptv] = useState<VodItem[]>([]);
  const [iptvState, setIptvState] = useState<PollState>("checking");
  const pollRef = useRef<{ retry(): void } | null>(null);
  useEffect(() => {
    let alive = true;
    setItems(null);
    setPerson(null);
    setFailed(false);
    getItems({ PersonIds: id, Recursive: true, IncludeItemTypes: "Movie,Series", SortBy: "PremiereDate,SortName", SortOrder: "Descending,Ascending", Limit: 200 })
      .then((r) => alive && setItems(r.Items))
      .catch(() => alive && setFailed(true));
    jf<{ Name?: string; ImageTags?: Record<string, string>; Overview?: string }>(`/Users/${uid()}/Items/${id}`)
      .then((p) => alive && setPerson(p))
      .catch(() => undefined);
    // Titles they are in that the library lacks but this person's IPTV has. The first answer may be partial, so ask again
    // a bounded number of times (backoff, paused while hidden, cancelled on leaving); never show a stale person's titles.
    setIptv([]);
    setIptvState("checking");
    const poll = startBoundedPoll<VodItem[]>({
      env: browserEnv,
      fetch: async (signal) => {
        const c = await personCreditsChecked(id, signal);
        return c.kind === "ok" ? { kind: "ok", value: c.iptvItems, pending: c.pending } : c;
      },
      onValue: (v) => alive && setIptv(v),
      onState: (s) => alive && setIptvState(s),
    });
    pollRef.current = poll;
    return () => {
      alive = false;
      poll.cancel();
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
      {iptvState === "checking" && <p className="dim person-iptv-status">Still checking IPTV…</p>}
      {(iptvState === "timeout" || iptvState === "error") && (
        <p className="dim person-iptv-status">
          {iptvState === "timeout" ? "IPTV check timed out" : "IPTV check failed"}
          {iptv.length > 0 ? " (showing what was found so far)" : ""}.
          <button data-nav className="chip" onClick={() => pollRef.current?.retry()}>
            Retry
          </button>
        </p>
      )}
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
