// "Your reminders": the Home row, and the toast that nudges at "starting soon" and at kick-off while the app is open.
import { useEffect, useRef, useState } from "react";
import { useReminders } from "../api/reminderStore";
import { livePath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import { NO_STREAM, countdown, dueNudges, hasStream, nudgeText, stateOf, type Nudge } from "../lib/reminders";
import { isNative, NativeReminders } from "../lib/native";
import { COMPANION_BASE } from "../api/companion";
import { getSession } from "../api/jellyfin";
import { UpcomingSheet, watchExtras } from "./Upcoming";
import { SportBadges } from "./SportBadges";
import { artClass, SportArt } from "./SportArt";
import type { UpEvent } from "../lib/upcoming";
import "../screens/iptv/iptv.css";

const watchPath = (e: UpEvent) => livePath(e.channels[0].id, e.channels[0].name, watchExtras(e, e.channels[0].id));

/** Re-render every 30 s so chips and states follow the clock between polls. */
function useNow() {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 30_000);
    return () => clearInterval(t);
  }, []);
  return now;
}

export function RemindersRow() {
  const { items } = useReminders();
  const now = useNow();
  const [open, setOpen] = useState<UpEvent | null>(null);
  const list = (items ?? []).filter((r) => stateOf(r.event, now) !== "ended");
  if (!list.length) return null;
  return (
    <section className="row up-row reminders-row">
      <h2 className="row-title">Your reminders</h2>
      <div className="row-scroll">
        {list.map(({ event: e }) => {
          const chip = countdown(e, now);
          const noStream = !hasStream(e);
          const watchable = !noStream && stateOf(e, now) !== "upcoming";
          const best = e.channels[0];
          return (
            <button
              key={e.key}
              className={`up-tile${chip.tone === "rec" ? " live" : ""}${artClass(e.art)}`}
              data-nav
              onClick={() => (watchable ? go(watchPath(e)) : noStream ? go("/games") : setOpen(e))}
              aria-label={`${e.title}, ${chip.text}`}
              title={e.title}
            >
              <SportArt art={e.art} />
              <span className="up-top">
                <span className={`rec-chip ${chip.tone}`}>{chip.text}</span>
              </span>
              <span className="rec-title">{e.title}</span>
              <span className="rec-sub">{[e.league, noStream ? NO_STREAM : best?.name].filter(Boolean).join(" · ")}</span>
              <SportBadges quality={best?.quality} size="sm" />
            </button>
          );
        })}
      </div>
      {open && <UpcomingSheet e={open} onClose={() => setOpen(null)} />}
    </section>
  );
}

const SHOW_MS = 15_000;

/** Phone app: hand native the token (it schedules the notifications itself), sync on open/resume, open a tapped link. */
function useNativeReminders() {
  useEffect(() => {
    if (!isNative) return;
    const token = getSession()?.token ?? "";
    void NativeReminders.configure({ apiBase: COMPANION_BASE, token }).catch(() => undefined);
    const takeLink = () =>
      NativeReminders.takeDeepLink()
        .then((r) => {
          if (r.deepLink) go(r.deepLink.replace(/^#/, ""));
        })
        .catch(() => undefined);
    const onVisible = () => {
      if (document.visibilityState !== "visible") return;
      void NativeReminders.sync().catch(() => undefined);
      void takeLink();
    };
    void takeLink();
    window.addEventListener("jellytip-native-deeplink", takeLink);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.removeEventListener("jellytip-native-deeplink", takeLink);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);
}

/** Mounted once in the app shell: also what keeps the reminders polled while the app is open. */
export function ReminderNudge() {
  const { items } = useReminders();
  useNativeReminders();
  const now = useNow();
  const seen = useRef(new Set<string>());
  const [nudge, setNudge] = useState<Nudge | null>(null);
  useEffect(() => {
    if (!items?.length) return;
    const due = dueNudges(items, seen.current, now);
    if (due.length) setNudge(due[due.length - 1]);
  }, [items, now]);
  useEffect(() => {
    if (!nudge) return;
    const t = setTimeout(() => setNudge(null), SHOW_MS);
    return () => clearTimeout(t);
  }, [nudge]);
  if (!nudge) return null;
  return (
    <div className="remind-toast" role="status">
      <span>{nudgeText(nudge, Math.floor(Date.now() / 1000))}</span>
      <button
        className="btn small primary"
        data-nav
        onClick={() => {
          setNudge(null);
          go(hasStream(nudge.event) ? watchPath(nudge.event) : "/games");
        }}
      >
        {hasStream(nudge.event) ? "Watch" : "Game Centre"}
      </button>
      <button className="btn small" data-nav onClick={() => setNudge(null)} aria-label="Dismiss">
        ✕
      </button>
    </div>
  );
}
