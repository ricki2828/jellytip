import { useEffect, useState } from "react";
import { go, isRootRoute, systemBack } from "../lib/router";
import { afterStartup } from "../lib/startup";
import { hubNow, loadHub, onHubChange } from "../api/collections";
import { useInbox } from "../api/inboxStore";
import { unreadCount } from "../lib/inbox";
import { navActive } from "../lib/navModel";
import { AvatarMenu } from "./AvatarMenu";
import { useLiveFlags } from "./navFlags";

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
  live: (
    <>
      <circle cx="12" cy="12" r="2" fill="currentColor" />
      <path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2" />
    </>
  ),
};

/** The five fixed phone tabs, and the first four desktop links (Collections joins them when the hub exists). */
const TABS: [string, string][] = [
  ["home", "Home"],
  ["movies", "Movies"],
  ["shows", "Shows"],
  ["live", "Live"],
  ["search", "Search"],
];

const BELL = (
  <svg viewBox="0 0 24 24" aria-hidden>
    <path d="M4 13.5 6.5 5.5h11L20 13.5V18a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" />
    <path d="M4 13.5h4.5a3.5 3.5 0 0 0 7 0H20" />
  </svg>
);

export function Nav({ current }: { current: string }) {
  const { hasRecs, ready } = useLiveFlags();
  const active = navActive(current);
  const dot = hasRecs && ready > 0;
  // Inbox: looks once at app start (at most every 15 min) and shows the unread count
  const unreadN = unreadCount(useInbox({ soon: true, refresh: true }));
  // Collections: the last link of the left group, once the server has a hub (a 404 keeps it hidden)
  const [, hubTick] = useState(0);
  useEffect(() => {
    let alive = true;
    void afterStartup().then(() => {
      if (alive) void loadHub();
    });
    const off = onHubChange(() => hubTick((n) => n + 1));
    return () => {
      alive = false;
      off();
    };
  }, []);
  const hasHub = (hubNow()?.length ?? 0) > 0;
  const links: [string, string][] = [...TABS.filter(([k]) => k !== "search"), ...(hasHub ? ([["collections", "Collections"]] as [string, string][]) : [])];
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 40);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  const root = isRootRoute(current);
  // Tapping the current room again must not push a duplicate history entry: scroll to top instead
  const again = (k: string) => (e: React.MouseEvent) => {
    if (current === k) {
      e.preventDefault();
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  };
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
          <a key={k} href={`#/${k}`} aria-label={k === "live" && dot ? `Live, ${ready} recordings ready` : undefined} data-nav onClick={again(k)} className={active === k ? "active" : ""} aria-current={active === k ? "page" : undefined}>
            {label}
            {k === "live" && dot && <span className="nav-dot" aria-hidden />}
          </a>
        ))}
      </div>
      <div className="nav-user">
        <a href="#/search" className={`nav-icon nav-search${active === "search" ? " active" : ""}`} aria-label="Search" title="Search" data-nav>
          <svg viewBox="0 0 24 24" aria-hidden>
            {ICONS.search}
          </svg>
        </a>
        <a href="#/inbox" className={`nav-icon nav-inbox${active === "inbox" ? " active" : ""}`} aria-label={unreadN > 0 ? `Inbox, ${unreadN} unread` : "Inbox"} title="Inbox" data-nav>
          {BELL}
          {unreadN > 0 && <span className="nav-badge">{unreadN}</span>}
        </a>
        <AvatarMenu active={active === "you"} />
      </div>
      {/* Phone: exactly five fixed tabs as a bottom bar (shown by the phone media query only) */}
      <div className="tabbar" role="tablist">
        {TABS.map(([k, label]) => (
          <a key={k} href={`#/${k}`} role="tab" aria-label={k === "live" && dot ? `Live, ${ready} recordings ready` : undefined} onClick={again(k)} aria-selected={active === k} className={active === k ? "active" : ""}>
            <svg viewBox="0 0 24 24" aria-hidden>
              {ICONS[k]}
            </svg>
            <span>{label}</span>
            {k === "live" && dot && <span className="nav-dot" aria-hidden />}
          </a>
        ))}
      </div>
    </nav>
  );
}
