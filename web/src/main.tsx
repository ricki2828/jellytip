import { createRoot } from "react-dom/client";
import { App } from "./App";
import { installSpatialNav } from "./lib/spatial";
import "./styles.css";

installSpatialNav();
createRoot(document.getElementById("root")!).render(<App />);
