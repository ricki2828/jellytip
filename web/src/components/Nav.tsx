import { getSession, logout } from "../api/jellyfin";
import { go } from "../lib/router";

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

const LINKS: [string, string][] = [
  ["home", "Home"],
  ["movies", "Movies"],
  ["shows", "Shows"],
  ["search", "Search"],
];

export function Nav({ current }: { current: string }) {
  const s = getSession();
  return (
    <nav className="nav">
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
    </nav>
  );
}
