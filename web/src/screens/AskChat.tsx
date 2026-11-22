// #/ask/chat: conversational discovery. You type (or say) what you feel like; the server turns each turn into chips and picks the
// titles from your library. The AI only proposes filter changes; the reply line is rendered by the server from what it applied.
// State machine: lib/askChat.ts (pure, tested). While a turn is pending the cards are hidden (skeleton) and the chips are the local
// reading, dimmed: nothing unfiltered ever shows under a text that may tighten safety (kids).
import { useEffect, useMemo, useRef, useState } from "react";
import { getItems, uid, type Item } from "../api/jellyfin";
import { companionCall } from "../api/companionCall";
import { itemsByIds } from "../components/ServerRow";
import { Card } from "../components/Card";
import { IptvCard } from "../components/IptvTile";
import { EXAMPLES } from "../lib/askParse";
import { isNative } from "../lib/native";
import { go } from "../lib/router";
import {
  alignCards, applyError, applyResponse, cardKeyOf, ChatSender, dismissNotice, displayChips, emptyState, isIptvCard, libraryIds, noticeSeen, NOTICE_TEXT, refineChat, retry, scopeHint, submitText, startOver, tapChip, TOO_LONG_TEXT,
  type Call, type ChatBody, type ChatState, type Op,
} from "../lib/askChat";
import { afterEnd, errorText, heardNothing, isQuietError, isTap, joinSpeech, sessionText, TAP_SILENCE_MS } from "../lib/chatVoice";
import { Ask } from "./Ask";
import "./Ask.css";
import "./AskChat.css";

type Fetched = { key: string; items: Item[]; people: Item[] };
type Speech = {
  abort(): void; stop(): void; start(): void; lang: string; interimResults: boolean; continuous: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null; onend: (() => void) | null; onerror: ((e: { error?: string }) => void) | null;
};
const speechCtor = (): (new () => Speech) | null => {
  if (isNative || typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: new () => Speech; webkitSpeechRecognition?: new () => Speech };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
};

export function AskChat() {
  const userId = uid();
  const [state, setState] = useState<ChatState>(emptyState);
  const stateRef = useRef(state);
  const sender = useRef<ChatSender | null>(null);
  if (!sender.current) sender.current = new ChatSender(companionCall as Call);
  const [text, setText] = useState("");
  const textRef = useRef("");
  textRef.current = text;
  const [fetched, setFetched] = useState<Fetched | null>(null);
  const [notice, setNotice] = useState(() => {
    try {
      return !noticeSeen(localStorage);
    } catch {
      return true;
    }
  });
  const [listening, setListening] = useState(false);
  const [voiceErr, setVoiceErr] = useState<string | null>(null);
  const [micFocus, setMicFocus] = useState(false);
  // press-to-talk bookkeeping (refs: the recognizer's callbacks must see the current values)
  const voice = useRef({ held: false, tap: false, active: false, committed: "", live: "", restarts: 0, downAt: 0, base: "", quiet: null as ReturnType<typeof setTimeout> | null });
  const input = useRef<HTMLInputElement>(null);
  const turnsEnd = useRef<HTMLDivElement>(null);
  const Speech = useMemo(speechCtor, []);
  const rec = useRef<Speech | null>(null);

  const commit = (n: ChatState) => {
    stateRef.current = n;
    setState(n);
  };
  const run = (body: ChatBody) => {
    void sender.current!.send(body).then((out) => {
      if (out.kind === "aborted") return; // a newer request replaced it
      if (out.kind === "ok") return commit(applyResponse(stateRef.current, out.resp));
      const e = applyError(stateRef.current, body.rev, out.status);
      commit(e.state);
      if (e.resend) run(e.resend); // 400 / 413: once more with the oldest turns trimmed
    });
  };
  const dispatch = (r: { state: ChatState; body: ChatBody } | null) => {
    if (!r) return; // refused: a turn is pending (the controls are disabled too)
    commit(r.state);
    run(r.body);
  };
  const submit = (v: string) => {
    const r = submitText(stateRef.current, v);
    if (!r) return; // empty, or a turn is already pending: keep what was typed
    setText("");
    dispatch(r);
  };
  const tap = (op: Op) => dispatch(tapChip(stateRef.current, op));

  // ---- press-to-talk ----
  const stopVoice = () => {
    const v = voice.current;
    v.active = v.held = v.tap = false;
    if (v.quiet) clearTimeout(v.quiet);
    v.quiet = null;
    const r = rec.current;
    rec.current = null;
    if (r) {
      r.onresult = r.onend = r.onerror = null; // nothing from a stopped recognizer reaches the screen
      try {
        r.abort();
      } catch {
        /* already stopped */
      }
    }
    setListening(false);
  };
  // the utterance is over: send what was heard (never blank), or say nothing was heard
  const finishVoice = (send: boolean) => {
    const text = voice.current.live;
    stopVoice();
    if (!send) return;
    if (heardNothing(voice.current.base, text)) return setVoiceErr("Didn't catch that"); // typed text stays in the box, nothing is sent
    setText(text);
    submit(text);
  };
  const beginSession = () => {
    if (!Speech) return;
    const v = voice.current;
    const r = new Speech();
    rec.current = r;
    r.lang = navigator.language || "en-US";
    r.continuous = true;
    r.interimResults = true;
    r.onresult = (e) => {
      if (rec.current !== r) return;
      v.live = joinSpeech(v.committed, sessionText(e.results));
      setText(v.live);
      if (v.tap) armQuiet();
    };
    r.onerror = (e) => {
      if (rec.current !== r) return;
      const code = e?.error ?? "";
      if (isQuietError(code)) return; // onend follows
      const text = v.live;
      stopVoice();
      if (text.trim()) setText(text);
      setVoiceErr(errorText(code));
    };
    r.onend = () => {
      if (rec.current !== r) return;
      v.committed = v.live;
      if (afterEnd(v.held, v.tap, v.restarts) === "restart") {
        v.restarts++;
        beginSession();
      } else {
        finishVoice(true);
      }
    };
    try {
      r.start();
    } catch {
      stopVoice();
      setVoiceErr(errorText(""));
    }
  };
  // tap mode: a pause of TAP_SILENCE_MS after the last words ends it (stop() lets the final result arrive, then onend sends)
  const armQuiet = () => {
    const v = voice.current;
    if (v.quiet) clearTimeout(v.quiet);
    v.quiet = setTimeout(() => {
      rec.current?.stop();
      v.quiet = setTimeout(() => finishVoice(true), 3000); // onend never came: send what we have
    }, TAP_SILENCE_MS);
  };
  const micDown = () => {
    const v = voice.current;
    if (!Speech || stateRef.current.pending) return;
    if (v.active) {
      if (v.tap) rec.current?.stop(); // a second tap while a tap-listen runs: send now
      return;
    }
    setVoiceErr(null);
    Object.assign(v, { active: true, held: true, tap: false, restarts: 0, downAt: Date.now(), committed: textRef.current, live: textRef.current, base: textRef.current });
    setListening(true);
    beginSession();
  };
  const micUp = () => {
    const v = voice.current;
    if (!v.active || !v.held) return;
    v.held = false;
    if (isTap(Date.now() - v.downAt)) {
      v.tap = true;
      armQuiet();
    } else {
      rec.current?.stop(); // the final result arrives, then onend sends
      v.quiet = setTimeout(() => finishVoice(true), 3000); // a recognizer that never answers stop()
    }
  };

  // Start over: everything goes (also what is in flight), no server call; works while a turn is pending
  const doStartOver = () => {
    stopVoice();
    sender.current!.cancel();
    commit(startOver(stateRef.current));
    setText("");
    setVoiceErr(null);
    input.current?.focus();
  };

  // a turn went pending while a mic session was live (the mic is disabled): drop the session, never leave it "held"
  useEffect(() => {
    if (state.pending && voice.current.active) stopVoice();
  }, [state.pending]); // eslint-disable-line react-hooks/exhaustive-deps

  // The chat service warms up while the screen is open (fire and forget)
  useEffect(() => {
    sender.current!.warm();
  }, []);
  // leaving the screen stops a recognition that is still listening
  useEffect(
    () => () => {
      stopVoice();
      sender.current?.cancel();
    },
    [],
  );
  useEffect(() => {
    turnsEnd.current?.scrollIntoView?.({ block: "nearest" });
  }, [state.turns.length, state.pending]);

  // The server's cards, exactly its ids in its order: fetched as library Items; an id the fetch drops is a gap, never a substitute
  // (TV cards are drawn from the server's own fields: only the library ids are fetched)
  const ids = useMemo(() => libraryIds(state.cards), [state.cards]);
  const cardKey = ids.join(",");
  const resultKey = useMemo(() => state.cards.map(cardKeyOf).join(","), [state.cards]);
  useEffect(() => {
    if (!cardKey) {
      setFetched({ key: "", items: [], people: [] });
      return;
    }
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

  const pending = state.pending != null;
  const ready = fetched?.key === cardKey;
  const shown = useMemo(() => (ready && fetched ? alignCards(state.cards, fetched.items) : []), [ready, fetched, state.cards]);
  const refine = useMemo(() => (ready && !pending && fetched ? refineChat(fetched.items, fetched.people, state.chips) : []), [ready, pending, fetched, state.chips]);
  const chips = displayChips(state);
  const hasProvisional = chips.some((c) => (c as { provisional?: boolean }).provisional);
  const fresh = state.turns.length === 0 && !pending && state.chips.length === 0 && !state.error;

  if (state.error === "off") return <Ask key={userId} />; // chat is switched off on the server: the classic Ask screen

  const lastTurn = state.turns.length - 1;
  return (
    <div className="ask askchat">
      <div className="ac-head">
        <h1 className="page-title">Ask</h1>
        <button className="btn small ghost" data-nav onClick={() => go("/ask")}>
          Classic Ask
        </button>
      </div>
      <div className="ac-cols">
        <section className="ac-chat" aria-label="Conversation">
          <div className="ac-turns" aria-live="polite">
            {fresh && <p className="ac-hello">Ask like you'd ask a friend.</p>}
            {state.turns.map((t, i) => (
              <div className="ac-turn" key={i}>
                <p className="ac-user">{t.user}</p>
                <p className="ac-reply">
                  {t.reply}
                  {i === lastTurn && state.mode === "fallback" && !pending && (
                    <span className="ac-tag" title="The AI wasn't used for this turn: a simpler reading was applied.">
                      basic mode
                    </span>
                  )}
                </p>
              </div>
            ))}
            {state.pending?.body.text && (
              <div className="ac-turn">
                <p className="ac-user">{state.pending.body.text}</p>
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
            {!state.pending && state.turns.length === 0 && state.reply && <p className="ac-reply">{state.reply}</p>}
            <div ref={turnsEnd} />
          </div>
          <form
            className="ask-form ac-form"
            onSubmit={(e) => {
              e.preventDefault();
              submit(text);
            }}
          >
            <input
              ref={input}
              className="search-input"
              data-nav
              autoFocus
              type="text"
              enterKeyHint="send"
              maxLength={300}
              aria-label="What do you feel like watching?"
              placeholder={state.turns.length ? "Refine it: something shorter, with Paul Rudd…" : "e.g. here with my kids, something funny, not scary"}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
            <button type="submit" className="btn primary" data-nav disabled={!text.trim() || pending}>
              Send
            </button>
            <button type="button" className="btn" data-nav data-testid="start-over" onClick={doStartOver}>
              Start over
            </button>
            {Speech && (
              <button
                type="button"
                className={listening ? "btn on" : "btn"}
                data-nav
                data-testid="mic"
                title="Hold to talk, release to send (or hold Space). Voice uses your browser's speech service"
                aria-label="Hold to talk, release to send. Voice uses your browser's speech service"
                aria-pressed={listening}
                disabled={pending}
                onPointerDown={(e) => {
                  if (e.button !== 0) return;
                  e.currentTarget.setPointerCapture?.(e.pointerId); // the release is seen even off the button
                  micDown();
                }}
                onPointerUp={micUp}
                onPointerCancel={micUp}
                onKeyDown={(e) => {
                  if (e.key !== " " && e.key !== "Enter") return;
                  e.preventDefault(); // no click from the keyboard: the hold is the gesture
                  if (!e.repeat) micDown();
                }}
                onKeyUp={(e) => {
                  if (e.key !== " " && e.key !== "Enter") return;
                  e.preventDefault();
                  micUp();
                }}
                onFocus={() => setMicFocus(true)}
                onBlur={() => {
                  setMicFocus(false);
                  micUp(); // focus left while held: never leave the mic open
                }}
                onContextMenu={(e) => e.preventDefault()}
              >
                {listening ? "Listening…" : "Hold to talk"}
              </button>
            )}
          </form>
          {(listening || voiceErr || (micFocus && Speech && !pending)) && (
            <p className="ac-voice" data-testid="voice-hint" role="status" aria-live="polite">
              {listening ? "Listening… release to send" : (voiceErr ?? "Hold the mic (or Space) to talk")}
            </p>
          )}
          {notice && (
            <p className="ac-notice">
              {NOTICE_TEXT}{" "}
              <button
                className="ac-link"
                data-nav
                onClick={() => {
                  try {
                    dismissNotice(localStorage);
                  } catch {
                    /* blocked */
                  }
                  setNotice(false);
                }}
              >
                Got it
              </button>
            </p>
          )}
        </section>

        <section className="ac-results" aria-label="Results">
          {state.error === "unreachable" && (
            <div className="rows-error" role="alert">
              <p>Couldn't reach chat.</p>
              <button className="btn" data-nav onClick={() => dispatch(retry(stateRef.current))}>
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
          {fresh && (
            <div>
              <h2 className="row-title">Try</h2>
              <div className="chips">
                {EXAMPLES.map((q) => (
                  <button key={q} className="chip" data-nav disabled={pending} onClick={() => submit(q)}>
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}
          {chips.length > 0 && (
            <div className="chips ask-chips" aria-label="How this was read">
              {chips.map((c) => {
                const prov = (c as { provisional?: boolean }).provisional;
                const off = (c as { applied?: boolean }).applied === false;
                const removable = !prov && (c as { removable?: boolean }).removable !== false;
                return removable ? (
                  <button key={c.id} className={`chip on${off ? " ac-off" : ""}`} data-nav disabled={pending} aria-label={`Remove ${c.label}`} onClick={() => tap({ remove: c.id })}>
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
                  <p className="ask-note">{n.text}</p>
                  {n.actions && (
                    <div className="chips">
                      {n.actions.map((a) => (
                        <button key={a.label} className="btn small" data-nav disabled={pending} onClick={() => tap(a.op)}>
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
            <div className="grid ask-grid" aria-busy="true">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="card fluid card-skeleton" />
              ))}
            </div>
          ) : (
            shown.length > 0 && (
              <div className="grid ask-grid" data-result={resultKey}>
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
          {!pending && refine.length > 0 && (
            <div className="chips ask-refine" aria-label="Narrow it down">
              <span className="ask-refine-label">Narrow it down</span>
              {refine.map((c) => (
                <button key={c.id} className="chip" data-nav disabled={pending} onClick={() => tap({ add: c.data })}>
                  + {c.label}
                </button>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
