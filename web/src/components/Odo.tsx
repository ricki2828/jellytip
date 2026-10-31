// Odometer number swap: only the digits that changed roll in (translate + fade); unchanged digits keep their node and do not animate.
import { useEffect, useRef } from "react";

export function Odo({ value }: { value: string | number | null | undefined }) {
  const s = value == null ? "" : String(value);
  const prev = useRef<string | null>(null);
  const before = prev.current;
  useEffect(() => {
    prev.current = s;
  });
  return (
    <span className="odo" aria-label={s}>
      {s.split("").map((ch, i) => (
        <span key={`${i}:${ch}`} className={before != null && before[i] !== ch ? "odo-d" : undefined} aria-hidden>
          {ch}
        </span>
      ))}
    </span>
  );
}
