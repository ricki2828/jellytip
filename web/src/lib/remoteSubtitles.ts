// Remote subtitle search + download for one Jellyfin item (pure; tested in web/test/remoteSubtitles.test.ts).
// Mirrors the Android TV app (JellytipSubtitleSearch.kt, SubtitleSearchUtils.kt): Jellyfin's native
// RemoteSearch API, a 70 s search timeout (Jellyfin asks every provider in turn), who-may-search gating, and the
// same sort. No companion route is involved.

export const SEARCH_TIMEOUT_MS = 70_000;
export const DOWNLOAD_TIMEOUT_MS = 60_000;

// ---------- who may search (TV: policy.isAdministrator || policy.enableSubtitleManagement) ----------

export type SubtitleAccessSession = { isAdmin?: boolean; canManageSubtitles?: boolean } | null | undefined;

export const canManageSubtitles = (s: SubtitleAccessSession): boolean => !!s && (s.isAdmin === true || s.canManageSubtitles === true);

/** A session stored before the flag existed: the policy has to be read once (admins and known answers need no lookup). */
export const needsPolicyLookup = (s: SubtitleAccessSession): boolean => !!s && s.isAdmin !== true && s.canManageSubtitles === undefined;

// ---------- languages ----------

/** Pref codes are ISO 639-1 ("en"); Jellyfin's RemoteSearch takes ISO 639-2/B ("eng"). */
const THREE: Record<string, string> = { en: "eng", mi: "mao", fr: "fre", es: "spa", de: "ger", it: "ita", pt: "por", nl: "dut", sv: "swe", ja: "jpn", ko: "kor", zh: "chi", hi: "hin", ar: "ara", ru: "rus", pl: "pol", tr: "tur", da: "dan", no: "nor", fi: "fin", el: "gre", he: "heb" };
const NAMES: Record<string, string> = { eng: "English", mao: "Māori", fre: "French", spa: "Spanish", ger: "German", ita: "Italian", por: "Portuguese", dut: "Dutch", swe: "Swedish", jpn: "Japanese", kor: "Korean", chi: "Chinese", hin: "Hindi", ara: "Arabic", rus: "Russian", pol: "Polish", tur: "Turkish", dan: "Danish", nor: "Norwegian", fin: "Finnish", gre: "Greek", heb: "Hebrew" };
const ALT3: Record<string, string> = { fra: "fre", deu: "ger", nld: "dut", zho: "chi", mri: "mao", ell: "gre" };
const COMMON = ["eng", "spa", "fre", "ger", "ita", "por", "jpn", "kor", "chi"];

/** A pref code or 3-letter tag → the 3-letter code for the API; null when it is not a language code. */
export function toSearchLanguage(code: string | null | undefined): string | null {
  const c = (code ?? "").trim().toLowerCase();
  if (THREE[c]) return THREE[c];
  if (ALT3[c]) return ALT3[c];
  return /^[a-z]{3}$/.test(c) ? c : null;
}

export const languageName = (code: string) => NAMES[code] ?? code.toUpperCase();

/** TV: the first preferred subtitle language, else the device language; the web falls back to English. */
export function defaultSearchLanguage(subtitleLangs: readonly string[] | null | undefined): string {
  for (const c of subtitleLangs ?? []) {
    const l = toSearchLanguage(c);
    if (l) return l;
  }
  return "eng";
}

/** Language choices for the dialog: the person's own first (in order), then the common ones; always includes `selected`. */
export function languageChoices(subtitleLangs: readonly string[] | null | undefined, selected: string): string[] {
  const out: string[] = [];
  for (const c of [...(subtitleLangs ?? []).map(toSearchLanguage), selected, ...COMMON]) if (c && !out.includes(c)) out.push(c);
  return out;
}

// ---------- requests ----------

const BASE_ID = /^[0-9a-f]{32}$|^[0-9a-f-]{36}$/i;

/** Item ids are hex GUIDs; anything else is refused rather than put in a path. */
export function validItemId(id: string): boolean {
  return BASE_ID.test(id);
}

export function searchPath(itemId: string, language: string): string {
  const lang = toSearchLanguage(language);
  if (!validItemId(itemId) || !lang) throw new Error("bad subtitle search request");
  return `/Items/${encodeURIComponent(itemId)}/RemoteSearch/Subtitles/${encodeURIComponent(lang)}`;
}

/** Provider subtitle ids are opaque and can hold "/", "+", "=" or ":": always encoded. */
export function downloadPath(itemId: string, subtitleId: string): string {
  if (!validItemId(itemId) || !subtitleId) throw new Error("bad subtitle download request");
  return `/Items/${encodeURIComponent(itemId)}/RemoteSearch/Subtitles/${encodeURIComponent(subtitleId)}`;
}

// ---------- results ----------

export interface RemoteSubtitle {
  id: string;
  name: string;
  provider: string;
  format: string;
  author: string;
  language: string;
  rating: number | null;
  downloads: number | null;
  perfectMatch: boolean;
  hearingImpaired: boolean;
  forced: boolean;
  aiTranslated: boolean;
  machineTranslated: boolean;
}

const rec = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const str = (v: unknown) => (typeof v === "string" ? v.trim() : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
const flag = (v: unknown) => v === true;

/** Jellyfin RemoteSubtitleInfo[] → results. Entries with no id cannot be downloaded and are dropped; the rest are sorted. */
export function mapResults(raw: unknown): RemoteSubtitle[] {
  if (!Array.isArray(raw)) return [];
  const list: RemoteSubtitle[] = [];
  for (const x of raw) {
    const r = rec(x);
    const id = str(r.Id);
    if (!id) continue;
    list.push({
      id,
      name: str(r.Name) || str(r.ProviderName) || "Subtitle",
      provider: str(r.ProviderName),
      format: str(r.Format).toUpperCase(),
      author: str(r.Author),
      language: str(r.ThreeLetterISOLanguageName),
      rating: num(r.CommunityRating),
      downloads: num(r.DownloadCount),
      perfectMatch: flag(r.IsHashMatch),
      hearingImpaired: flag(r.HearingImpaired),
      forced: flag(r.Forced),
      aiTranslated: flag(r.AiTranslated),
      machineTranslated: flag(r.MachineTranslated),
    });
  }
  return sortResults(list);
}

/** TV order: hash (perfect) match, then community rating, then download count; stable for ties. */
export function sortResults(list: readonly RemoteSubtitle[]): RemoteSubtitle[] {
  return list
    .map((r, i) => ({ r, i }))
    .sort((a, b) => Number(b.r.perfectMatch) - Number(a.r.perfectMatch) || (b.r.rating ?? -1) - (a.r.rating ?? -1) || (b.r.downloads ?? -1) - (a.r.downloads ?? -1) || a.i - b.i)
    .map((x) => x.r);
}

/** One line of detail for a result: provider, format, rating, downloads, author. */
export function resultMeta(r: RemoteSubtitle): string {
  return [r.provider, r.format, r.rating != null ? `rating ${r.rating.toFixed(1)}` : "", r.downloads != null ? `${r.downloads.toLocaleString("en-US")} downloads` : "", r.author].filter(Boolean).join(" · ");
}

export function resultFlags(r: RemoteSubtitle): string[] {
  return [r.perfectMatch && "Perfect match", r.hearingImpaired && "Hearing impaired", r.forced && "Forced", r.aiTranslated && "AI translated", r.machineTranslated && "Machine translated"].filter((x): x is string => !!x);
}

// ---------- errors ----------

export type SubtitleErrorKind = "forbidden" | "notfound" | "timeout" | "cancelled" | "offline" | "server";

export class SubtitleError extends Error {
  kind: SubtitleErrorKind;
  status: number;
  constructor(kind: SubtitleErrorKind, status = 0) {
    super(kind);
    this.kind = kind;
    this.status = status;
  }
}

/** Anything thrown by a Jellyfin call → a kind. HttpError carries `status`; fetch network failures are TypeErrors. */
export function classifyError(e: unknown): SubtitleError {
  if (e instanceof SubtitleError) return e;
  const status = typeof (e as { status?: unknown })?.status === "number" ? (e as { status: number }).status : 0;
  if (status === 401 || status === 403) return new SubtitleError("forbidden", status);
  if (status === 404) return new SubtitleError("notfound", status);
  if (status > 0) return new SubtitleError("server", status);
  return new SubtitleError("offline");
}

export function errorMessage(e: SubtitleError, action: "search" | "download" = "search"): string {
  switch (e.kind) {
    case "forbidden":
      return "Your account can't manage subtitles. Ask an admin to turn on \"Allow subtitle management\" for you in Jellyfin.";
    case "notfound":
      return action === "search" ? "The server has no subtitle search for this item (no subtitle provider is set up, or the item is gone)." : "That subtitle is no longer available from the provider. Pick another.";
    case "timeout":
      return `The subtitle ${action} timed out after ${Math.round((action === "search" ? SEARCH_TIMEOUT_MS : DOWNLOAD_TIMEOUT_MS) / 1000)} s. Try again.`;
    case "cancelled":
      return "Cancelled.";
    case "offline":
      return `Couldn't reach the server for the subtitle ${action}. Check your connection and try again.`;
    default:
      return `Subtitle ${action} failed (server error${e.status ? ` ${e.status}` : ""}). Try again.`;
  }
}

export const NONE_FOUND = "No subtitles found. The server's subtitle providers returned nothing for this language.";

/** Runs `run` with an abort signal that fires on `ms` (-> "timeout") or when `outer` aborts (-> "cancelled"). */
export async function withDeadline<T>(run: (signal: AbortSignal) => Promise<T>, ms: number, outer?: AbortSignal): Promise<T> {
  const ctl = new AbortController();
  let why: "timeout" | "cancelled" | null = null;
  const abort = (w: "timeout" | "cancelled") => {
    if (!why) {
      why = w;
      ctl.abort();
    }
  };
  if (outer?.aborted) throw new SubtitleError("cancelled");
  const onOuter = () => abort("cancelled");
  outer?.addEventListener("abort", onOuter, { once: true });
  const timer = setTimeout(() => abort("timeout"), ms);
  try {
    return await run(ctl.signal);
  } catch (e) {
    if (why) throw new SubtitleError(why);
    throw classifyError(e);
  } finally {
    clearTimeout(timer);
    outer?.removeEventListener("abort", onOuter);
  }
}

// ---------- dialog state machine ----------
// Every search/download is started with a fresh, increasing `seq` (the caller allocates it); a late reply from a
// cancelled or replaced request carries an old one and is ignored.

export type Phase =
  | { phase: "idle"; seq: number; note?: string }
  | { phase: "searching"; seq: number; language: string }
  | { phase: "results"; seq: number; language: string; results: RemoteSubtitle[] }
  | { phase: "empty"; seq: number; language: string }
  | { phase: "downloading"; seq: number; language: string; results: RemoteSubtitle[]; picked: RemoteSubtitle }
  | { phase: "added"; seq: number; language: string; results: RemoteSubtitle[]; picked: RemoteSubtitle; listed: boolean }
  | { phase: "error"; seq: number; language: string; action: "search" | "download"; error: SubtitleError; results: RemoteSubtitle[] };

export type Event =
  | { type: "search"; seq: number; language: string }
  | { type: "found"; seq: number; results: RemoteSubtitle[] }
  | { type: "download"; seq: number; picked: RemoteSubtitle }
  | { type: "added"; seq: number; listed: boolean }
  | { type: "failed"; seq: number; error: SubtitleError }
  | { type: "cancel" };

export const initialPhase: Phase = { phase: "idle", seq: 0 };

export function reduce(s: Phase, e: Event): Phase {
  switch (e.type) {
    case "search":
      // A search may start from anywhere except mid-download (the server is already installing a file)
      if (s.phase === "downloading") return s;
      return { phase: "searching", seq: e.seq, language: e.language };
    case "found":
      if (s.phase !== "searching" || e.seq !== s.seq) return s;
      return e.results.length ? { phase: "results", seq: s.seq, language: s.language, results: e.results } : { phase: "empty", seq: s.seq, language: s.language };
    case "download":
      if (s.phase !== "results" && !(s.phase === "error" && s.action === "download" && s.results.length) && s.phase !== "added") return s;
      return { phase: "downloading", seq: e.seq, language: s.language, results: "results" in s ? s.results : [], picked: e.picked };
    case "added":
      if (s.phase !== "downloading" || e.seq !== s.seq) return s;
      return { phase: "added", seq: s.seq, language: s.language, results: s.results, picked: s.picked, listed: e.listed };
    case "failed":
      if (e.seq !== s.seq) return s;
      if (s.phase === "searching") return e.error.kind === "cancelled" ? { phase: "idle", seq: s.seq, note: "Search cancelled." } : { phase: "error", seq: s.seq, language: s.language, action: "search", error: e.error, results: [] };
      if (s.phase === "downloading") return { phase: "error", seq: s.seq, language: s.language, action: "download", error: e.error, results: s.results };
      return s;
    case "cancel":
      // Only a search can be cancelled; a download already running on the server cannot be taken back
      return s.phase === "searching" ? { phase: "idle", seq: s.seq, note: "Search cancelled." } : s;
  }
}

// ---------- after a download: wait for the item's streams to show the new track ----------

export interface StreamLike {
  Index: number;
  Type: string;
  Language?: string;
  Codec?: string;
  DisplayTitle?: string;
  Title?: string;
  Path?: string;
  IsExternal?: boolean;
}

const subs = <T extends StreamLike>(l: readonly T[]) => l.filter((s) => s.Type === "Subtitle");
/** Identity of a track independent of its index (indices shift when an external track is inserted). */
export const streamKey = (s: StreamLike) => [s.Path ?? "", s.IsExternal ? "x" : "e", s.Language ?? "", s.Codec ?? "", s.DisplayTitle ?? s.Title ?? ""].join("|");

export function addedSubtitle<T extends StreamLike>(before: readonly T[], after: readonly T[]): T | null {
  const old = new Set(subs(before).map(streamKey));
  const fresh = subs(after).filter((s) => !old.has(streamKey(s)));
  return fresh.find((s) => s.IsExternal) ?? fresh[fresh.length - 1] ?? null;
}

/** Jellyfin refreshes the item in the background after a download: ask again a few times, as the TV does (4 x 1.5 s). */
export async function waitForNewSubtitle<T extends StreamLike>(load: () => Promise<readonly T[]>, before: readonly T[], pause: (ms: number) => Promise<void>, attempts = 4, delayMs = 1500): Promise<{ streams: readonly T[]; added: T | null }> {
  let streams: readonly T[] = before;
  for (let i = 0; i < attempts; i++) {
    await pause(delayMs);
    try {
      streams = await load();
    } catch {
      continue; // a blip while the server refreshes; the next attempt may succeed
    }
    const added = addedSubtitle(before, streams);
    if (added) return { streams, added };
  }
  return { streams, added: null };
}

/** After the stream list changed, where did the active subtitle go? -1 = off (or the track vanished). */
export function remapActive(before: readonly StreamLike[], after: readonly StreamLike[], active: number): number {
  if (active < 0) return -1;
  const cur = before.find((s) => s.Type === "Subtitle" && s.Index === active);
  if (!cur) return -1;
  const key = streamKey(cur);
  return subs(after).find((s) => streamKey(s) === key)?.Index ?? -1;
}

export function addedMessage(listed: boolean, where: "page" | "player", name: string): string {
  if (listed) return where === "player" ? `Added "${name}". Pick it under Audio & subtitles.` : `Added "${name}". It is now in this title's subtitle list and will show in the player's Audio & subtitles menu.`;
  return `Downloaded "${name}", but the server hasn't listed it yet. It should appear shortly; ${where === "player" ? "restart playback to use it" : "reopen this page to check"}.`;
}
