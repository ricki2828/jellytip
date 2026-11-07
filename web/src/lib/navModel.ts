// Navigation model (pure, no imports: tested in test/navModel.test.ts). Which top-level item a route lights up, and which Live sub-tabs show.

export type LiveTabKey = "guide" | "channels" | "sport" | "fantasy" | "recordings";

/** Routes that belong to the Live room. Old links (#/livetv, #/channels, ...) keep working and light Live. */
export const LIVE_ROUTES: readonly string[] = ["live", "livetv", "channels", "upcoming", "games", "reminders", "fantasy", "recordings"];

/** Routes reached from the avatar menu (the avatar shows as current). */
const YOU_ROUTES = new Set(["settings", "taste", "iptv", "downloads", "discover", "manage"]);

export type NavItem = "home" | "movies" | "shows" | "live" | "collections" | "search" | "inbox" | "you";

/** Route name → the nav item that is current (null: nothing, e.g. a detail page). */
export function navActive(route: string): NavItem | null {
  if (LIVE_ROUTES.includes(route)) return "live";
  if (route === "ask") return "search";
  if (route === "collectionrow" || route === "journeys" || route === "journey") return "collections";
  if (YOU_ROUTES.has(route)) return "you";
  switch (route) {
    case "home":
    case "movies":
    case "shows":
    case "collections":
    case "search":
    case "inbox":
      return route;
    default:
      return null;
  }
}

export interface LiveFlags {
  liveTv: boolean;
  fantasyLinked: boolean;
  hasRecs: boolean;
  /** Upcoming sport exists (Game Centre is always reachable from the Sport tab). */
  hasUpcoming: boolean;
}

/** Sub-tabs of the Live room, in order. My Channels and Sport always show: the room is never empty and does not shift. */
export function liveTabs(f: LiveFlags): LiveTabKey[] {
  const tabs: LiveTabKey[] = [];
  if (f.liveTv) tabs.push("guide");
  tabs.push("channels", "sport");
  if (f.fantasyLinked) tabs.push("fantasy");
  if (f.hasRecs) tabs.push("recordings");
  return tabs;
}

/** Where #/live lands. */
export const liveDefault = (liveTv: boolean): string => (liveTv ? "livetv" : "channels");

/** Route name → the Live sub-tab it belongs to. */
export function liveTabFor(route: string): LiveTabKey | null {
  switch (route) {
    case "livetv":
      return "guide";
    case "channels":
      return "channels";
    case "upcoming":
    case "games":
    case "reminders":
      return "sport";
    case "fantasy":
      return "fantasy";
    case "recordings":
      return "recordings";
    default:
      return null;
  }
}

/** Hash target for a sub-tab. */
export const liveTabHref = (k: LiveTabKey): string => `#/${k === "guide" ? "livetv" : k === "sport" ? "upcoming" : k}`;

export const LIVE_TAB_LABEL: Record<LiveTabKey, string> = { guide: "Guide", channels: "My Channels", sport: "Sport", fantasy: "Fantasy", recordings: "Recordings" };
