import { useEffect, useState } from "react";
import { getSession, logout } from "../api/jellyfin";
import { go, isRootRoute, systemBack } from "../lib/router";
import { isNative } from "../lib/native";
import { hasLiveTv, iptvChanged } from "../api/iptv";
import { afterStartup } from "../lib/startup";
import { useRecs } from "../api/recStore";
import { shown, unwatchedCount } from "../lib/recs";

/** Live TV shows in the nav only for someone with opted-in channels (their own provider or one shared with them). */
function useLiveTv(): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    let alive = true;
    // After the first screen's requests: the Live TV link can appear a moment later
    const check = () => void afterStartup().then(hasLiveTv).then((v) => alive && setOn(v));
    const onSession = () => iptvChanged();
    check();
    window.addEventListener("jellytip-iptv", check);
    window.addEventListener("jellytip-session", onSession);
    return () => {
      alive = false;
      window.removeEventListener("jellytip-iptv", check);
      window.removeEventListener("jellytip-session", onSession);
    };
  }, []);
  return on;
}

export function Logo({ large = false }: { large?: boolean }) {
  return (
    <span className={large ? "logo large" : "logo"}>
      <svg viewBox="0 0 100 170" aria-hidden>
        <path fill="#D9A86C" d="M43,119 A7,7 0 0 1 57,119 V159 A7,7 0 0 1 43,159 Z" />
        <path fill="#F7E8C4" d="M48,18 H52 A30,30 0 0 1 82,48 V92 A30,30 0 0 1 52,122 H48 A30,30 0 0 1 18,92 V48 A30,30 0 0 1 48,18 Z" />
        <path fill="#5B3421" d="M18,70 V48 A32,32 0 0 1 82,48 V70 Q74,78 66,70 Q58,62 50,70 Q42,78 34,70 Q26,62 18,70 Z" />
        <path fill="#E2264D" d="M27,36 A23,23 0 0 1 73,36 Q67,42 61,36 Q56,31 50,36 Q44,42 39,36 Q33,31 27,36 Z" />
        <path fill="#5B3421" d="M44,84 L62,96 L44,108 Z" />
      </svg>
      <span className="logo-word">
        <span className="jelly">jelly</span>tip
      </span>
    </span>
  );
}

const ICONS: Record<string, React.ReactNode> = {
  home: <path d="M4 11.5 12 5l8 6.5V20a1 1 0 0 1-1 1h-4.5v-5.5h-5V21H5a1 1 0 0 1-1-1z" />,
  movies: (
    <>
      <rect x="3.5" y="5" width="17" height="14" rx="2" />
      <path d="M3.5 9.5h17M8 5v4.5M16 5v4.5" />
    </>
  ),
  shows: (
    <>
      <rect x="3" y="6" width="18" height="12.5" rx="2" />
      <path d="M9 21h6M9 2.5l3 3.5 3-3.5" />
    </>
  ),
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6" />
      <path d="m15 15 5.5 5.5" />
    </>
  ),
  downloads: (
    <>
      <path d="M12 4v11M7.5 10.5 12 15l4.5-4.5" />
      <path d="M5 19.5h14" />
    </>
  ),
};

const LINKS: [string, string][] = [
  ["home", "Home"],
  ["movies", "Movies"],
  ["shows", "Shows"],
  ["search", "Search"],
  // Offline downloads exist only in the Android app
  ...(isNative ? ([["downloads", "Downloads"]] as [string, string][]) : []),
];

export function Nav({ current }: { current: string }) {
  const s = getSession();
  // Admins also get Discover: a top-bar link on desktop, a compass in the phone top bar (the tab bar stays as is)
  const liveTv = useLiveTv();
  // Recordings: a link once there are any, with the count of ready, unwatched ones
  const recs = useRecs();
  const hasRecs = !!recs && shown(recs).length > 0;
  const ready = recs ? unwatchedCount(recs) : 0;
  const links: [string, string][] = [...LINKS, ...(liveTv ? ([["livetv", "Live TV"]] as [string, string][]) : []), ...(hasRecs ? ([["recordings", "Recordings"]] as [string, string][]) : []), ...(s?.isAdmin ? ([["discover", "Discover"]] as [string, string][]) : [])];
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 40);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  const root = isRootRoute(current);
  return (
    <nav className={`nav${scrolled ? " solid" : ""}${root ? "" : " has-back"}`}>
      {/* Every non-root screen: same Back as Android's (sheet / sound first, then the previous screen, else Home) */}
      {!root && (
        <button className="nav-back" onClick={systemBack} aria-label="Back" title="Back">
          <svg viewBox="0 0 24 24" aria-hidden>
            <path d="M14.5 5.5 8 12l6.5 6.5" />
          </svg>
        </button>
      )}
      <button className="nav-logo" onClick={() => go("/home")} tabIndex={-1} aria-label="Home">
        <Logo />
      </button>
      <div className="nav-links">
        {links.map(([k, label]) => (
          <a key={k} href={`#/${k}`} data-nav className={current === k ? "active" : ""}>
            {label}
            {k === "recordings" && ready > 0 && <span className="nav-badge" aria-label={`${ready} ready to watch`}>{ready}</span>}
          </a>
        ))}
      </div>
      <div className="nav-user">
        {s?.isAdmin && (
          <a href="#/discover" className={`nav-discover${current === "discover" ? " active" : ""}`} aria-label="Discover" title="Discover">
            <svg viewBox="0 0 24 24" aria-hidden>
              <circle cx="12" cy="12" r="8.5" />
              <path d="m15.5 8.5-2 5-5 2 2-5z" />
            </svg>
          </a>
        )}
        {/* Phone: Live TV as an icon in the top bar (the bottom tab bar keeps its 4-5 tabs) */}
        {liveTv && (
          <a href="#/livetv" className={`nav-icon nav-livetv${current === "livetv" ? " active" : ""}`} aria-label="Live TV" title="Live TV">
            <svg viewBox="0 0 24 24" aria-hidden>
              <rect x="3" y="6" width="18" height="13" rx="2" />
              <path d="M8.5 2.5 12 6l3.5-3.5" />
              <circle cx="17.5" cy="9.5" r="1" fill="currentColor" stroke="none" />
            </svg>
          </a>
        )}
        {hasRecs && (
          <a href="#/recordings" className={`nav-icon nav-recs${current === "recordings" ? " active" : ""}`} aria-label="Recordings" title="Recordings">
            <svg viewBox="0 0 24 24" aria-hidden>
              <circle cx="12" cy="12" r="8.5" />
              <circle cx="12" cy="12" r="3.2" fill="currentColor" stroke="none" />
            </svg>
            {ready > 0 && <span className="nav-badge">{ready}</span>}
          </a>
        )}
        <a href="#/settings" className={`nav-icon nav-settings${current === "settings" || current === "iptv" ? " active" : ""}`} aria-label="Settings" title="Settings" data-nav>
          <svg viewBox="0 0 24 24" aria-hidden>
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
          <span className="nav-settings-label">Settings</span>
        </a>
        <span>{s?.userName}</span>
        <button data-nav className="link" onClick={() => logout()}>
          Sign out
        </button>
      </div>
      {/* Phone: the same links as a bottom tab bar (shown by the phone media query only) */}
      <div className="tabbar" role="tablist">
        {LINKS.map(([k, label]) => (
          <a key={k} href={`#/${k}`} role="tab" aria-selected={current === k} className={current === k ? "active" : ""}>
            <svg viewBox="0 0 24 24" aria-hidden>
              {ICONS[k]}
            </svg>
            <span>{label}</span>
          </a>
        ))}
      </div>
    </nav>
  );
}
