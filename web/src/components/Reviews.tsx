// Reviews, as on the TV (JellytipReviews.kt): a Details row of short pull-quote cards, Ricki's own first (warm accent),
// then the most-liked; a card opens the full text. Plus the home hero's one-line Letterboxd pull-quote.
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { Item } from "../api/jellyfin";
import { reviewQuote, reviews as getReviews, type Quote, type Review, type Reviews } from "../api/companion";
import "./Reviews.css";

const QUOTE_MAX = 120;

function stars(r: number): string {
  const whole = Math.max(0, Math.min(5, Math.floor(r)));
  return "★".repeat(whole) + (r - whole >= 0.25 ? "½" : "");
}

function likes(n: number): string {
  if (n >= 10_000) return `${Math.floor(n / 1000)}k`;
  if (n >= 1_000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

const reviewable = (item: Item) => item.Type === "Movie" || item.Type === "Series";

export function ReviewsRow({ item }: { item: Item }) {
  const [data, setData] = useState<Reviews | null>(null);
  const [open, setOpen] = useState<Review | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    setData(null);
    setOpen(null);
    if (!reviewable(item)) return;
    const ctl = new AbortController();
    getReviews(item.Id, { priority: "high", signal: ctl.signal }).then((r) => !ctl.signal.aborted && setData(r));
    return () => ctl.abort();
  }, [item.Id, item.Type]);

  if (!data || data.reviews.length === 0) return null;
  const label = data.source === "tmdb" ? "TMDB" : data.source === "letterboxd" ? "Letterboxd" : null;

  return (
    <section className="row reviews">
      <h2 className="row-title">
        Reviews{label && <span className="count">· {label}</span>}
      </h2>
      <div className="row-scroll">
        {data.reviews.map((r) => {
          const body = r.spoiler ? "Contains spoilers · select to read" : r.excerpt || (r.own ? "Rated, no review" : "");
          return (
            <button
              key={r.id}
              className={`review-card${r.own ? " own" : ""}`}
              data-nav
              onClick={(e) => {
                if (!r.text) return;
                opener.current = e.currentTarget;
                setOpen(r);
              }}
            >
              <span className="review-head">
                <span className="review-author">{r.own ? `${r.author} · you` : r.author}</span>
                {r.rating != null && <span className="review-stars">{stars(r.rating)}</span>}
              </span>
              <span className={`review-body${r.spoiler || !r.excerpt ? " quiet" : ""}`}>{body}</span>
              {r.likes ? <span className="review-likes">♥ {likes(r.likes)}</span> : null}
            </button>
          );
        })}
      </div>
      {open && (
        <ReviewModal
          review={open}
          onClose={() => {
            setOpen(null);
            opener.current?.focus({ preventScroll: true });
          }}
        />
      )}
    </section>
  );
}

function ReviewModal({ review, onClose }: { review: Review; onClose: () => void }) {
  const textRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  // Keys stay inside the modal (document runs before the app's window-level spatial nav and Esc = back):
  // Up/Down scroll the text, Esc/Backspace close.
  useEffect(() => {
    closeRef.current?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        onClose();
      } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        textRef.current?.scrollBy({ top: e.key === "ArrowDown" ? 90 : -90, behavior: "smooth" });
      } else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        e.preventDefault();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const meta = [
    review.rating != null ? stars(review.rating) : null,
    review.likes ? `♥ ${likes(review.likes)}` : null,
    review.source === "tmdb" ? "TMDB" : "Letterboxd",
  ]
    .filter(Boolean)
    .join("   ");

  // Portalled to <body>: inside .details it would sit in that stacking context, under the nav bar and phone tab bar
  return createPortal(
    <div className="review-modal" role="dialog" aria-modal="true" aria-label={`Review by ${review.author}`} onClick={onClose}>
      <div className={`review-sheet${review.own ? " own" : ""}`} onClick={(e) => e.stopPropagation()}>
        <div className="review-sheet-head">
          <div>
            <div className="review-sheet-author">{review.own ? `${review.author} · your review` : review.author}</div>
            <div className="review-sheet-meta">{meta}</div>
          </div>
          <button ref={closeRef} className="btn" data-nav onClick={onClose}>
            Close
          </button>
        </div>
        <div ref={textRef} className="review-sheet-text">
          {review.text.split(/\n{2,}/).map((p, i) => (
            <p key={i}>{p}</p>
          ))}
        </div>
      </div>
    </div>,
    document.body,
  );
}

/** Home hero pull-quote: small italic line, only when the companion has a short Letterboxd quote. */
export function HeroQuote({ item }: { item: Item }) {
  const [quote, setQuote] = useState<Quote | null>(null);
  useEffect(() => {
    setQuote(null);
    if (!reviewable(item)) return;
    const ctl = new AbortController();
    reviewQuote(item.Id, { priority: "high", signal: ctl.signal }).then((q) => !ctl.signal.aborted && setQuote(q));
    return () => ctl.abort();
  }, [item.Id, item.Type]);
  if (!quote || quote.text.length > QUOTE_MAX) return null;
  return (
    <p className="hero-quote">
      “{quote.text}” <span className="hero-quote-by">— {quote.author} on Letterboxd</span>
    </p>
  );
}
