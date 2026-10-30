// Settings (#/settings): Live TV / IPTV, and — when the server has personalisation — the person's own preferences
// (they follow the person to the TV and phone): hide watched, playback, Letterboxd, "Your taste".
import { useEffect, useState } from "react";
import { getSession, logout } from "../api/jellyfin";
import { savePrefs, usePrefs } from "../api/me";
import { dropPersonalCaches } from "../api/companion";
import type { Prefs } from "../lib/prefs";
import { go } from "../lib/router";
import { SportSettings } from "../components/SportSettings";

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

function Personal({ prefs }: { prefs: Prefs }) {
  const [msg, setMsg] = useState<string | null>(null);
  const [lb, setLb] = useState(prefs.letterboxd.username ?? "");
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

      <SportSettings prefs={prefs} />

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
