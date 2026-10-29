// Admin "Get it" for a title not in the library (closet picks, collection parts, Discover): a dry run first shows what
// would happen (size estimate, free space, or why it's refused), then POST /v1/request. Series choose which seasons.
// With an overview / poster it doubles as the Discover details sheet.
import { useEffect, useRef, useState } from "react";
import { requestTitle, type RequestResult, type SeasonChoice } from "../api/companion";
import { go } from "../lib/router";
import "./GetItSheet.css";

export interface GetItPick {
  title: string;
  year?: number | null;
  tmdbId?: number | null;
  /** Default movie. */
  mediaType?: "movie" | "tv";
  posterUrl?: string | null;
  overview?: string | null;
  rating?: number | null;
  /** Library / request state from the server (Discover): none | requested | downloading | in_library. */
  state?: string | null;
  progress?: number | null;
  /** In the library already: open it instead. */
  itemId?: string | null;
}

const SEASONS: [SeasonChoice, string][] = [
  ["first", "First season"],
  ["latest", "Latest season"],
  ["all", "All seasons"],
];

type Answer = { code: number; body: RequestResult };

const gb = (n: number) => (n >= 100 ? Math.round(n) : Math.round(n * 10) / 10);

/** "≈ 34 GB · 512 GB free after", or the server's reason when it won't. */
export function dryRunText(a: Answer | null): string {
  if (!a) return "Checking…";
  const b = a.body;
  if (a.code === 200 && b.status === "added") {
    const parts = [b.label ?? null, b.estimateGb != null ? `≈ ${gb(b.estimateGb)} GB` : "Size unknown", b.freeGbAfter != null ? `${gb(b.freeGbAfter)} GB free after` : null];
    return parts.filter(Boolean).join(" · ");
  }
  if (b.status === "exists") return b.reason ?? "Already in the library or requested.";
  return b.reason ?? b.error ?? (a.code ? `Server said ${a.code}` : "Server unreachable");
}

export function stateText(state?: string | null, progress?: number | null): string | null {
  switch (state) {
    case "in_library":
      return "In library";
    case "requested":
      return "Requested";
    case "downloading":
      return progress != null ? `Downloading ${progress}%` : "Downloading";
    default:
      return null;
  }
}

export function GetItSheet({ pick, onClose, onRequested }: { pick: GetItPick; onClose: () => void; onRequested?: () => void }) {
  const tv = pick.mediaType === "tv";
  const [seasons, setSeasons] = useState<SeasonChoice>("first");
  const [check, setCheck] = useState<Answer | null>(null);
  const [done, setDone] = useState<Answer | null>(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);
  const box = useRef<HTMLDivElement>(null);

  // Keyboard / remote: focus moves into the sheet, and back to the tile that opened it when it closes
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    if (!matchMedia("(hover: none) and (pointer: coarse)").matches)
      box.current?.querySelector<HTMLElement>("[data-autofocus]:not([disabled]), [data-nav]:not([disabled])")?.focus();
    return () => opener?.focus?.({ preventScroll: true });
  }, []);
  // A film in the library or on its way is done; a series can still get more seasons (the server extends it, or says
  // "exists" when every chosen season is already there)
  const have = !tv && (pick.state === "in_library" || pick.state === "requested" || pick.state === "downloading");

  useEffect(() => {
    if (!pick.tmdbId || have) return;
    const n = ++seq.current;
    setCheck(null);
    requestTitle(pick.tmdbId, tv ? "tv" : "movie", true, tv ? seasons : undefined).then((a) => n === seq.current && setCheck(a));
  }, [pick.tmdbId, tv, seasons, have]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const can = !have && check?.code === 200 && check.body.status === "added";
  const status = stateText(pick.state, pick.progress);
  return (
    <div className="getit-scrim" onClick={onClose}>
      <div ref={box} className={`getit${pick.overview || pick.posterUrl ? " rich" : ""}`} role="dialog" aria-modal="true" aria-label={`Get ${pick.title}`} onClick={(e) => e.stopPropagation()}>
        {pick.posterUrl && <img className="getit-poster" src={pick.posterUrl} alt="" />}
        <div className="getit-body">
          <strong className="getit-title">
            {pick.title}
            {pick.year ? ` (${pick.year})` : ""}
          </strong>
          <span className="getit-meta">
            {[tv ? "Series" : "Film", pick.rating ? `TMDB ${pick.rating.toFixed(1)}` : null, status ?? "Not in the library yet"].filter(Boolean).join(" · ")}
          </span>
          {pick.overview && <p className="getit-overview">{pick.overview}</p>}
          {done ? (
            <>
              <span className="getit-note">{done.body.status === "added" ? "Added. It's searching now." : dryRunText(done)}</span>
              <div className="buttons">
                <button className="btn" data-nav data-autofocus onClick={onClose}>
                  Close
                </button>
              </div>
            </>
          ) : have ? (
            <div className="buttons">
              {pick.itemId && (
                <button className="btn primary" data-nav data-autofocus onClick={() => go(`/item/${pick.itemId}`)}>
                  Open
                </button>
              )}
              <button className="btn" data-nav data-autofocus={!pick.itemId || undefined} onClick={onClose}>
                Close
              </button>
            </div>
          ) : (
            <>
              {tv && (
                <div className="getit-seasons" role="radiogroup" aria-label="Seasons">
                  {SEASONS.map(([k, label]) => (
                    <button key={k} className={`chip${seasons === k ? " on" : ""}`} data-nav role="radio" aria-checked={seasons === k} onClick={() => setSeasons(k)}>
                      {label}
                    </button>
                  ))}
                </div>
              )}
              <span className={`getit-note${check && !can ? " warn" : ""}`}>{dryRunText(check)}</span>
              <div className="buttons">
                {pick.itemId && (
                  <button className="btn" data-nav onClick={() => go(`/item/${pick.itemId}`)}>
                    Open
                  </button>
                )}
                <button
                  className="btn primary"
                  data-nav
                  data-autofocus
                  disabled={!can || busy}
                  onClick={async () => {
                    setBusy(true);
                    const a = await requestTitle(pick.tmdbId!, tv ? "tv" : "movie", false, tv ? seasons : undefined);
                    setDone(a);
                    setBusy(false);
                    if (a.body.status === "added") onRequested?.();
                  }}
                >
                  {busy ? "Adding…" : "Get it"}
                </button>
                <button className="btn" data-nav onClick={onClose}>
                  Close
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
