// Kids profile on the client: blocked screens, and a per-title check for details and the player (an episode
// without its own rating takes its series'). Defence in depth; the server filters rows, specials and search.
import { getItem, type Item } from "../api/jellyfin";
import { kidsAllowed } from "../lib/kids";
import { go } from "../lib/router";

export function KidsBlocked({ what = "This" }: { what?: string }) {
  return (
    <div className="kids-blocked" role="alert">
      <p>{what} isn't available in kids mode.</p>
      <button className="btn" data-nav data-autofocus onClick={() => go("/home")}>
        Home
      </button>
    </div>
  );
}

const seriesRating = new Map<string, string | undefined>();

/** The rating that decides an item: its own, else (episodes, seasons) its series'. */
export async function ratingOf(item: Item): Promise<string | undefined> {
  if (item.OfficialRating || !item.SeriesId) return item.OfficialRating;
  if (!seriesRating.has(item.SeriesId)) seriesRating.set(item.SeriesId, (await getItem(item.SeriesId).catch(() => null))?.OfficialRating);
  return seriesRating.get(item.SeriesId);
}

export async function kidsMayOpen(item: Item, maxAge: number | null): Promise<boolean> {
  if (maxAge == null) return true;
  return kidsAllowed(await ratingOf(item), maxAge);
}
