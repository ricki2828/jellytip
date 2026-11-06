// IPTV tiles announce focus / hover / tap so the Home hero can follow them (Jellyfin tiles pass onFocusItem instead).
import type { IptvHeroTile } from "../components/Hero";

type Listener = (t: IptvHeroTile) => void;
const listeners = new Set<Listener>();

export const announceIptvFocus = (t: IptvHeroTile) => listeners.forEach((l) => l(t));

export function onIptvFocus(l: Listener): () => void {
  listeners.add(l);
  return () => void listeners.delete(l);
}
