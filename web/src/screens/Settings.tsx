// Settings (#/settings): the account and the per-person extras. Live TV / IPTV is open to every signed-in person.
import { getSession, logout } from "../api/jellyfin";
import { go } from "../lib/router";

export function Settings() {
  const s = getSession();
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
