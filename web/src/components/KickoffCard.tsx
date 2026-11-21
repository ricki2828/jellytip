// Kick-off card (TV port: SportWow.kt KickoffPolicy + JellytipKickoff.kt): when a followed team's game is about to start (2.5 min) or
// has just started, a card says so with Watch, wherever you are in the app, for 15 s. Only for games with no reminder (those get the
// reminder toast) and never for the channel already playing. Mounted once in the app shell next to the reminder toast.
import { useEffect, useMemo, useRef, useState } from "react";
import { hasLiveTv } from "../api/iptv";
import { refreshFixtures, useFixtures } from "../api/fixtures";
import { usePrefs } from "../api/me";
import { useReminders } from "../api/reminderStore";
import { fxTitle, pickChannel } from "../lib/gameCentre";
import { livePath } from "../lib/iptvPlay";
import { isMuted } from "../lib/liveHero";
import { findReminder } from "../lib/reminders";
import { go, useRoute } from "../lib/router";
import { KICKOFF_SHOW_MS, kickoffDue, kickoffHeadline, kickoffKey, type KickoffDue } from "../lib/sportWow";
import { afterStartup } from "../lib/startup";
import "../screens/iptv/iptv.css";

const SEEN_KEY = "jellytip.kickoffSeen";
const readSeen = (): Set<string> => {
  try {
    return new Set(JSON.parse(sessionStorage.getItem(SEEN_KEY) ?? "[]") as string[]);
  } catch {
    return new Set();
  }
};

export function KickoffCard() {
  const [live, setLive] = useState(false);
  useEffect(() => {
    let alive = true;
    void afterStartup()
      .then(() => hasLiveTv())
      .then((ok) => alive && setLive(ok));
    return () => {
      alive = false;
    };
  }, []);
  const { data } = useFixtures(live);
  const { prefs } = usePrefs({ defer: true });
  const { items: reminders } = useReminders();
  const route = useRoute();
  const playing = route.name === "iptvplay" ? route.query.get("channel") : null;
  const [now, setNow] = useState(() => Date.now() / 1000);
  // 15 s steps: "in 2.5 min" is caught within a few seconds of its window opening
  useEffect(() => {
    if (!live) return;
    const t = window.setInterval(() => {
      setNow(Date.now() / 1000);
      void refreshFixtures();
    }, 15_000);
    return () => window.clearInterval(t);
  }, [live]);
  const seen = useRef(readSeen());
  const [card, setCard] = useState<KickoffDue | null>(null);
  const due = useMemo(() => {
    if (!data) return null;
    const muted = prefs?.sport.muted;
    return kickoffDue(data.items, now, seen.current, {
      refs: prefs?.sport.teamRefs ?? [],
      playingChannel: playing,
      hasReminder: (f) => !!findReminder(reminders, { fid: f.fid, matchId: f.matchId }),
      muted: (f) => isMuted(f, muted),
    });
  }, [data, now, prefs, reminders, playing]);
  useEffect(() => {
    if (!due) return;
    seen.current.add(kickoffKey(due));
    if (due.phase === "live") seen.current.add(`${due.fixture.fid}:soon`);
    try {
      sessionStorage.setItem(SEEN_KEY, JSON.stringify([...seen.current].slice(-200)));
    } catch {
      /* fine: this tab only */
    }
    setCard(due);
  }, [due]);
  useEffect(() => {
    if (!card) return;
    const t = window.setTimeout(() => setCard(null), KICKOFF_SHOW_MS);
    return () => window.clearTimeout(t);
  }, [card]);
  if (!card) return null;
  const f = card.fixture;
  const c = pickChannel(f, Date.now() / 1000).channel;
  return (
    <div className="remind-toast kickoff-toast" role="status">
      <span>
        <strong className="kickoff-head">{kickoffHeadline(card, Date.now() / 1000)}</strong> {fxTitle(f)}
      </span>
      {c && (
        <button
          className="btn small primary"
          data-nav
          onClick={() => {
            setCard(null);
            go(livePath(c.id, fxTitle(f), { matchId: f.matchId ?? f.fid, alternates: f.channels.filter((x) => x.id !== c.id).map((x) => ({ id: x.id, name: x.name })) }));
          }}
        >
          Watch
        </button>
      )}
      <button className="btn small" data-nav onClick={() => setCard(null)} aria-label="Dismiss">
        ✕
      </button>
    </div>
  );
}
