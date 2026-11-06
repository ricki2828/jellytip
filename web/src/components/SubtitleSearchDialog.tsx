// "Find subtitles" for one Jellyfin item (movie / episode), from Details and from the player's track menu.
// Keyboard / remote: arrows (and Tab) stay inside the dialog, Escape / Back close it (or cancel a running search).
import { useEffect, useReducer, useRef, useState, type KeyboardEvent } from "react";
import "./SubtitleSearchDialog.css";
import { currentStreams, downloadSubtitle, refreshAfterDownload, searchSubtitles } from "../api/subtitles";
import type { Item } from "../api/jellyfin";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { nextFocus, tabTarget } from "../lib/trackMenu.ts";
import { NONE_FOUND, SEARCH_TIMEOUT_MS, addedMessage, classifyError, defaultSearchLanguage, errorMessage, initialPhase, languageChoices, languageName, reduce, resultFlags, resultMeta, type RemoteSubtitle } from "../lib/remoteSubtitles.ts";

export type SubtitleSearchDialogProps = {
  itemId: string;
  title: string;
  /** MediaSource whose streams are watched for the new track (the playing one); default: the first. */
  sourceId?: string;
  subtitleLangs?: readonly string[];
  where: "page" | "player";
  onClose: () => void;
  /** a new track is listed: the refreshed item (so the caller can update its streams) */
  onAdded?: (item: Item) => void;
};

export function SubtitleSearchDialog({ itemId, title, sourceId, subtitleLangs, where, onClose, onAdded }: SubtitleSearchDialogProps) {
  const [state, dispatch] = useReducer(reduce, initialPhase);
  const [language, setLanguage] = useState(() => defaultSearchLanguage(subtitleLangs));
  const box = useRef<HTMLDivElement>(null);
  const ctl = useRef<AbortController | null>(null);
  const opener = useRef<HTMLElement | null>(document.activeElement as HTMLElement | null);
  const alive = useRef(true);
  const seqs = useRef(0);
  const [elapsed, setElapsed] = useState(0);
  const busy = state.phase === "searching" || state.phase === "downloading";

  const search = (lang: string) => {
    if (state.phase === "downloading") return;
    ctl.current?.abort();
    const c = new AbortController();
    ctl.current = c;
    setLanguage(lang);
    const seq = ++seqs.current;
    dispatch({ type: "search", seq, language: lang });
    searchSubtitles(itemId, lang, c.signal).then(
      (results) => alive.current && dispatch({ type: "found", seq, results }),
      (e) => alive.current && dispatch({ type: "failed", seq, error: classifyError(e) }),
    );
  };

  const download = async (picked: RemoteSubtitle) => {
    if (state.phase === "downloading") return;
    const seq = ++seqs.current;
    dispatch({ type: "download", seq, picked });
    try {
      const before = await currentStreams(itemId, sourceId).catch(() => []);
      await downloadSubtitle(itemId, picked.id);
      const r = await refreshAfterDownload(itemId, before, sourceId);
      if (!alive.current) {
        if (r.listed && r.item) onAdded?.(r.item);
        return;
      }
      if (r.listed && r.item) onAdded?.(r.item);
      dispatch({ type: "added", seq, listed: r.listed });
    } catch (e) {
      if (alive.current) dispatch({ type: "failed", seq, error: classifyError(e) });
    }
  };

  // Open: search straight away in the default language, like the TV
  useEffect(() => {
    search(language);
    return () => {
      alive.current = false;
      ctl.current?.abort();
      opener.current?.focus?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Escape / Back: cancel a search first, otherwise close (a running download keeps going on the server; closing is allowed)
  useEscapeClose(() => {
    if (state.phase === "searching") {
      ctl.current?.abort();
      dispatch({ type: "cancel" });
    } else onClose();
  });

  useEffect(() => {
    if (state.phase !== "searching") return;
    setElapsed(0);
    const t0 = Date.now();
    const t = setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 1000);
    return () => clearInterval(t);
  }, [state.phase, state.seq]);

  // Focus follows the state: the first result, else the primary button
  useEffect(() => {
    const root = box.current;
    if (!root) return;
    const want = state.phase === "results" ? root.querySelector<HTMLElement>("[data-result]") : root.querySelector<HTMLElement>("[data-autofocus]");
    (want ?? root).focus();
  }, [state.phase, state.seq]);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const els = Array.from(box.current?.querySelectorAll<HTMLElement>("[data-nav]") ?? []).filter((el) => !(el as HTMLButtonElement).disabled);
    const cur = els.indexOf(document.activeElement as HTMLElement);
    if (e.key === "Tab") {
      e.preventDefault();
      els[tabTarget(els.length, cur, e.shiftKey)]?.focus();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp" || e.key === "ArrowLeft" || e.key === "ArrowRight") {
      // Keep the arrows inside the dialog: the page / player behind must not get them (or its spatial nav)
      e.preventDefault();
      e.stopPropagation();
      const k = e.key === "ArrowDown" || e.key === "ArrowRight" ? "ArrowDown" : "ArrowUp";
      const dis = els.map(() => false);
      els[nextFocus(dis, cur < 0 ? 0 : cur, k)]?.focus();
    } else if (e.key === " " || e.key === "Enter" || e.key === "k") {
      e.stopPropagation(); // the player's play/pause must not run behind the dialog
    }
  };

  const results = "results" in state ? state.results : [];
  const showList = state.phase === "results" || state.phase === "downloading" || state.phase === "added" || (state.phase === "error" && state.action === "download");

  let status: string | null = null;
  if (state.phase === "searching") status = `Searching subtitle providers for ${languageName(state.language)}. This can take up to a minute.`;
  else if (state.phase === "downloading") status = `Downloading "${state.picked.name}"...`;
  else if (state.phase === "added") status = addedMessage(state.listed, where, state.picked.name);
  else if (state.phase === "empty") status = NONE_FOUND;
  else if (state.phase === "error") status = errorMessage(state.error, state.action);
  else if (state.phase === "idle" && state.note) status = state.note;

  return (
    <div className="subs-back" onClick={onClose}>
      <div ref={box} className="subs-dialog" role="dialog" aria-modal="true" aria-label={`Find subtitles for ${title}`} tabIndex={-1} onClick={(e) => e.stopPropagation()} onKeyDown={onKeyDown}>
        <SheetClose onClose={onClose} />
        <h2>Find subtitles</h2>
        <p className="subs-title">{title}</p>
        <div role="radiogroup" aria-label="Subtitle language" className="subs-langs">
          {languageChoices(subtitleLangs, language).map((l) => (
            <button key={l} type="button" role="radio" aria-checked={l === language} data-nav className={`chip${l === language ? " on" : ""}`} disabled={state.phase === "downloading"} onClick={() => l !== language && search(l)}>
              {languageName(l)}
            </button>
          ))}
        </div>
        <p role="status" aria-live="polite" className={`subs-status${state.phase === "error" ? " err" : ""}`}>
          {status}
          {state.phase === "searching" && <span aria-hidden="true" className="subs-elapsed"> {elapsed} s of {SEARCH_TIMEOUT_MS / 1000} s</span>}
        </p>
        {showList && (
          <ul className="subs-list" aria-label="Subtitle results">
            {results.map((r) => {
              const flags = resultFlags(r);
              return (
                <li key={r.id}>
                  <button type="button" data-nav data-result className="subs-result" disabled={busy} onClick={() => void download(r)} aria-label={`Download ${r.name}. ${[resultMeta(r), ...flags].join(". ")}`}>
                    <span className="subs-name">{r.name}</span>
                    <span className="subs-meta">{resultMeta(r)}</span>
                    {flags.length > 0 && <span className="subs-flags">{flags.join(" · ")}</span>}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        <div className="subs-actions">
          {state.phase === "searching" && (
            <button type="button" className="btn" data-nav data-autofocus onClick={() => { ctl.current?.abort(); dispatch({ type: "cancel" }); }}>
              Cancel search
            </button>
          )}
          {(state.phase === "error" || state.phase === "empty" || state.phase === "idle") && (
            <button type="button" className="btn" data-nav data-autofocus onClick={() => search(language)}>
              {state.phase === "error" && state.action === "search" ? "Try again" : "Search again"}
            </button>
          )}
          <button type="button" className="btn" data-nav data-autofocus={state.phase === "added" || undefined} onClick={onClose}>
            {state.phase === "added" ? "Done" : "Close"}
          </button>
        </div>
      </div>
    </div>
  );
}
