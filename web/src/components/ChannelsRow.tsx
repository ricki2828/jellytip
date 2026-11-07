// Home "My Channels": each visible personal channel as a wide card (art of what's on, name, Now + progress, Next + time).
// Click / Enter tunes in. Lazy: nothing is asked before the page's own rows have started (afterStartup), the row stays
// out of the way until a channel is viable, and the now/next text only ticks while the row is on screen and the tab visible.
import { memo, useEffect, useMemo, useRef, useState } from "react";
import { channelItems, readHiddenChannels } from "../api/channels";
import { imageUrl } from "../api/jellyfin";
import { CHANNELS, fromStartTarget, poolOf, schedule, tunePath, type ChanItem } from "../lib/channels";
import { cardModel, visibleChannels } from "../lib/channelsRow";
import { afterStartup } from "../lib/startup";
import { go } from "../lib/router";
import "./FranchiseRow.css";
import "./ChannelsRow.css";

const TICK_MS = 45_000;
const CONCURRENCY = 2;
const clock = (ms: number) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });

const Card = memo(function Card({ id, items, now }: { id: string; items: ChanItem[]; now: number }) {
  const def = CHANNELS.find((c) => c.id === id)!;
  const m = useMemo(() => cardModel(def, items, now), [def, items, now]);
  const [bad, setBad] = useState<string | null>(null);
  if (!m) return null;
  const art = m.artUrl ?? (m.artId ? imageUrl(m.artId, "Backdrop", { maxWidth: 640, quality: 70 }) : null);
  const pct = Math.round(m.progress * 100);
  const fromStart = () => {
    const t = fromStartTarget(schedule(poolOf(items, def), def.id, Date.now(), 2).slots); // the clock, not the card's last tick
    if (t) go(tunePath(t, def.id, true));
  };
  return (
    <div className="chrow-wrap">
    <button
      className="chrow-card"
      data-nav
      onClick={() => go(tunePath(cardModel(def, items, Date.now())?.target ?? m.target, def.id))} // the clock, not the card's last tick
      aria-label={`${def.name}. Now: ${m.nowTitle}, ${pct}% through.${m.next ? ` Next: ${m.next.title} at ${clock(m.next.atMs)}.` : ""} Tune in`}
    >
      {art && bad !== art && <img className="chrow-art" src={art} alt="" loading="lazy" decoding="async" onError={() => setBad(art)} />}
      <span className="chrow-shade" aria-hidden />
      <span className="chrow-name">{def.name}</span>
      <span className="chrow-now">
        <span className="chrow-label">Now</span> {m.nowTitle}
      </span>
      {m.next && (
        <span className="chrow-next">
          Next: {m.next.title} at {clock(m.next.atMs)}
        </span>
      )}
      <span className="onnow-progress" aria-hidden>
        <span style={{ width: `${pct}%` }} />
      </span>
    </button>
    <button className="chrow-start" data-nav onClick={fromStart} aria-label={`${def.name}: play ${m.nowTitle} from the start`}>
      From start
    </button>
    </div>
  );
});

export function ChannelsRow() {
  const [loads, setLoads] = useState<Record<string, ChanItem[] | undefined>>({});
  const [hidden, setHidden] = useState<string[]>(readHiddenChannels);
  const [now, setNow] = useState(() => Date.now());
  const [inView, setInView] = useState(false);
  const el = useRef<HTMLElement>(null);

  // Load after startup, a couple of channels at a time (cached for hours, so this is usually no request). Hidden channels are not asked for.
  useEffect(() => {
    let alive = true;
    let gen = 0;
    const run = async () => {
      const mine = ++gen;
      await afterStartup();
      if (!alive || mine !== gen) return;
      const hid = readHiddenChannels();
      setHidden(hid);
      const queue = CHANNELS.filter((c) => !hid.includes(c.id));
      const worker = async () => {
        for (let def = queue.shift(); def && alive && mine === gen; def = queue.shift()) {
          const d = def;
          await channelItems(d).then(
            (items) => alive && mine === gen && setLoads((l) => ({ ...l, [d.id]: items })),
            () => undefined, // a channel that can't load is just not shown
          );
        }
      };
      await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    };
    void run();
    // Another person: drop the last one's channels and load this person's
    const onSession = () => {
      setLoads({});
      void run();
    };
    window.addEventListener("jellytip-session", onSession);
    return () => {
      alive = false;
      window.removeEventListener("jellytip-session", onSession);
    };
  }, []);

  const shown = useMemo(() => visibleChannels(loads, hidden), [loads, hidden]);
  const present = shown.length > 0;

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

  if (!present) return null;
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
        {shown.map((c) => (
          <Card key={c.id} id={c.id} items={loads[c.id]!} now={now} />
        ))}
      </div>
    </section>
  );
}
