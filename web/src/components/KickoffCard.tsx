// Kick-off card (TV port: SportWow.kt KickoffPolicy + JellytipKickoff.kt): when a followed team's game is about to start (2.5 min) or
// has just started, a card says so with Watch, wherever you are in the app, for 15 s. Only for games with no reminder (those get the
// reminder toast) and never for the channel already playing. Mounted once in the app shell next to the reminder toast.
import { useEffect, useMemo, useRef, useState } from "react";
import { hasLiveTv } from "../api/iptv";
import { refreshFixtures, useFixtures } from "../api/fixtures";
import { usePrefs } from "../api/me";
import { useReminders } from "../api/reminderStore";
import { fxTitle, isFavourite, pickChannel } from "../lib/gameCentre";
import { getSession } from "../api/jellyfin";
import { livePath } from "../lib/iptvPlay";
import { isMuted } from "../lib/liveHero";
import { findReminder } from "../lib/reminders";
import { go, useRoute } from "../lib/router";
import { KICKOFF_SHOW_MS, kickoffDue, kickoffHeadline, kickoffKey, type KickoffDue } from "../lib/sportWow";
import { afterStartup } from "../lib/startup";
import { NativePlayer, nativeIptvPlayer } from "../lib/native";
import { kickoffNotice } from "../lib/nativePhone";
import "../screens/iptv/iptv.css";

/** Per person: after an account switch in the same tab, the new person's cards are their own. */
const seenKey = () => `jellytip.kickoffSeen.${getSession()?.userId ?? ""}`;
const readSeen = (): Set<string> => {
  try {
    return new Set(JSON.parse(sessionStorage.getItem(seenKey()) ?? "[]") as string[]);
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
  const { items: reminders, available: remindersOn } = useReminders();
  const mountedAt = useRef(Date.now());
  const route = useRoute();
  const playing = route.name === "iptvplay" ? route.query.get("channel") : null;
  const [now, setNow] = useState(() => Date.now() / 1000);
  // The clock steps every 15 s, so "in 2.5 min" is caught within seconds of its window opening. The list itself is asked for again
  // every 15 s only while one of his games kicks off within 10 min, else every 5 min; never while the tab is hidden.
  const dataRef = useRef(data);
  dataRef.current = data;
  const refsRef = useRef(prefs?.sport.teamRefs);
  refsRef.current = prefs?.sport.teamRefs;
  useEffect(() => {
    if (!live) return;
    let last = Date.now();
    const t = window.setInterval(() => {
      if (document.hidden) return;
      const n = Date.now() / 1000;
      setNow(n);
      const near = (dataRef.current?.items ?? []).some((f) => Math.abs(f.kickoff - n) <= 600 && f.status !== "post" && isFavourite(f, refsRef.current ?? []));
      if (near || Date.now() - last >= 5 * 60_000) {
        last = Date.now();
        void refreshFixtures();
      }
    }, 15_000);
    return () => window.clearInterval(t);
  }, [live]);
  const seen = useRef(readSeen());
  const [card, setCard] = useState<KickoffDue | null>(null);
  const due = useMemo(() => {
    // reminders still loading (route there, at most 20 s): a game with a reminder must get the reminder toast, not this card as well
    if (!data || (reminders === null && remindersOn && Date.now() - mountedAt.current < 20_000)) return null;
    const muted = prefs?.sport.muted;
    return kickoffDue(data.items, now, seen.current, {
      refs: prefs?.sport.teamRefs ?? [],
      playingChannel: playing,
      hasReminder: (f) => !!findReminder(reminders, { fid: f.fid, matchId: f.matchId }),
      muted: (f) => isMuted(f, muted),
    });
  }, [data, now, prefs, reminders, remindersOn, playing]);
  useEffect(() => {
    if (!due) return;
    seen.current.add(kickoffKey(due));
    if (due.phase === "live") seen.current.add(`${due.fixture.fid}:soon`);
    try {
      sessionStorage.setItem(seenKey(), JSON.stringify([...seen.current].slice(-200)));
    } catch {
      /* fine: this tab only */
    }
    setCard(due);
    // Phone app: the native player covers this card, so the player shows it as a note (no Watch button there)
    if (nativeIptvPlayer && route.name === "iptvplay") void NativePlayer.notice({ text: kickoffNotice(due, Date.now() / 1000) }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
