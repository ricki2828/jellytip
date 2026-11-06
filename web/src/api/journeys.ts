// Journey data from existing APIs only: companion collection page (franchise), Jellyfin Items with PersonTypes=Director
// (director; one bounded call, never one per person), companion carousels manifest (Letterboxd). Definitions are cached per user
// for the session; watched state is fetched fresh on each visit (one bounded Ids call).
import { getItems, jf, uid, type Item } from "./jellyfin";
import { collectionPage, manifest } from "./companion";
import { buildEntries, createJourneyCache, directorDef, franchiseDef, listDef, parseJourneyId, type Entry, type JourneyDef } from "../lib/journeys";

const defs = createJourneyCache();
window.addEventListener("jellytip-session", () => defs.clear());

/** More than the cap so eligibility (4+) and chronological order are judged on the library's real credits. */
const DIRECTOR_QUERY_LIMIT = 60;

async function build(id: string): Promise<JourneyDef | null> {
  const p = parseJourneyId(id);
  if (!p) return null;
  if (p.kind === "franchise") {
    const page = await collectionPage(Number(p.key));
    return page ? franchiseDef(p.key, page) : null;
  }
  if (p.kind === "director") {
    const [r, person] = await Promise.all([
      getItems({ PersonIds: p.key, PersonTypes: "Director", Recursive: true, IncludeItemTypes: "Movie", SortBy: "PremiereDate,SortName", SortOrder: "Ascending", Limit: DIRECTOR_QUERY_LIMIT, Fields: "" }),
      jf<{ Name?: string }>(`/Users/${uid()}/Items/${p.key}`),
    ]);
    return directorDef(p.key, person.Name ?? "", r.Items);
  }
  const m = await manifest();
  const l = m?.letterboxd.find((x) => x.slug === p.key);
  return l ? listDef(l) : null;
}

export const journeyDef = (id: string): Promise<JourneyDef | null> => defs.get(uid(), id, () => build(id));

/** The path with fresh watched state: one Items call for the (at most a few dozen) ids on it. */
export async function journeyEntries(def: JourneyDef): Promise<Entry[]> {
  const ids = [...new Set(def.parts.map((p) => p.itemId).filter((x): x is string => !!x))];
  if (!ids.length) return buildEntries(def, []);
  const r = await getItems({ Ids: ids.join(","), IncludeItemTypes: "Movie", Fields: "" });
  return buildEntries(def, r.Items as Item[]);
}

