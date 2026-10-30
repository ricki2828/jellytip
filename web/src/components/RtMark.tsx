// Rotten Tomatoes mark: a red tomato (fresh) or a green splat (rotten).
import type { Mark } from "../lib/attribution";

export function RtMark({ mark, size = 14 }: { mark: Mark | null; size?: number }) {
  if (!mark) return null;
  const fresh = mark === "rt_fresh";
  return (
    <svg className="rt-mark" width={size} height={size} viewBox="0 0 24 24" role="img" aria-label={fresh ? "Fresh" : "Rotten"} style={{ verticalAlign: "-2px" }}>
      {fresh ? (
        <>
          <circle cx="12" cy="14" r="9" fill="#e5322d" />
          <path d="M12 6 8 2l4 2 4-2-4 4Zm0 0 3 3-3-1-3 1 3-3Z" fill="#2e9b3f" />
        </>
      ) : (
        <path
          d="M12 2c1.6 0 2 2.6 3.4 3.2C17 6 20 5 20.6 7c.6 2-1.8 3.2-1.4 4.8.4 1.6 3 2.4 2.2 4.4-.8 2-3.4 1-4.6 2.2-1.2 1.2-.6 3.8-2.6 4.4-2 .6-3-1.8-4.6-2-1.6-.2-4 1.6-5.4 0-1.4-1.6.8-3.2.6-4.8C4 14 1.6 13 2 11c.4-2 3.2-1.8 4.2-3C7.2 6.8 7 4.2 9 3.4 10.4 2.8 10.6 2 12 2Z"
          fill="#6bbf3a"
        />
      )}
    </svg>
  );
}
