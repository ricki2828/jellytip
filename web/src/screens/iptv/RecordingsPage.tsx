// #/recordings: every recording with play / cancel / delete (the same list as in IPTV settings), one tap from the nav.
import { useEffect } from "react";
import { refreshRecs } from "../../api/recStore";
import { BackArrow } from "../../components/BackArrow";
import { Recordings } from "./Recordings";
import "./iptv.css";

export function RecordingsPage() {
  useEffect(() => {
    void refreshRecs(true);
    // Leaving re-reads the list (Home's row and the nav badge follow what was changed here)
    return () => void refreshRecs(true);
  }, []);
  return (
    <div className="iptv-page">
      <div className="page-head">
        <BackArrow />
        <h1 className="page-title">Recordings</h1>
      </div>
      <Recordings />
    </div>
  );
}
