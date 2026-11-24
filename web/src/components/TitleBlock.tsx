import { logoUrl, runtimeText, type Item } from "../api/jellyfin";
import { logoOwner } from "../lib/logoStyle";
import { HeroLogo } from "./DetailsScaffold";

/** Clearlogo drawn as an off-white silhouette (never repeats the coloured tile logo), else the typeset title. */
export function TitleLogo({ item, big = false }: { item: Item; big?: boolean }) {
  return <HeroLogo url={logoUrl(item)} name={item.Type === "Episode" ? item.SeriesName ?? item.Name : item.Name} owner={logoOwner(item)} big={big} />;
}

export function infoLine(item: Item): string {
  return [
    item.ProductionYear?.toString(),
    item.OfficialRating,
    item.Type === "Series" ? null : runtimeText(item.RunTimeTicks),
    item.Genres?.slice(0, 3).join(", ") || null,
  ]
    .filter(Boolean)
    .join("  ·  ");
}

/** e.g. ["4K", "HEVC", "HDR", "EAC3 5.1"] from the first media source. */
export function fileBadges(item: Item): string[] {
  const s = item.MediaSources?.[0]?.MediaStreams ?? [];
  const v = s.find((x) => x.Type === "Video");
  const a = s.find((x) => x.Type === "Audio" && x.IsDefault) ?? s.find((x) => x.Type === "Audio");
  const out: string[] = [];
  if (v?.Width) out.push(v.Width >= 3200 ? "4K" : v.Width >= 1800 ? "1080p" : v.Width >= 1200 ? "720p" : "SD");
  if (v?.Codec) out.push(v.Codec === "hevc" ? "HEVC" : v.Codec === "h264" ? "H.264" : v.Codec.toUpperCase());
  if (v?.VideoRangeType && v.VideoRangeType !== "SDR") out.push(v.VideoRangeType.startsWith("DOVI") ? "Dolby Vision" : "HDR");
  if (a?.Codec) {
    const ch = a.Channels ? (a.Channels >= 8 ? " 7.1" : a.Channels >= 6 ? " 5.1" : a.Channels === 2 ? " 2.0" : "") : "";
    out.push(a.Codec.toUpperCase() + ch);
  }
  return out;
}
