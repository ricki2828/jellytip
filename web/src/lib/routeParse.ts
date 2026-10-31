// Hash route parsing (pure, no imports: tested in web/test/rows.test.ts). The router in router.ts reads location.hash through this.

export interface Route {
  name: string;
  params: Record<string, string>;
  query: URLSearchParams;
}

const ID_RE = /^[0-9a-f]{32}$/i;

/** A hash ("#/collections/franchise%3A645", with or without the #) → the route. Ids are percent-decoded where they may hold ":" or "~". */
export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#/, "") || "/home";
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
    case "list":
      // Letterboxd list slugs
      if (!/^[a-z0-9][a-z0-9_-]{0,120}$/i.test(seg[1] ?? "")) return { name: "home", params: {}, query };
      return { name: "list", params: { id: seg[1] }, query };
    case "collection":
      // TMDB collection ids are numeric
      if (!/^\d{1,9}$/.test(seg[1] ?? "")) return { name: "home", params: {}, query };
      return { name: "collection", params: { id: seg[1] }, query };
    case "collections":
      // #/collections = the hub; #/collections/<id> = one collection (ids are the server's row ids)
      if (seg[1] == null) return { name: "collections", params: {}, query };
      {
        // The link carries the id percent-encoded ("franchise:645" → "franchise%3A645"): decode, then check it
        let id = seg[1];
        try {
          id = decodeURIComponent(id);
        } catch {
          return { name: "collections", params: {}, query };
        }
        if (!/^[^\s/?#%]{1,160}$/.test(id)) return { name: "collections", params: {}, query };
        return { name: "collectionrow", params: { id }, query };
      }
    case "iptv":
      // #/iptv = settings for the person's providers; #/iptv/play?… = the IPTV player; #/iptv/item?id=… = film / series
      return { name: seg[1] === "play" ? "iptvplay" : seg[1] === "item" ? "iptvitem" : "iptv", params: {}, query };
    case "settings":
    case "taste":
    case "livetv":
    case "recordings":
    case "upcoming":
    case "discover":
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

