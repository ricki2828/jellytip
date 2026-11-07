// Journey data from existing APIs only: companion collection page (franchise), Jellyfin Items with PersonTypes=Director
// (director; one bounded call, never one per person), companion carousels manifest (Letterboxd). Results are cached per user
// for the session (errors are not); watched state is fetched fresh on each visit (one bounded Ids call).
import { getItems, jf, uid, type Item } from "./jellyfin";
import { collectionPageOrThrow, manifest } from "./companion";
import { buildEntries, buildJourney, createJourneyCache, directorQuery, type BuildDeps, type BuildResult, type Entry, type JourneyDef } from "../lib/journeys";

const cache = createJourneyCache();
window.addEventListener("jellytip-session", () => cache.clear());

const deps: BuildDeps = {
  collectionPage: (tmdbId) => collectionPageOrThrow(tmdbId),
  directorFilms: async (personId) => {
    const r = await getItems(directorQuery(personId));
    return { items: r.Items, total: r.TotalRecordCount };
  },
  personName: async (personId) => (await jf<{ Name?: string }>(`/Users/${uid()}/Items/${personId}`)).Name ?? "",
  list: async (slug) => {
    const m = await manifest();
    if (!m) throw new Error("manifest unavailable");
    return m.letterboxd.find((x) => x.slug === slug) ?? null;
  },
};

export const journeyResult = (id: string): Promise<BuildResult> => cache.get(uid(), id, () => buildJourney(id, deps));

/** The path with fresh watched state: one Items call by Ids (no type filter: whatever the library holds the film as). */
export async function journeyEntries(def: JourneyDef): Promise<Entry[]> {
  const ids = [...new Set(def.parts.map((p) => p.itemId).filter((x): x is string => !!x))];
  if (!ids.length) return buildEntries(def, []);
  const r = await getItems({ Ids: ids.join(","), Fields: "" });
  return buildEntries(def, r.Items as Item[]);
}
