// Sport interaction events for the ranker (POST /v1/me/sport/events; contract: ~/ai/state/jellytip-sport-relevance-plan.md,
// "Update 21-Nov"). Pure: event shape, validation, and the queue (a 'shown' counts once per list load; nothing else is deduped).

export const SPORT_EVENT_TYPES = ["shown", "opened", "watch", "remind", "record", "not_interested", "kickoff_ok", "kickoff_back"] as const;
export const SPORT_SURFACES = ["gamecentre", "home_hero", "home_row", "kickoff_card", "player_switcher", "search", "web_fixtures"] as const;
export type SportEventType = (typeof SPORT_EVENT_TYPES)[number];
export type SportSurface = (typeof SPORT_SURFACES)[number];

export interface SportEvent {
  type: SportEventType;
  fid: string;
  surface: SportSurface;
  /** Where on the surface: Game Centre's group ("live", "yours", "big", "finished", "hero"). */
  section?: string;
  /** 0-based position in that section. */
  pos?: number;
  /** The fixtures answer the tile came from (its `listId`). */
  listId?: string;
  /** ms since the Unix epoch */
  at?: number;
  ms?: number;
}

export const SPORT_BATCH_MAX = 50;
/** Most events kept while the server can't be reached; the oldest go first. */
export const SPORT_QUEUE_CAP = 200;
/** A tile counts as 'shown' once it has been on screen this long. */
export const SHOWN_AFTER_MS = 1000;

export function validSportEvent(e: SportEvent): boolean {
  return (
    SPORT_EVENT_TYPES.includes(e.type) &&
    SPORT_SURFACES.includes(e.surface) &&
    typeof e.fid === "string" &&
    e.fid.length > 0 &&
    (e.pos === undefined || (Number.isInteger(e.pos) && e.pos >= 0)) &&
    (e.ms === undefined || (Number.isFinite(e.ms) && e.ms >= 0))
  );
}

/**
 * 'shown' is once per list load (listId; without one, per page load), surface, section and fixture: Game Centre's hero and
 * its card for the same game are two impressions. Other events always count.
 */
export const shownKey = (e: Pick<SportEvent, "fid" | "surface" | "listId" | "section">): string => `${e.listId ?? "-"}|${e.surface}|${e.section ?? ""}|${e.fid}`;

/** The wire form: only the contract's fields, undefined ones dropped. */
export function wireEvent(e: SportEvent): SportEvent {
  const out: SportEvent = { type: e.type, fid: e.fid, surface: e.surface };
  if (e.section) out.section = e.section;
  if (e.pos !== undefined) out.pos = e.pos;
  if (e.listId) out.listId = e.listId;
  if (e.at !== undefined) out.at = e.at;
  if (e.ms !== undefined) out.ms = Math.round(e.ms);
  return out;
}

export interface SportQueue {
  /** false when invalid or an already-counted 'shown'. */
  add(e: SportEvent): boolean;
  take(max?: number): SportEvent[];
  done(sent: SportEvent[]): void;
  size(): number;
  clear(): void;
}

export function createSportQueue(cap = SPORT_QUEUE_CAP): SportQueue {
  let list: SportEvent[] = [];
  const shown = new Set<string>();
  return {
    add(e) {
      if (!validSportEvent(e)) return false;
      if (e.type === "shown") {
        const k = shownKey(e);
        if (shown.has(k)) return false;
        shown.add(k);
      }
      list.push(e);
      if (list.length > cap) list = list.slice(list.length - cap);
      return true;
    },
    take: (max = SPORT_BATCH_MAX) => list.slice(0, Math.min(max, SPORT_BATCH_MAX)),
    done(sent) {
      const gone = new Set(sent);
      list = list.filter((x) => !gone.has(x));
    },
    size: () => list.length,
    clear() {
      list = [];
      shown.clear();
    },
  };
}
