// Tiny hash router: static hosting under /jellytip/ needs no server-side fallback.
import { useEffect, useState } from "react";

export interface Route {
  name: string;
  params: Record<string, string>;
  query: URLSearchParams;
}

const ID_RE = /^[0-9a-f]{32}$/i;

function parse(): Route {
  const raw = location.hash.replace(/^#/, "") || "/home";
  const [path, q = ""] = raw.split("?");
  const seg = path.split("/").filter(Boolean);
  const query = new URLSearchParams(q);
  switch (seg[0]) {
    case "item":
    case "play":
    case "person":
      // Ids go into API paths: anything but a Jellyfin id falls back to Home
      if (!ID_RE.test(seg[1] ?? "")) return { name: "home", params: {}, query };
      return { name: seg[0], params: { id: seg[1].toLowerCase() }, query };
    case "movies":
    case "shows":
    case "search":
    case "downloads":
    case "home":
      return { name: seg[0], params: {}, query };
    default:
      return { name: "home", params: {}, query };
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState(parse);
  useEffect(() => {
    const on = () => setRoute(parse());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  return route;
}

export function go(path: string, replace = false) {
  const h = `#${path}`;
  if (replace) location.replace(h);
  else location.hash = path;
}

export function back(fallback = "/home") {
  if (history.length > 1) history.back();
  else go(fallback, true);
}
