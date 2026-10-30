import { lazy, Suspense, useEffect, useState } from "react";
import { getSession } from "./api/jellyfin";
import { useRoute } from "./lib/router";
import { Nav } from "./components/Nav";
import { Login } from "./screens/Login";
import { Home } from "./screens/Home";
import { Library } from "./screens/Library";
import { Details } from "./screens/Details";
import { Player } from "./screens/Player";
import { Search } from "./screens/Search";
import { Downloads } from "./screens/Downloads";
import { Person } from "./screens/Person";
import { Collection } from "./screens/Collection";
import { Discover } from "./screens/Discover";
import { Settings } from "./screens/Settings";
import { ListPage } from "./screens/ListPage";
import { Taste } from "./screens/Taste";
import { TileMenuHost } from "./components/TasteButtons";
import { parseTarget } from "./lib/iptvPlay";
import { VOD_ID_RE } from "./lib/iptvVod";
import { isNative } from "./lib/native";
import { go, isRestoring } from "./lib/router";

// IPTV screens (and mpegts.js / hls.js behind them) load only when someone opens them
const IptvSettings = lazy(() => import("./screens/iptv/IptvSettings").then((m) => ({ default: m.IptvSettings })));
const RecordingsPage = lazy(() => import("./screens/iptv/RecordingsPage").then((m) => ({ default: m.RecordingsPage })));
const LiveTv = lazy(() => import("./screens/iptv/LiveTv").then((m) => ({ default: m.LiveTv })));
const IptvDetails = lazy(() => import("./screens/iptv/IptvDetails").then((m) => ({ default: m.IptvDetails })));
const IptvPlayer = lazy(() => import("./screens/iptv/IptvPlayer").then((m) => ({ default: m.IptvPlayer })));

export function App() {
  const [session, setSessionState] = useState(getSession);
  const route = useRoute();

  useEffect(() => {
    const on = () => setSessionState(getSession());
    window.addEventListener("jellytip-session", on);
    return () => window.removeEventListener("jellytip-session", on);
  }, []);

  useEffect(() => {
    if (route.name !== "play" && route.name !== "iptvplay" && !isRestoring()) window.scrollTo(0, 0);
  }, [route.name, route.params.id]);

  // Phone app started without a connection: the downloads are what works
  useEffect(() => {
    if (isNative && !navigator.onLine && route.name === "home") go("/downloads", true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!session) return <Login />;
  // Discover is for admins; anyone else landing on it (old link, other account) goes Home
  if (route.name === "discover" && !session.isAdmin) {
    go("/home", true);
    return null;
  }
  if (route.name === "iptvplay") {
    const target = parseTarget(route.query);
    if (!target) {
      go("/livetv", true);
      return null;
    }
    return (
      <Suspense fallback={null}>
        <IptvPlayer key={route.query.toString()} target={target} title={route.query.get("t") ?? "Live TV"} resume={Number(route.query.get("resume")) || 0} />
      </Suspense>
    );
  }
  if (route.name === "play")
    return <Player key={route.params.id} id={route.params.id} startParam={route.query.get("start")} thenId={route.query.get("then")} />;

  return (
    <>
      <Nav current={route.name} />
      <TileMenuHost />
      <main>
        {route.name === "home" && <Home />}
        {route.name === "movies" && <Library key="movie" kind="movie" />}
        {route.name === "shows" && <Library key="tv" kind="tv" />}
        {route.name === "search" && <Search initial={route.query.get("q") ?? ""} />}
        {route.name === "downloads" && isNative && <Downloads />}
        {route.name === "person" && <Person key={route.params.id} id={route.params.id} name={route.query.get("name")} />}
        {route.name === "discover" && session.isAdmin && <Discover />}
        {route.name === "collection" && <Collection key={route.params.id} tmdbId={Number(route.params.id)} />}
        {route.name === "item" && <Details key={route.params.id} id={route.params.id} />}
        {route.name === "settings" && <Settings />}
        {route.name === "taste" && <Taste />}
        {route.name === "list" && <ListPage key={route.params.id} slug={route.params.id} />}
        <Suspense fallback={null}>
          {route.name === "iptv" && <IptvSettings />}
          {route.name === "livetv" && <LiveTv />}
          {route.name === "recordings" && <RecordingsPage />}
          {route.name === "iptvitem" && VOD_ID_RE.test(route.query.get("id") ?? "") && <IptvDetails key={route.query.get("id")} id={route.query.get("id")!} />}
        </Suspense>
      </main>
    </>
  );
}
