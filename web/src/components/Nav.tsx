import { useEffect, useState } from "react";
import { getSession, logout } from "../api/jellyfin";
import { go } from "../lib/router";
import { isNative } from "../lib/native";

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
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 40);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  return (
    <nav className={scrolled ? "nav solid" : "nav"}>
      <button className="nav-logo" onClick={() => go("/home")} tabIndex={-1} aria-label="Home">
        <Logo />
      </button>
      <div className="nav-links">
        {LINKS.map(([k, label]) => (
          <a key={k} href={`#/${k}`} data-nav className={current === k ? "active" : ""}>
            {label}
          </a>
        ))}
      </div>
      <div className="nav-user">
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
