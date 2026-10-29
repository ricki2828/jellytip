// Follow a person (POST/DELETE /v1/me/follow): followed people get priority in person rows and the spotlight.
// Hidden when the server has no personalisation.
import { useEffect, useState } from "react";
import { followed, setFollow, usePrefs } from "../api/me";
import { flashNote } from "./TasteButtons";

export function FollowButton({ personId, name }: { personId: string; name: string }) {
  const { available } = usePrefs();
  const [on, setOn] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!available) return;
    let alive = true;
    followed().then((s) => alive && setOn(s ? s.has(personId) : null));
    return () => {
      alive = false;
    };
  }, [available, personId]);
  if (!available || on === null) return null;
  return (
    <button
      className={`btn small${on ? " on" : ""}`}
      data-nav
      aria-pressed={on}
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        const e = await setFollow(personId, !on);
        setBusy(false);
        if (e) flashNote(e);
        else {
          setOn(!on);
          flashNote(on ? `Unfollowed ${name}` : `Following ${name}: more of their films in your rows`);
        }
      }}
    >
      {on ? "Following ✓" : "Follow"}
    </button>
  );
}
