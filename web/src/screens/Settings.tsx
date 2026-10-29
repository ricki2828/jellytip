// Settings (#/settings): Live TV / IPTV, and — when the server has personalisation — the person's own preferences
// (they follow the person to the TV and phone): hide watched, playback, Letterboxd, kids profile, "Your taste".
import { useEffect, useState } from "react";
import { getSession, logout } from "../api/jellyfin";
import { savePrefs, setKidsPin, usePrefs } from "../api/me";
import { dropPersonalCaches } from "../api/companion";
import { KIDS_AGES, type Prefs } from "../lib/prefs";
import { go } from "../lib/router";

const LANGS: [string, string][] = [
  ["en", "English"],
  ["mi", "Māori"],
  ["fr", "French"],
  ["es", "Spanish"],
  ["de", "German"],
  ["it", "Italian"],
  ["pt", "Portuguese"],
  ["nl", "Dutch"],
  ["sv", "Swedish"],
  ["ja", "Japanese"],
  ["ko", "Korean"],
  ["zh", "Chinese"],
  ["hi", "Hindi"],
];

const ageLabel = (age: number) => KIDS_AGES.find(([a]) => a === age)?.[1] ?? `${age}+`;

function Toggle({ label, sub, on, onChange, disabled }: { label: string; sub?: string; on: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <button className="settings-item" data-nav role="switch" aria-checked={on} disabled={disabled} onClick={() => onChange(!on)}>
      <span>
        <strong>{label}</strong>
        {sub && <span className="settings-sub">{sub}</span>}
      </span>
      <span className={`switch${on ? " on" : ""}`} aria-hidden />
    </button>
  );
}

function Langs({ label, value, onChange }: { label: string; value: string[]; onChange: (v: string[]) => void }) {
  return (
    <div className="settings-block">
      <strong>{label}</strong>
      <span className="settings-sub">In order of preference: tap to add, tap again to remove.</span>
      <div className="chips">
        {LANGS.map(([code, name]) => {
          const i = value.indexOf(code);
          return (
            <button key={code} className={`chip${i >= 0 ? " on" : ""}`} data-nav aria-pressed={i >= 0} onClick={() => onChange(i >= 0 ? value.filter((c) => c !== code) : [...value, code])}>
              {i >= 0 ? `${i + 1}. ` : ""}
              {name}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function PinForm({ title, onPin, onCancel }: { title: string; onPin: (pin: string) => Promise<string | null>; onCancel: () => void }) {
  const [pin, setPin] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="settings-block"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!/^\d{4,8}$/.test(pin)) return setErr("4 to 8 digits.");
        setBusy(true);
        const r = await onPin(pin);
        setBusy(false);
        setPin("");
        if (r) setErr(r);
      }}
    >
      <strong>{title}</strong>
      <input className="settings-input pin" data-nav data-autofocus type="password" inputMode="numeric" autoComplete="off" maxLength={8} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} aria-label="PIN" />
      {err && <span className="settings-err">{err}</span>}
      <div className="buttons">
        <button className="btn small primary" data-nav disabled={busy}>
          OK
        </button>
        <button type="button" className="btn small" data-nav onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function Personal({ prefs }: { prefs: Prefs }) {
  const [msg, setMsg] = useState<string | null>(null);
  const [lb, setLb] = useState(prefs.letterboxd.username ?? "");
  const [pinFor, setPinFor] = useState<"set" | "exit" | "raise" | null>(null);
  // Kids profile on: raising the age is a loosening, so it needs the PIN in the same change (server rule)
  const [raiseTo, setRaiseTo] = useState<number | null>(null);
  useEffect(() => setLb(prefs.letterboxd.username ?? ""), [prefs.letterboxd.username]);

  const save = async (patch: Record<string, unknown>, reloadRows = false) => {
    const e = await savePrefs(patch);
    setMsg(e);
    if (!e && reloadRows) dropPersonalCaches();
    return e;
  };
  const pb = prefs.playback;
  const lbStatus = { none: null, pending: "Linking…", linked: "Linked: your lists and ratings feed your rows", error: "Couldn't read that profile (is it public?)" }[prefs.letterboxd.status];

  return (
    <>
      <h2 className="settings-head">Your rows</h2>
      <ul className="settings-list">
        <li>
          <Toggle label="Hide watched" sub="Played films and shows leave your rows (Continue Watching stays)" on={prefs.hideWatched} onChange={(v) => void save({ hideWatched: v }, true)} />
        </li>
        <li>
          <button className="settings-item" data-nav onClick={() => go("/taste")}>
            <span>
              <strong>Your taste</strong>
              <span className="settings-sub">What you watch most: genres, directors, decades, countries</span>
            </span>
            <span aria-hidden>›</span>
          </button>
        </li>
      </ul>

      <h2 className="settings-head">Playback</h2>
      <ul className="settings-list">
        <li>
          <Toggle label="Intro" sub="The Jellytip intro when the TV app starts" on={pb.intro} onChange={(v) => void save({ playback: { intro: v } })} />
        </li>
        <li>
          <Toggle label="Trailers play on their own" sub="In the Home hero after a short pause" on={pb.trailerAutoplay} onChange={(v) => void save({ playback: { trailerAutoplay: v } })} />
        </li>
        <li>
          <Toggle label="Trailers with sound" sub="On the TV; browsers start them muted until you click" on={pb.trailerSound} onChange={(v) => void save({ playback: { trailerSound: v } })} />
        </li>
        <li>
          <Langs label="Audio languages" value={pb.audioLangs} onChange={(v) => void save({ playback: { audioLangs: v } })} />
        </li>
        <li>
          <Langs label="Subtitle languages" value={pb.subtitleLangs} onChange={(v) => void save({ playback: { subtitleLangs: v } })} />
        </li>
      </ul>

      <h2 className="settings-head">Letterboxd</h2>
      <form
        className="settings-block settings-pad"
        onSubmit={(e) => {
          e.preventDefault();
          void save({ letterboxd: { username: lb.trim() || null } }, true);
        }}
      >
        <span className="settings-sub">Your public Letterboxd profile: its lists and ratings shape your rows.</span>
        <div className="settings-inline">
          <input className="settings-input" data-nav value={lb} onChange={(e) => setLb(e.target.value)} placeholder="username" autoCapitalize="none" spellCheck={false} maxLength={40} aria-label="Letterboxd username" />
          <button className="btn small" data-nav disabled={lb.trim() === (prefs.letterboxd.username ?? "")}>
            Save
          </button>
        </div>
        {lbStatus && <span className={`settings-sub${prefs.letterboxd.status === "error" ? " settings-err" : ""}`}>{lbStatus}</span>}
      </form>

      <h2 className="settings-head">Kids profile</h2>
      <ul className="settings-list">
        <li>
          <Toggle
            label="Kids profile"
            sub={
              prefs.kids.enabled
                ? `On: ${ageLabel(prefs.kids.maxAge)} titles only, a simpler Home. The PIN turns it off.`
                : "Only titles for an age, a simpler Home; a PIN to leave it"
            }
            on={prefs.kids.enabled}
            onChange={(v) => {
              if (v && !prefs.kids.pinSet) setPinFor("set");
              else if (v) void save({ kids: { enabled: true } }, true);
              else setPinFor("exit");
            }}
          />
        </li>
        {pinFor === "set" && (
          <li>
            <PinForm
              title="Choose a PIN, 4 to 8 digits (needed to leave the kids profile)"
              onCancel={() => setPinFor(null)}
              onPin={async (pin) => {
                const e = (await setKidsPin(pin)) ?? (prefs.kids.enabled ? null : await save({ kids: { enabled: true } }, true));
                if (!e) setPinFor(null);
                return e;
              }}
            />
          </li>
        )}
        {pinFor === "exit" && (
          <li>
            <PinForm
              title="PIN to leave the kids profile"
              onCancel={() => setPinFor(null)}
              onPin={async (pin) => {
                // The server checks the PIN with the change (403 {"error":"pin"} when wrong, 429 after 5 tries)
                const e = await save({ kids: { enabled: false, pin } }, true);
                if (!e) setPinFor(null);
                return e;
              }}
            />
          </li>
        )}
        <li className="settings-block">
          <strong>Suitable for</strong>
          <div className="chips">
            {KIDS_AGES.map(([age, label]) => (
              <button
                key={age}
                className={`chip${prefs.kids.maxAge === age ? " on" : ""}`}
                data-nav
                aria-pressed={prefs.kids.maxAge === age}
                onClick={() => {
                  if (age === prefs.kids.maxAge) return;
                  if (prefs.kids.enabled && age > prefs.kids.maxAge) {
                    setRaiseTo(age);
                    setPinFor("raise");
                  } else void save({ kids: { maxAge: age } }, true);
                }}
              >
                {label}
              </button>
            ))}
          </div>
          {prefs.kids.enabled && <span className="settings-sub">Raising the age needs the PIN.</span>}
        </li>
        {pinFor === "raise" && raiseTo != null && (
          <li>
            <PinForm
              title={`PIN to allow ${ageLabel(raiseTo)}`}
              onCancel={() => {
                setPinFor(null);
                setRaiseTo(null);
              }}
              onPin={async (pin) => {
                const e = await save({ kids: { maxAge: raiseTo, pin } }, true);
                if (!e) {
                  setPinFor(null);
                  setRaiseTo(null);
                }
                return e;
              }}
            />
          </li>
        )}
        {prefs.kids.pinSet && !prefs.kids.enabled && pinFor === null && (
          <li>
            <button className="settings-item" data-nav onClick={() => setPinFor("set")}>
              <span>
                <strong>Change PIN</strong>
              </span>
            </button>
          </li>
        )}
      </ul>
      {msg && <p className="settings-err settings-pad">{msg}</p>}
    </>
  );
}

export function Settings() {
  const s = getSession();
  const { available, prefs } = usePrefs();
  return (
    <div className="settings-page">
      <h1 className="page-title">Settings</h1>
      <ul className="settings-list">
        <li>
          <button className="settings-item" data-nav data-autofocus onClick={() => go("/iptv")}>
            <span>
              <strong>Live TV / IPTV</strong>
              <span className="settings-sub">Add your own provider, pick channel groups, films and series, recordings</span>
            </span>
            <span aria-hidden>›</span>
          </button>
        </li>
      </ul>
      {available && prefs && <Personal prefs={prefs} />}
      <h2 className="settings-head">Account</h2>
      <ul className="settings-list">
        <li>
          <button className="settings-item" data-nav onClick={() => logout()}>
            <span>
              <strong>Sign out</strong>
              <span className="settings-sub">Signed in as {s?.userName}</span>
            </span>
          </button>
        </li>
      </ul>
    </div>
  );
}
