import { useEffect, useState } from "react";

/**
 * Phone layout: narrow screens, plus phones turned landscape (wide but short, touch). Keep in step with the
 * `@media` block at the end of styles.css; desktop never matches.
 */
export const PHONE_QUERY = "(max-width: 760px), (hover: none) and (pointer: coarse) and (max-height: 520px)";

const mq = typeof window !== "undefined" ? window.matchMedia(PHONE_QUERY) : null;

export const isPhone = () => !!mq?.matches;

export function useIsPhone(): boolean {
  const [phone, setPhone] = useState(isPhone);
  useEffect(() => {
    if (!mq) return;
    const on = () => setPhone(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return phone;
}
