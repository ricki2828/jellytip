import { useEffect, useState } from "react";
import { getSession } from "./api/jellyfin";
import { useRoute } from "./lib/router";
import { Nav } from "./components/Nav";
import { Login } from "./screens/Login";
import { Home } from "./screens/Home";
import { Library } from "./screens/Library";
import { Details } from "./screens/Details";
import { Player } from "./screens/Player";
import { Search } from "./screens/Search";

export function App() {
  const [session, setSessionState] = useState(getSession);
  const route = useRoute();

  useEffect(() => {
    const on = () => setSessionState(getSession());
    window.addEventListener("jellytip-session", on);
    return () => window.removeEventListener("jellytip-session", on);
  }, []);

  useEffect(() => {
    if (route.name !== "play") window.scrollTo(0, 0);
  }, [route.name, route.params.id]);

  if (!session) return <Login />;
  if (route.name === "play") return <Player key={route.params.id} id={route.params.id} startParam={route.query.get("start")} />;

  return (
    <>
      <Nav current={route.name} />
      <main>
        {route.name === "home" && <Home />}
        {route.name === "movies" && <Library key="movie" kind="movie" />}
        {route.name === "shows" && <Library key="tv" kind="tv" />}
        {route.name === "search" && <Search initial={route.query.get("q") ?? ""} />}
        {route.name === "item" && <Details key={route.params.id} id={route.params.id} />}
      </main>
    </>
  );
}
