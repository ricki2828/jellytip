// Home "My Channels" (Ricki 23-Nov, TV 268dcba9): directly under Continue watching, the person's channels in the My Channels page's
// order, numbered from 101 as on the TV. Each tile: number + name, what's on now, "sub · time left" and "Next HH:MM · title".
// Tap / Enter joins the channel; hold (a touch long-press, or a right click; a mouse held down is still a click) plays what is on from the start; "From start" stays as a
// chip where there is a mouse. Hidden channels stay hidden. A channel that fails to load is asked again with backoff (never
// remembered as missing); the loaded channels are kept per person for the session so Back paints the same row at once, and a
// person who had channels last time gets the row's space reserved while it loads (nothing below it jumps).
import { memo, useEffect, useMemo, useRef, useState } from "react";
import { channelItems, commitChannelOrder, peekChannelOrder, readHiddenChannels, rememberPersonal } from "../api/channels";
import { imageUrl, uid } from "../api/jellyfin";
import { CHANNELS, fromStartTarget, poolOf, schedule, tunePath, type ChanItem, type ChannelDef } from "../lib/channels";
import { cardModel, channelNumber, channelRetryMs, nextLine, orderByAffinity, orderForView, subLine, visibleChannels } from "../lib/channelsRow";
import { personalDefs, personalItems } from "../lib/personalChannels";
import { tasteProfileSoon } from "../api/tasteProfile";
import type { TasteProfile } from "../lib/tasteProfile";
import { afterStartup } from "../lib/startup";
import { go } from "../lib/router";
import "./FranchiseRow.css";
import "./ChannelsRow.css";

const TICK_MS = 30_000;
const CONCURRENCY = 2;
const HOLD_MS = 550;

/** What the row last showed, per person (Back paints it at once; channelItems keeps the pools themselves for hours). */
let rowMemo: { uid: string; loads: Record<string, ChanItem[] | undefined>; profile: TasteProfile | null; answered: boolean; profileKnown: boolean } | null = null;
if (typeof window !== "undefined") window.addEventListener("jellytip-session", () => (rowMemo = null));
const presentKey = () => `jellytip.mychannels.present.${uid()}`;
const hadChannels = () => {
  try {
    return localStorage.getItem(presentKey()) === "1";
  } catch {
    return false;
  }
};
const rememberPresent = (yes: boolean) => {
  try {
    if (yes) localStorage.setItem(presentKey(), "1");
    else localStorage.removeItem(presentKey());
  } catch {
    // private mode: no reservation next time
  }
};

const Card = memo(function Card({ def, items, now, number }: { def: ChannelDef; items: ChanItem[]; now: number; number: number }) {
  const m = useMemo(() => cardModel(def, items, now), [def, items, now]);
  const [bad, setBad] = useState<string | null>(null);
  const hold = useRef<{ t: number; fired: boolean } | null>(null);
  if (!m) return null;
  const art = m.artUrl ?? (m.artId ? imageUrl(m.artId, "Backdrop", { maxWidth: 640, quality: 70 }) : null);
  const pct = Math.round(m.progress * 100);
  const join = () => go(tunePath(cardModel(def, items, Date.now())?.target ?? m.target, def.id)); // the clock, not the card's last tick
  const fromStart = (): boolean => {
    const t = fromStartTarget(schedule(poolOf(items, def), def.id, Date.now(), 2).slots);
    if (t) go(tunePath(t, def.id, true));
    return !!t;
  };
  const endHold = () => {
    if (hold.current) window.clearTimeout(hold.current.t);
  };
  const next = nextLine(m.next);
  return (
    <div className="chrow-wrap">
      <button
        className="chrow-card"
        data-nav
        onPointerDown={(e) => {
          endHold();
          hold.current = null; // a new press: whatever a contextmenu that went nowhere left behind is gone
          // only a finger's long-press is a hold; a mouse held down is still a click (right click = contextmenu below)
          if (e.pointerType !== "touch" || e.button !== 0) return;
          const h = { t: 0, fired: false };
          h.t = window.setTimeout(() => {
            h.fired = fromStart();
          }, HOLD_MS);
          hold.current = h;
        }}
        onPointerUp={endHold}
        onPointerLeave={endHold}
        onPointerCancel={endHold}
        onContextMenu={(e) => {
          // Android's long-press and a right click: from the start (once, even if the hold timer already fired)
          e.preventDefault();
          if (!hold.current?.fired) {
            endHold();
            // swallow the click a touch long-press may still send, but only if it went somewhere
            hold.current = fromStart() ? { t: 0, fired: true } : null;
          }
        }}
        onClick={() => {
          if (hold.current?.fired) {
            hold.current = null; // the hold already played it from the start
            return;
          }
          endHold();
          join();
        }}
        aria-label={`${number} ${def.name}. Now: ${m.nowTitle}, ${subLine(m, now)}.${next ? ` ${next}.` : ""} Join. Hold to play from the start`}
      >
        {art && bad !== art && <img className="chrow-art" src={art} alt="" loading="lazy" decoding="async" draggable={false} onError={() => setBad(art)} />}
        <span className="chrow-shade" aria-hidden />
        <span className="chrow-head">
          <span className="chrow-num">{number}</span>
          <span className="chrow-name">{def.name}</span>
        </span>
        <span className="chrow-foot">
          <span className="chrow-now">{m.nowTitle}</span>
          <span className="chrow-sub">{subLine(m, now)}</span>
          {next && <span className="chrow-next">{next}</span>}
        </span>
        <span className="onnow-progress" aria-hidden>
          <span style={{ width: `${pct}%` }} />
        </span>
      </button>
      <button className="chrow-start" data-nav onClick={() => void fromStart()} aria-label={`${def.name}: play ${m.nowTitle} from the start`}>
        From start
      </button>
    </div>
  );
});

export function ChannelsRow() {
  const mine0 = rowMemo && rowMemo.uid === uid() ? rowMemo : null;
  const [loads, setLoads] = useState<Record<string, ChanItem[] | undefined>>(() => mine0?.loads ?? {});
  const [hidden, setHidden] = useState<string[]>(readHiddenChannels);
  const [profile, setProfile] = useState<TasteProfile | null>(() => mine0?.profile ?? null);
  const [done, setDone] = useState(!!mine0);
  /** Every channel has answered once (loaded or failed): the first load has settled. */
  const [answered, setAnswered] = useState(!!mine0?.answered);
  /** The taste profile request has answered (with a profile or none): personal channels are known. */
  const [profileKnown, setProfileKnown] = useState(!!mine0?.profileKnown);
  const [reserve, setReserve] = useState(() => !mine0 && hadChannels());
  useEffect(() => {
    if (!reserve) return;
    const t = window.setTimeout(() => setReserve(false), 8_000); // never a lasting hole when channels keep failing
    return () => window.clearTimeout(t);
  }, [reserve]);
  const [now, setNow] = useState(() => Date.now());
  const [inView, setInView] = useState(false);
  const el = useRef<HTMLElement>(null);

  // Load after startup, a couple of channels at a time (cached for hours, so this is usually no request). Hidden channels are
  // not asked for. A failure is retried with backoff while Home is up, paused in a hidden tab.
  useEffect(() => {
    let alive = true;
    let gen = 0;
    const timers = new Set<number>();
    const run = async () => {
      const mine = ++gen;
      await afterStartup();
      if (!alive || mine !== gen) return;
      const hid = readHiddenChannels();
      setHidden(hid);
      void tasteProfileSoon().then((a) => {
        if (!alive || mine !== gen) return;
        setProfile(a.profile);
        setProfileKnown(true);
      });
      const failures = new Map<string, number>();
      const loadOne = (d: ChannelDef): Promise<void> =>
        channelItems(d).then(
          (items) => void (alive && mine === gen && setLoads((l) => ({ ...l, [d.id]: items }))),
          () => {
            if (!alive || mine !== gen) return;
            const n = (failures.get(d.id) ?? 0) + 1;
            failures.set(d.id, n);
            const retry = () => {
              if (!alive || mine !== gen) return;
              if (document.hidden) {
                const vis = () => {
                  if (document.hidden) return;
                  document.removeEventListener("visibilitychange", vis);
                  void loadOne(d);
                };
                document.addEventListener("visibilitychange", vis);
                return;
              }
              void loadOne(d);
            };
            const t = window.setTimeout(() => (timers.delete(t), retry()), channelRetryMs(n));
            timers.add(t);
          },
        );
      const queue = CHANNELS.filter((c) => !hid.includes(c.id));
      const worker = async () => {
        for (let def = queue.shift(); def && alive && mine === gen; def = queue.shift()) await loadOne(def);
      };
      await Promise.all(Array.from({ length: CONCURRENCY }, worker));
      if (alive && mine === gen) setAnswered(true);
      // "done" = every channel loaded; while some are failing (retrying) the reservation and the remembered presence stay
      if (alive && mine === gen && failures.size === 0) setDone(true);
    };
    void run();
    // Another person: drop the last one's channels and load this person's
    const onSession = () => {
      setLoads({});
      setProfile(null);
      setDone(false);
      setAnswered(false);
      setProfileKnown(false);
      void run();
    };
    window.addEventListener("jellytip-session", onSession);
    return () => {
      alive = false;
      for (const t of timers) window.clearTimeout(t);
      window.removeEventListener("jellytip-session", onSession);
    };
  }, []);

  // Personal channels are cut from the loaded pools (no requests of their own). Same channels and order as the My Channels page
  // (screens/Channels.tsx: [...personal, ...CHANNELS], viable, ordered by affinity, hidden ones out); ordered once per load, not
  // on every tick, so tiles don't jump while someone is looking
  const personal = useMemo(() => personalDefs(profile, loads), [profile, loads]);
  useEffect(() => {
    if (profile) rememberPersonal(personal);
  }, [profile, personal]);
  const allLoads = useMemo(() => {
    const m: Record<string, ChanItem[] | undefined> = { ...loads };
    for (const d of personal) m[d.id] = personalItems(d, loads, d.genres?.[0]);
    return m;
  }, [loads, personal]);
  // Ranked by affinity only to place channels new to the session; placed ones keep their places and numbers (on Back, after a
  // film, when a late channel or a retry lands), the same order and numbers as the My Channels page (api/channels sessionChannelOrder)
  const ranked = useMemo(() => orderByAffinity(visibleChannels(allLoads, hidden, [...personal, ...CHANNELS]), allLoads, Date.now()), [allLoads, hidden, personal]);
  const [orderVer, setOrderVer] = useState(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const shown = useMemo(() => orderForView(peekChannelOrder(), ranked), [ranked, orderVer]);
  // Commit the order once the first load has settled (every channel answered, the taste profile known), then for each late one
  useEffect(() => {
    if (answered && profileKnown && ranked.length && commitChannelOrder(ranked)) setOrderVer((v) => v + 1);
  }, [answered, profileKnown, ranked]);
  const present = shown.length > 0;
  useEffect(() => {
    rowMemo = { uid: uid(), loads, profile, answered, profileKnown };
  }, [loads, profile, answered, profileKnown]);
  useEffect(() => {
    if (present) rememberPresent(true);
    else if (done) rememberPresent(false);
  }, [present, done]);

  // On screen? (no observer API: assume yes)
  useEffect(() => {
    const node = el.current;
    if (!present || !node) return;
    if (typeof IntersectionObserver === "undefined") return setInView(true);
    const io = new IntersectionObserver((es) => setInView(es.some((e) => e.isIntersecting)));
    io.observe(node);
    return () => {
      io.disconnect();
      setInView(false);
    };
  }, [present]);

  // Now/next follow the clock only while visible: refresh on becoming visible, then every TICK_MS
  useEffect(() => {
    if (!inView) return;
    setNow(Date.now());
    const t = setInterval(() => !document.hidden && setNow(Date.now()), TICK_MS);
    const vis = () => !document.hidden && setNow(Date.now());
    document.addEventListener("visibilitychange", vis);
    return () => {
      clearInterval(t);
      document.removeEventListener("visibilitychange", vis);
    };
  }, [inView]);

  if (!present) {
    // Had channels last time: hold the row's place until they load (or turn out to be gone), so the rows below don't jump
    return reserve && !done ? <section className="row chrow chrow-reserve" aria-hidden /> : null;
  }
  return (
    <section className="row chrow" ref={el}>
      <h2 className="row-title">
        <button className="row-title-link" data-nav onClick={() => go("/channels")}>
          My Channels
          <span className="row-title-more" aria-hidden>
            ›
          </span>
        </button>
      </h2>
      <div className="row-scroll">
        {shown.map((c, i) => (
          <Card key={c.id} def={c} items={allLoads[c.id]!} now={now} number={channelNumber(i)} />
        ))}
      </div>
    </section>
  );
}
