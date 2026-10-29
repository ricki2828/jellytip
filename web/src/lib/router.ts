// Tiny hash router: static hosting under /jellytip/ needs no server-side fallback.
import { useEffect, useState } from "react";

export interface Route {
  name: string;
  params: Record<string, string>;
  query: URLSearchParams;
}

function parse(): Route {
  const raw = location.hash.replace(/^#/, "") || "/home";
  const [path, q = ""] = raw.split("?");
  const seg = path.split("/").filter(Boolean);
  const query = new URLSearchParams(q);
  switch (seg[0]) {
    case "item":
      return { name: "item", params: { id: seg[1] ?? "" }, query };
    case "play":
      return { name: "play", params: { id: seg[1] ?? "" }, query };
    case "movies":
    case "shows":
    case "search":
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
