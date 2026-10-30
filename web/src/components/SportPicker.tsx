// "Sports I follow" chips (tap = follow, again = mute, again = neutral) and the two tag toggles. Shared by Settings and
// the first-run "Make live sport yours" sheet.
import { savePrefs } from "../api/me";
import { MUTED_EVENT } from "../api/sportTaste";
import type { Prefs } from "../lib/prefs";
import { applySportState, hasTag, nextSportState, SPORTS, sportState, TAG_REPLAYS, TAG_WOMENS, withMutedTag } from "../lib/sportMute";

const MARK = { follow: "★ ", mute: "⊘ ", neutral: "" } as const;
const HINT = { follow: "following", mute: "hidden", neutral: "not set" } as const;

export function SportsPicker({ prefs, onMsg }: { prefs: Prefs; onMsg: (m: string | null) => void }) {
  const { leagues, muted } = prefs.sport;
  const cycle = async (name: string) => {
    const to = nextSportState(sportState(name, leagues, muted));
    const next = applySportState(name, to, leagues, muted);
    const err = await savePrefs({ sport: { leagues: next.leagues, muted: next.muted } });
    onMsg(err);
    if (!err) window.dispatchEvent(new Event(MUTED_EVENT));
  };
  return (
    <div className="chips settings-chips-gap" aria-label="Sports I follow">
      {SPORTS.map((n) => {
        const st = sportState(n, leagues, muted);
        return (
          <button key={n} className={`chip${st === "follow" ? " on" : ""}${st === "mute" ? " muted" : ""}`} data-nav aria-label={`${n}: ${HINT[st]}. Tap to change.`} onClick={() => void cycle(n)}>
            {MARK[st]}
            {n}
          </button>
        );
      })}
    </div>
  );
}

export function SportTagToggles({ prefs, onMsg }: { prefs: Prefs; onMsg: (m: string | null) => void }) {
  const { muted } = prefs.sport;
  const set = async (tag: string, on: boolean) => {
    const err = await savePrefs({ sport: { muted: withMutedTag(muted, tag, on) } });
    onMsg(err);
    if (!err) window.dispatchEvent(new Event(MUTED_EVENT));
  };
  return (
    <>
      <label className="iptv-check">
        <input type="checkbox" data-nav checked={hasTag(muted, TAG_WOMENS)} onChange={(e) => void set(TAG_WOMENS, e.target.checked)} />
        <span>Hide women's competitions</span>
      </label>
      <label className="iptv-check">
        <input type="checkbox" data-nav checked={hasTag(muted, TAG_REPLAYS)} onChange={(e) => void set(TAG_REPLAYS, e.target.checked)} />
        <span>Hide replays and highlights</span>
      </label>
    </>
  );
}
