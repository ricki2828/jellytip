// The one box's chat (screens/Search.tsx): the conversation, the server's chips, notes and picks. Rendering only; the turn-state
// machine is lib/askChat.ts and the box (typing, voice, Start over) belongs to the page. While a turn is pending the picks are hidden
// (skeleton) and the chips are the local reading, dimmed: nothing unfiltered ever shows under a text that may tighten safety (kids).
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { getItems, type Item } from "../api/jellyfin";
import { itemsByIds } from "./ServerRow";
import { Card } from "./Card";
import { IptvCard } from "./IptvTile";
import { alignCards, cardKeyOf, displayChips, isIptvCard, libraryIds, refineChat, scopeHint, TOO_LONG_TEXT, type ChatState, type Op } from "../lib/askChat";
import "./ChatAnswer.css";

export type ChatFetched = { key: string; items: Item[]; people: Item[] };

/** The server's picks as library Items (its ids, its order; TV picks are drawn from their own fields). `initial`: kept from before Back. */
export function useChatCards(state: ChatState, userId: string, initial: ChatFetched | null): ChatFetched | null {
  const ids = useMemo(() => libraryIds(state.cards), [state.cards]);
  const cardKey = ids.join(",");
  const [fetched, setFetched] = useState<ChatFetched | null>(initial);
  const have = useRef(fetched);
  have.current = fetched;
  useEffect(() => {
    if (!cardKey) {
      setFetched({ key: "", items: [], people: [] });
      return;
    }
    if (have.current?.key === cardKey) return; // already fetched (Back): nothing is asked again
    let alive = true;
    itemsByIds(ids)
      .then((items) => {
        if (!alive) return;
        setFetched({ key: cardKey, items, people: [] });
        if (ids.length > 1)
          getItems({ Ids: ids.join(","), Fields: "People", Limit: ids.length })
            .then((pr) => alive && setFetched((f) => (f && f.key === cardKey ? { ...f, people: pr.Items as Item[] } : f)))
            .catch(() => {}); // no people = no people chips
      })
      .catch(() => alive && setFetched({ key: cardKey, items: [], people: [] }));
    return () => {
      alive = false;
    };
    // cardKey stands for the ids; userId so another signed-in user never sees these tiles
  }, [cardKey, userId]); // eslint-disable-line react-hooks/exhaustive-deps
  return fetched;
}

export function ChatAnswer({
  state, fetched, onTap, onRetry, compact = false, footer,
}: {
  state: ChatState;
  fetched: ChatFetched | null;
  onTap: (op: Op) => void;
  onRetry: () => void;
  /** Under search results (both): the latest reply and the picks, no transcript. */
  compact?: boolean;
  footer?: ReactNode;
}) {
  const ids = libraryIds(state.cards);
  const cardKey = ids.join(",");
  const resultKey = state.cards.map(cardKeyOf).join(",");
  const pending = state.pending != null;
  const ready = fetched?.key === cardKey;
  const shown = useMemo(() => (ready && fetched ? alignCards(state.cards, fetched.items) : []), [ready, fetched, state.cards]);
  const refine = useMemo(() => (ready && !pending && fetched ? refineChat(fetched.items, fetched.people, state.chips) : []), [ready, pending, fetched, state.chips]);
  const chips = displayChips(state);
  const hasProvisional = chips.some((c) => (c as { provisional?: boolean }).provisional);
  const lastTurn = state.turns.length - 1;
  const basic = state.basic && !pending ? <span className="ac-tag" data-testid="basic-mode" title="The AI wasn't available for this turn: a simpler reading was applied.">basic mode</span> : null;
  const turns = compact ? state.turns.slice(-1) : state.turns;

  return (
    <div className={`chat-answer${compact ? " compact" : ""}`}>
      <div className="ac-turns" aria-live="polite">
        {turns.map((t, i) => (
          <div className="ac-turn" key={i}>
            {!compact && <p className="ac-user">{t.user}</p>}
            <p className="ac-reply">
              {t.reply}
              {(compact || i === lastTurn) && basic}
            </p>
          </div>
        ))}
        {state.pending?.body.text && (
          <div className="ac-turn">
            {!compact && <p className="ac-user">{state.pending.body.text}</p>}
            <p className="ac-reply ac-dim" role="status">
              Reading…
            </p>
          </div>
        )}
        {state.pending && !state.pending.body.text && (
          <p className="ac-reply ac-dim" role="status">
            Updating…
          </p>
        )}
      </div>
      {state.error === "unreachable" && (
        <div className="rows-error" role="alert">
          <p>Couldn't reach chat.</p>
          <button className="btn" data-nav onClick={onRetry}>
            Retry
          </button>
        </div>
      )}
      {state.error === "tooLong" && (
        <div className="rows-error" role="alert">
          <p>{TOO_LONG_TEXT}</p>
        </div>
      )}
      {state.error === "signedOut" && (
        <div className="rows-error" role="alert">
          <p>You're signed out. Sign in again to use chat.</p>
        </div>
      )}
      {chips.length > 0 && (
        <div className="chips ac-chips" aria-label="How this was read">
          {chips.map((c) => {
            const prov = (c as { provisional?: boolean }).provisional;
            const off = (c as { applied?: boolean }).applied === false;
            const removable = !prov && (c as { removable?: boolean }).removable !== false;
            return removable ? (
              <button key={c.id} className={`chip on${off ? " ac-off" : ""}`} data-nav disabled={pending} aria-label={`Remove ${c.label}`} onClick={() => onTap({ remove: c.id })}>
                {c.label}
                {off && <span className="ac-sub"> (not applied)</span>}
                <span aria-hidden> ×</span>
              </button>
            ) : (
              <span key={c.id} className={`chip on ac-static${prov ? " ac-prov" : ""}`}>
                {c.label}
              </span>
            );
          })}
          {hasProvisional && <span className="ac-hint">reading…</span>}
        </div>
      )}
      {state.notes.length > 0 && !pending && (
        <div className="ac-notes">
          {state.notes.map((n) => (
            <div key={n.code + n.text} className="ac-note">
              <p className="ac-note-text">{n.text}</p>
              {n.actions && (
                <div className="chips">
                  {n.actions.map((a) => (
                    <button key={a.label} className="btn small" data-nav disabled={pending} onClick={() => onTap(a.op)}>
                      {a.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      {state.sources.length > 0 && !pending && state.turns.length > 0 && (
        <p className="ac-scope" aria-label="Searched">
          {scopeHint(state.sources)}
          {state.matched > 0 ? ` · ${state.matched} fit` : ""}
        </p>
      )}
      {pending || (cardKey !== "" && !ready) ? (
        <div className="grid ac-grid" aria-busy="true">
          {Array.from({ length: compact ? 4 : 8 }, (_, i) => (
            <div key={i} className="card fluid card-skeleton" />
          ))}
        </div>
      ) : (
        shown.length > 0 && (
          <div className="grid ac-grid" data-result={resultKey}>
            {shown.map(({ card, item }) =>
              item ? (
                <Card key={item.Id} item={item} width={0} />
              ) : isIptvCard(card) ? (
                <IptvCard
                  key={cardKeyOf(card)}
                  width={0}
                  item={{
                    id: card.iptvId,
                    title: card.title ?? "",
                    year: card.year ?? null,
                    quality: card.quality ?? [],
                    poster: card.poster ?? null,
                    backdrop: card.backdrop ?? null,
                    tech: card.tech ?? [],
                    kind: card.kind === "film" ? "movie" : "series",
                    tmdbId: card.tmdbId ?? null,
                  }}
                />
              ) : null,
            )}
          </div>
        )
      )}
      {!compact && !pending && refine.length > 0 && (
        <div className="chips ac-refine" aria-label="Narrow it down">
          <span className="ac-refine-label">Narrow it down</span>
          {refine.map((c) => (
            <button key={c.id} className="chip" data-nav disabled={pending} onClick={() => onTap({ add: c.data })}>
              + {c.label}
            </button>
          ))}
        </div>
      )}
      {footer}
    </div>
  );
}
