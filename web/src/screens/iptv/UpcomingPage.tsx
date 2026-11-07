// #/upcoming: sport coming up in the next days, grouped by day, favourites first inside each day.
import { useEffect, useState } from "react";
import { refreshUpcoming, useUpcoming } from "../../api/upcomingStore";
import { UpcomingSheet, UpTile } from "../../components/Upcoming";
import { go } from "../../lib/router";
import { groupByDay, starredFirst, type UpEvent } from "../../lib/upcoming";
import "./iptv.css";

export function UpcomingPage() {
  const ev = useUpcoming(false);
  const [open, setOpen] = useState<UpEvent | null>(null);
  useEffect(() => void refreshUpcoming(true), []);
  // Starred first within each day (live before upcoming, then by start)
  const days = ev ? groupByDay(ev).map((d) => ({ ...d, events: starredFirst(d.events, Date.now() / 1000) })) : [];
  return (
    <div className="iptv-page up-page">
      <div className="page-head">
        <h1 className="page-title">Upcoming sport</h1>
        <button className="btn small" data-nav onClick={() => go("/games")}>Game Centre</button>
        <button className="btn small" data-nav onClick={() => go("/reminders")}>Reminders</button>
      </div>
      {ev === null && <p className="iptv-hint">Loading…</p>}
      {ev && days.length === 0 && <p className="iptv-hint">Nothing coming up in the guide.</p>}
      {days.map((d) => (
        <section key={d.day} className="row">
          <h2 className="row-title">{d.label}</h2>
          <div className="up-grid">
            {d.events.map((e) => (
              <UpTile key={e.key} e={e} onOpen={() => setOpen(e)} />
            ))}
          </div>
        </section>
      ))}
      {open && <UpcomingSheet e={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
