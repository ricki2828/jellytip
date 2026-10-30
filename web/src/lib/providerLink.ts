// Forgiving input for the IPTV add form (pure; tested in web/test/iptv.test.ts). Never logs what it parses: the
// strings hold logins.

export type ProviderLink =
  | { kind: "xtream"; server: string; username: string; password: string }
  | { kind: "m3u"; playlistUrl: string };

const SCRIPT = /\/(get\.php|player_api\.php|xmltv\.php|panel_api\.php|portal\.php|c)\/?$/i;

function toUrl(raw: string): URL | null {
  const t = raw.trim();
  if (!t || /\s/.test(t)) return null;
  try {
    const u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : `http://${t}`);
    return (u.protocol === "http:" || u.protocol === "https:") && u.hostname.includes(".") || u.hostname === "localhost" ? u : null;
  } catch {
    return null;
  }
}

const dec = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};

/** A server field value made usable: spaces trimmed, http:// added when there is no scheme, query and
 * /get.php-style tails dropped. Null when it is not an address. */
export function normaliseServer(raw: string): string | null {
  const u = toUrl(raw);
  if (!u) return null;
  const path = u.pathname.replace(SCRIPT, "").replace(/\/+$/, "");
  return `${u.protocol}//${u.host}${path}`;
}

/** Read a pasted provider link: an Xtream one (get.php / player_api.php with username+password, or
 * /live/U/P/123.ts) or a plain playlist address. Null when it is not a link. */
export function parseProviderLink(raw: string): ProviderLink | null {
  const u = toUrl(raw);
  if (!u) return null;
  const username = u.searchParams.get("username")?.trim();
  const password = u.searchParams.get("password");
  if (username && password) {
    return { kind: "xtream", server: normaliseServer(`${u.protocol}//${u.host}${u.pathname}`) ?? `${u.protocol}//${u.host}`, username, password };
  }
  const m = u.pathname.match(/^(.*?)\/(?:live|movie|series|timeshift)\/([^/]+)\/([^/]+)\/[^/]+$/i);
  if (m && m[2] && m[3]) {
    return { kind: "xtream", server: `${u.protocol}//${u.host}${m[1].replace(/\/+$/, "")}`, username: dec(m[2]), password: dec(m[3]) };
  }
  return { kind: "m3u", playlistUrl: raw.trim().startsWith("http") ? raw.trim() : u.href };
}

/** A pasted address with http:// added when it has no scheme; null when it is not a web address. */
export function normaliseWebUrl(raw: string): string | null {
  const t = raw.trim();
  return toUrl(t) ? (/^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : `http://${t}`) : null;
}
