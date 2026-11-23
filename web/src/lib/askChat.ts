// Chat (the one box's chat view, screens/Search.tsx): the turn-state machine. Pure and tested (web/test/askChat.test.ts); the screen only renders it.
// The server (POST /v1/ask/chat on the companion) turns each turn into chips and picks the titles; the client never ranks cards.
// Plan: ~/ai/plans/jellytip-chat-search.md sections 3 (wire, rev rule, display rule), 3a (chips), 6 (UI), 7 (notice).
//
// Display rule: while a turn is pending the previous cards are HIDDEN (a skeleton shows) and the chips are the local reading
// (provisional, dimmed); a text can tighten safety (kids), so stale cards are never shown under it.
import type { Item } from "../api/jellyfin";
import { chipFor, parseAsk, refineOptions, type Chip, type ChipData } from "./askParse.ts";

export const MAX_TURNS = 6;
export const MAX_TEXT = 300;
export const MAX_REMOVED = 40;

// ---------- wire ----------

/** Server-only chip (mood / like / source / noscary): rendered from its label, echoed back verbatim, never given to chipFor. */
export interface ServerChip {
  id: string;
  label: string;
  server: true;
  data: { t: "mood" | "like" | "source" | "noscary"; [k: string]: unknown };
  removable?: boolean;
  applied?: boolean;
  protected?: boolean;
}
/** A native chip as the server returns it: t5k's {id, label, data} plus flags. */
export type NativeChip = Chip & { removable?: boolean; protected?: boolean; applied?: boolean };
export type ChatChip = NativeChip | ServerChip;
/** The ChipData `t` values the client can build a chip for (chipFor has no default branch: anything else would throw). */
export const NATIVE_T = ["genre", "notGenre", "kind", "maxRuntime", "minRuntime", "years", "unplayed", "played", "kids", "minRating", "maxSeasons", "person"];
/** chipFor that never throws or returns nothing: null for anything that is not a known native ChipData. */
export function safeChipFor(d: unknown): Chip | null {
  if (!d || typeof d !== "object" || !NATIVE_T.includes((d as { t?: string }).t ?? "")) return null;
  try {
    const c = chipFor(d as ChipData);
    return c && typeof c.id === "string" ? c : null;
  } catch {
    return null;
  }
}
export const isServerChip = (c: ChatChip): c is ServerChip => (c as ServerChip).server === true;

export type Op = { add: ChipData } | { remove: string };
export interface Turn {
  user: string;
  reply: string;
}
/** A library title: fetched from Jellyfin by `id`. */
export interface LibraryCard {
  kind: "film" | "series";
  source: "library";
  id: string;
  title?: string;
  year?: number | null;
  certification?: string | null;
  runtime?: number | null;
  [k: string]: unknown;
}
/** A TV (IPTV) title: the /v1/search IPTV entry shape, drawn straight from these fields (never fetched from Jellyfin). */
export interface IptvChatCard {
  kind: "film" | "series";
  source: "iptv";
  iptvId: string;
  title?: string;
  year?: number | null;
  tmdbId?: number | null;
  rating?: number | null;
  poster?: string | null;
  backdrop?: string | null;
  quality?: string[];
  tech?: string[];
  catalog?: boolean;
  certification?: string | null;
  runtime?: number | null;
  [k: string]: unknown;
}
export type ChatCard = LibraryCard | IptvChatCard;
export const isIptvCard = (c: ChatCard): c is IptvChatCard => c.source === "iptv";
/** The key that identifies a card in React and in "did the result change". */
export const cardKeyOf = (c: ChatCard): string => (isIptvCard(c) ? `iptv:${c.iptvId}` : c.id);
/** Library ids only: those are the ones fetched from Jellyfin. */
export const libraryIds = (cards: ChatCard[]): string[] => cards.filter((c): c is LibraryCard => c.source === "library").map((c) => c.id);
/** The scope hint under the reading: "Library", "Library + TV" or "TV", from the sources the server actually searched. */
export function scopeHint(sources: string[]): string {
  const lib = sources.includes("library");
  const tv = sources.includes("iptv");
  return lib && tv ? "Library + TV" : tv ? "TV" : lib ? "Library" : "";
}

export interface ChatNote {
  text: string;
  code: string;
  actions?: { label: string; op: Op }[];
}
export interface ChatBody {
  v: 1;
  rev: number;
  text?: string;
  op?: Op;
  turns: Turn[];
  chips: ChatChip[];
  removed: string[];
}
export interface ChatResponse {
  v: 1;
  rev: number;
  mode: "llm" | "fallback";
  reply: string;
  chips: ChatChip[];
  cards: ChatCard[];
  notes: ChatNote[];
  sources: string[];
  matched: number;
  partial: boolean;
  /** 23-Nov (additive): true only when the AI was unavailable for a typed turn (outage, timeout, busy, off, daily cap). Absent = an older server. */
  basic?: boolean;
  /** 23-Nov (additive): what the turn was. */
  turn?: "text" | "op";
}

/**
 * "basic mode" under the turn: only when the server says `basic`. An older server has no field: then only for a fallback answer to a
 * typed turn (a chip tap never asks the AI, so it is never "basic").
 */
export const basicLabel = (r: ChatResponse, b: ChatBody): boolean => (typeof r.basic === "boolean" ? r.basic : r.mode === "fallback" && !!b.text);

// ---------- state ----------

export type ChatError = null | "off" | "signedOut" | "unreachable" | "tooLong";
export const TOO_LONG_TEXT = "Couldn't send that \u2014 try a shorter message";
export interface ChatState {
  turns: Turn[];
  /** The server's chips, exactly as received (echoed back verbatim). */
  chips: ChatChip[];
  removed: string[];
  /** The latest revision issued; only a response with this rev is applied. */
  rev: number;
  pending: { body: ChatBody; /** already re-sent once with fewer turns (400 / 413) */ trimmed?: boolean } | null;
  /** The server's one-line reading of the current state. */
  reply: string;
  cards: ChatCard[];
  notes: ChatNote[];
  mode: "llm" | "fallback" | null;
  /** Show "basic mode" under the latest turn (basicLabel). */
  basic: boolean;
  sources: string[];
  matched: number;
  error: ChatError;
  /** The body that could not be delivered (error "unreachable"): Retry re-sends it. */
  failed: ChatBody | null;
}

export const emptyState = (): ChatState => ({
  turns: [], chips: [], removed: [], rev: 0, pending: null, reply: "", cards: [], notes: [], mode: null, basic: false, sources: [], matched: 0, error: null, failed: null,
});

const bodyFor = (s: ChatState, rev: number, extra: { text?: string; op?: Op }): ChatBody => ({
  v: 1,
  rev,
  ...extra,
  turns: s.turns.slice(-MAX_TURNS).map((t) => ({ user: t.user.slice(0, MAX_TEXT), reply: t.reply })),
  chips: s.chips, // verbatim: a server chip round-trips byte-identical
  removed: s.removed.slice(-MAX_REMOVED),
});

/** "Start over": turns, chips, removed, cards, notes and the error are cleared wholesale. The rev moves past anything issued and nothing is
 *  pending, so a response or error still in flight for the old conversation is dropped. Allowed while a turn is pending. No server call. */
export const startOver = (s: ChatState): ChatState => ({ ...emptyState(), rev: s.rev + 1 });

/** A typed or spoken turn. null for empty text, and null while another turn is pending (a second send would be built from the old
 *  server state and silently drop the pending one): the screen disables sending, this keeps the rule even if the wiring misses.
 *  The new state is pending: skeleton, no cards, local chips shown dimmed. */
export function submitText(s: ChatState, text: string): { state: ChatState; body: ChatBody } | null {
  if (s.pending) return null;
  const t = text.replace(/\s+/g, " ").trim().slice(0, MAX_TEXT);
  if (!t) return null;
  const body = bodyFor(s, s.rev + 1, { text: t });
  return { state: { ...s, rev: body.rev, pending: { body }, error: null, failed: null }, body };
}

/** A chip tap: `{add: ChipData}` (a refine chip) or `{remove: id}` (a chip's x, a note action). Pending too: no cards until it answers.
 *  null while another turn is pending, or for an add that is not a known native chip. */
export function tapChip(s: ChatState, op: Op): { state: ChatState; body: ChatBody } | null {
  if (s.pending) return null;
  if ("add" in op && !safeChipFor(op.add)) return null;
  const body = bodyFor(s, s.rev + 1, { op });
  return { state: { ...s, rev: body.rev, pending: { body }, error: null, failed: null }, body };
}

/** The server's answer. Applied only when it is for the latest revision issued and a request is pending; anything older is dropped.
 *  Everything is replaced wholesale from the server (chips, cards, notes, mode, sources). */
export function applyResponse(s: ChatState, r: ChatResponse): ChatState {
  if (!s.pending || r.rev !== s.rev) return s;
  const b = s.pending.body;
  let turns = s.turns;
  if (b.text) turns = [...turns, { user: b.text, reply: r.reply }].slice(-MAX_TURNS);
  else if (turns.length) turns = [...turns.slice(0, -1), { ...turns[turns.length - 1], reply: r.reply }]; // an op restates the current reading
  let removed = s.removed;
  if (b.op && "remove" in b.op && !removed.includes(b.op.remove)) removed = [...removed, b.op.remove];
  if (b.op && "add" in b.op) {
    const id = safeChipFor(b.op.add)?.id;
    removed = removed.filter((x) => x !== id);
  }
  const have = new Set(r.chips.map((c) => c.id));
  removed = removed.filter((x) => !have.has(x)); // a chip the server holds is not "removed"
  return { turns, chips: r.chips, removed, rev: s.rev, pending: null, reply: r.reply, cards: r.cards, notes: r.notes, mode: r.mode, basic: basicLabel(r, b), sources: r.sources, matched: r.matched, error: null, failed: null };
}

/** A failed request for revision `rev` (ignored when a newer one has been issued).
 *  404 = the feature is off (the one box shows the plain results instead); 401 / 403 = signed out; 400 / 413 = too big: the oldest turns are trimmed and the request is
 *  re-sent once (`resend`), then a plain "try a shorter message"; anything else (0, 5xx, 429...) = unreachable: Retry re-sends the same
 *  body, and the cards of the superseded reading stay hidden until a newer response is applied. */
export function applyError(s: ChatState, rev: number, status: number): { state: ChatState; resend: ChatBody | null } {
  if (!s.pending || rev !== s.rev) return { state: s, resend: null };
  if (status === 404) return { state: { ...s, pending: null, error: "off" }, resend: null };
  if (status === 401 || status === 403) return { state: { ...s, pending: null, error: "signedOut" }, resend: null };
  if (status === 400 || status === 413) {
    const b = s.pending.body;
    if (!s.pending.trimmed && b.turns.length > 0) {
      const body: ChatBody = { ...b, turns: b.turns.slice(b.turns.length - Math.floor(b.turns.length / 2)) };
      return { state: { ...s, pending: { body, trimmed: true } }, resend: body };
    }
    return { state: { ...s, pending: null, error: "tooLong", cards: [], notes: [], matched: 0 }, resend: null };
  }
  // the superseded reading's cards, notes and count are not shown any more: only a newer response brings cards back
  return { state: { ...s, pending: null, error: "unreachable", failed: s.pending.body, cards: [], notes: [], matched: 0 }, resend: null };
}

/** Retry after "unreachable": the same body again (same rev: nothing newer was issued). */
export function retry(s: ChatState): { state: ChatState; body: ChatBody } | null {
  if (s.error !== "unreachable" || !s.failed) return null;
  return { state: { ...s, pending: { body: s.failed }, error: null, failed: null }, body: s.failed };
}

const strip = ({ pos: _p, src: _s, ...c }: Chip): Chip => c;

/** The chips to draw: the server's, and while a turn is pending the local reading on top (`provisional`: dimmed, "reading..."). */
export function displayChips(s: ChatState, now = new Date()): (ChatChip & { provisional?: true })[] {
  const cur = s.chips;
  const b = s.pending?.body;
  if (!b) return cur;
  if (b.text) {
    const read = parseAsk(b.text, now).chips.filter((r) => !cur.some((c) => c.id === r.id));
    return [...cur, ...read.map((r) => ({ ...strip(r), provisional: true as const }))];
  }
  if (b.op && "add" in b.op) {
    const c = safeChipFor(b.op.add);
    if (!c) return cur;
    return cur.some((x) => x.id === c.id) ? cur : [...cur, { ...c, provisional: true as const }];
  }
  if (b.op && "remove" in b.op) {
    const id = b.op.remove;
    return cur.filter((c) => c.id !== id);
  }
  return cur;
}

// ---------- response parsing ----------

const isObj = (x: unknown): x is Record<string, unknown> => !!x && typeof x === "object" && !Array.isArray(x);
const isOp = (x: unknown): x is Op => isObj(x) && ((typeof x.remove === "string" && x.remove.length > 0) || (isObj(x.add) && safeChipFor(x.add) !== null));

/** Wire JSON -> ChatResponse, or null when it is not a chat answer. Malformed chips / cards / notes are dropped, not fatal. */
export function parseResponse(d: unknown): ChatResponse | null {
  if (!isObj(d) || d.v !== 1 || typeof d.rev !== "number" || (d.mode !== "llm" && d.mode !== "fallback") || typeof d.reply !== "string") return null;
  const chips = (Array.isArray(d.chips) ? d.chips : []).filter((c): c is ChatChip => isObj(c) && typeof c.id === "string" && typeof c.label === "string" && isObj(c.data) && typeof c.data.t === "string");
  const cards = (Array.isArray(d.cards) ? d.cards : []).filter(
    (c): c is ChatCard =>
      isObj(c) && (c.kind === "film" || c.kind === "series") && ((c.source === "library" && typeof c.id === "string") || (c.source === "iptv" && typeof c.iptvId === "string" && c.iptvId !== "")),
  );
  const notes: ChatNote[] = (Array.isArray(d.notes) ? d.notes : [])
    .filter((n): n is Record<string, unknown> => isObj(n) && typeof n.text === "string" && typeof n.code === "string")
    .map((n) => {
      const actions = (Array.isArray(n.actions) ? n.actions : []).filter((a): a is { label: string; op: Op } => isObj(a) && typeof a.label === "string" && isOp(a.op)).slice(0, 3);
      return { text: n.text as string, code: n.code as string, ...(actions.length ? { actions } : {}) };
    });
  return {
    v: 1,
    rev: d.rev,
    mode: d.mode,
    reply: d.reply,
    chips,
    cards,
    notes,
    sources: (Array.isArray(d.sources) ? d.sources : []).filter((x): x is string => typeof x === "string"),
    matched: typeof d.matched === "number" ? d.matched : cards.length,
    partial: d.partial === true,
    ...(typeof d.basic === "boolean" ? { basic: d.basic } : {}),
    ...(d.turn === "text" || d.turn === "op" ? { turn: d.turn } : {}),
  };
}

// ---------- sending ----------

export type Call = (method: "POST", path: string, body: unknown, opts: { signal?: AbortSignal }) => Promise<{ status: number; data: unknown }>;
export type Sent = { kind: "ok"; resp: ChatResponse } | { kind: "error"; status: number } | { kind: "aborted" };

export const SEND_TIMEOUT_MS = 12_000;

/** One in-flight turn at a time: sending a newer request aborts the previous one (its answer is never applied). A request that does
 *  not answer within the timeout (12 s) ends as `{kind:"error", status:0}` (unreachable + Retry), so a hung connection can never leave
 *  the screen waiting: every call resolves to ok, error or aborted (aborted only when superseded). A 200 for another rev than the
 *  body that was sent is an error too (never a silent drop that would leave the turn pending). */
export class ChatSender {
  private cur: { ctl: AbortController; superseded: boolean } | null = null;
  private call: Call;
  private timeoutMs: number;
  constructor(call: Call, timeoutMs = SEND_TIMEOUT_MS) {
    this.call = call;
    this.timeoutMs = timeoutMs;
  }

  async send(body: ChatBody): Promise<Sent> {
    if (this.cur) {
      this.cur.superseded = true;
      this.cur.ctl.abort();
    }
    const me = (this.cur = { ctl: new AbortController(), superseded: false });
    let timer: ReturnType<typeof setTimeout> | undefined;
    const dead = new Promise<"timeout">((res) => {
      timer = setTimeout(() => {
        me.ctl.abort(); // a setTimeout, not AbortSignal.timeout / .any: older WebViews lack those
        res("timeout");
      }, this.timeoutMs);
    });
    try {
      let got: { status: number; data: unknown } | "timeout";
      try {
        got = await Promise.race([this.call("POST", "v1/ask/chat", body, { signal: me.ctl.signal }), dead]);
      } catch {
        got = { status: 0, data: null };
      }
      if (me.superseded) return { kind: "aborted" };
      if (got === "timeout") return { kind: "error", status: 0 };
      if (got.status === 200) {
        const resp = parseResponse(got.data);
        return resp && resp.rev === body.rev ? { kind: "ok", resp } : { kind: "error", status: 500 };
      }
      return { kind: "error", status: got.status };
    } finally {
      if (timer) clearTimeout(timer);
      if (this.cur === me) this.cur = null;
    }
  }

  /** Whatever is in flight is no longer wanted (Start over, leaving the screen): it ends as "aborted". */
  cancel(): void {
    if (this.cur) {
      this.cur.superseded = true;
      this.cur.ctl.abort();
      this.cur = null;
    }
  }

  /** Fire and forget: tells the server a chat is starting so it can warm up. Never throws. */
  warm(): void {
    void this.call("POST", "v1/ask/chat/warm", {}, {}).catch(() => {});
  }
}

// ---------- cards ----------

export const normId = (id: string) => id.toLowerCase().replace(/-/g, "");

/** The server's cards in the server's order. A library card carries its fetched Item; an id the fetch dropped is a gap (no tile), never a
 *  substitute. A TV card has no Item: it is drawn from its own fields and is never dropped by the fetch. */
export function alignCards(cards: ChatCard[], items: Item[]): { card: ChatCard; item?: Item }[] {
  const by = new Map(items.map((i) => [normId(i.Id), i]));
  const out: { card: ChatCard; item?: Item }[] = [];
  for (const card of cards) {
    if (isIptvCard(card)) {
      out.push({ card });
      continue;
    }
    const item = by.get(normId(card.id));
    if (item) out.push({ card, item });
  }
  return out;
}

/** "Narrow it down": t5k's refine chips over the fetched titles and their people. Native chips only (server chips are opaque to it). */
export function refineChat(items: Item[], people: Item[], chips: ChatChip[]): Chip[] {
  return refineOptions(items, people, chips.filter((c): c is NativeChip => !isServerChip(c)));
}

// ---------- the Claude notice (once per device) ----------

export const NOTICE_TEXT = "What you type is sent to Claude (Anthropic) to understand it.";
export const NOTICE_KEY = "jt.askchat.notice.v1";
export interface Store {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
}
export const noticeSeen = (st: Store): boolean => {
  try {
    return st.getItem(NOTICE_KEY) === "1";
  } catch {
    return false;
  }
};
export const dismissNotice = (st: Store): void => {
  try {
    st.setItem(NOTICE_KEY, "1");
  } catch {
    /* storage blocked: the notice shows again next time */
  }
};
