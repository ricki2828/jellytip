// #/channels: personal scheduled channels. Each is a deterministic lineup from the person's own library, so "on now" is
// the same whenever they tune in. Schedule maths in lib/channels.ts; queries in api/channels.ts.
import { useEffect, useMemo, useRef, useState } from "react";
import { channelItems, readHiddenChannels, writeHiddenChannels } from "../api/channels";
import { CHANNELS, channelViable, fromStartTarget, itemLabel, poolOf, schedule, toggleHidden, tuneTarget, tunePath, type ChanItem, type ChannelDef } from "../lib/channels";
import { go } from "../lib/router";
import "./Channels.css";

type Load = { state: "loading" } | { state: "error" } | { state: "ok"; items: ChanItem[] };

const clock = (ms: number) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });

function Card({ def, items, now, onHide, hidden }: { def: ChannelDef; items: ChanItem[]; now: number; onHide: () => void; hidden: boolean }) {
  const pool = useMemo(() => poolOf(items, def), [items, def]);
  const { slots, offsetS, progress } = schedule(pool, def.id, now, 4);
  const target = tuneTarget(slots, offsetS);
  if (!slots.length || !target) return null;
  const cur = itemLabel(slots[0].item);
  return (
    <li className={`ch-card${hidden ? " hidden" : ""}`}>
      <div className="ch-head">
        <h2 className="ch-name">{def.name}</h2>
        <span className="ch-blurb">{def.blurb}</span>
      </div>
      <div className="ch-now">
        <span className="ch-chip">On now</span>
        <p className="ch-title">{cur.title}</p>
        {cur.sub && <p className="ch-sub">{cur.sub}</p>}
        <div className="ch-bar" role="progressbar" aria-label={`${def.name}: ${cur.title}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)}>
          <span style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
        <p className="ch-times">
          {clock(slots[0].startMs)} to {clock(slots[0].endMs)}
        </p>
      </div>
      <ul className="ch-next">
        {slots.slice(1).map((s) => (
          <li key={`${s.item.Id}-${s.startMs}`}>
            <span className="ch-at">{clock(s.startMs)}</span>
            <span className="ch-nt">{itemLabel(s.item).title}</span>
          </li>
        ))}
      </ul>
      <div className="ch-actions">
        <button className="btn primary small" data-nav onClick={() => go(tunePath(target, def.id))}>
          Tune in
        </button>
        <button className="btn small" data-nav onClick={() => { const t = fromStartTarget(slots); if (t) go(tunePath(t, def.id, true)); }} aria-label={`${def.name}: play ${cur.title} from the start`}>
          From start
        </button>
        <button className="btn small" data-nav onClick={onHide} aria-label={`${hidden ? "Show" : "Hide"} ${def.name}`}>
          {hidden ? "Show" : "Hide"}
        </button>
      </div>
    </li>
  );
}

export function Channels() {
  const [loads, setLoads] = useState<Record<string, Load>>(() => Object.fromEntries(CHANNELS.map((c) => [c.id, { state: "loading" } as Load])));
  const [hidden, setHidden] = useState<string[]>(readHiddenChannels);
  const [showHidden, setShowHidden] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [attempt, setAttempt] = useState(0);
  const failed = useRef(new Set<string>());

  useEffect(() => {
    const prev = document.title;
    document.title = "Channels";
    const t = setInterval(() => setNow(Date.now()), 20_000);
    return () => {
      document.title = prev;
      clearInterval(t);
    };
  }, []);

  useEffect(() => {
    let alive = true;
    for (const def of CHANNELS) {
      channelItems(def, attempt > 0 && failed.current.has(def.id)).then(
        (items) => {
          if (!alive) return;
          failed.current.delete(def.id);
          setLoads((l) => ({ ...l, [def.id]: { state: "ok", items } }));
        },
        () => {
          if (!alive) return;
          failed.current.add(def.id);
          setLoads((l) => ({ ...l, [def.id]: { state: "error" } }));
        },
      );
    }
    return () => {
      alive = false;
    };
  }, [attempt]);

  const toggle = (id: string) => {
    const next = toggleHidden(hidden, id);
    setHidden(next);
    writeHiddenChannels(next);
  };

  const viable = CHANNELS.filter((c) => {
    const l = loads[c.id];
    return l.state === "ok" && channelViable(l.items, c);
  });
  const pending = CHANNELS.some((c) => loads[c.id].state === "loading");
  const errored = CHANNELS.filter((c) => loads[c.id].state === "error").length;
  const visible = viable.filter((c) => !hidden.includes(c.id));
  const hiddenViable = viable.filter((c) => hidden.includes(c.id));
  const shown = showHidden ? viable : visible;

  return (
    <div className="channels">
      <h1 className="page-title">Channels</h1>
      <p className="ask-sub">Always-on channels made from your own library. What's on is the same whenever you tune in (on devices that see the same library), and the order is reshuffled after each full run through a channel.</p>
      {errored > 0 && !pending && (
        <p className="ask-sub" role="status">
          Couldn't load {errored === CHANNELS.length ? "your library" : "some channels"}.{" "}
          <button className="btn small" data-nav data-autofocus onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        </p>
      )}
      {pending && shown.length === 0 && <p className="ask-sub" role="status">Looking…</p>}
      {!pending && errored === 0 && viable.length === 0 && <p className="ask-sub" role="status">Not enough in your library yet to make a channel (each needs at least 3 titles).</p>}
      <ul className="ch-list">
        {shown.map((c) => {
          const l = loads[c.id];
          return l.state === "ok" ? <Card key={c.id} def={c} items={l.items} now={now} hidden={hidden.includes(c.id)} onHide={() => toggle(c.id)} /> : null;
        })}
      </ul>
      {hiddenViable.length > 0 && (
        <button className="btn small ch-toggle" data-nav aria-pressed={showHidden} onClick={() => setShowHidden((v) => !v)}>
          {showHidden ? "Hide the hidden ones" : `Hidden channels (${hiddenViable.length})`}
        </button>
      )}
    </div>
  );
}
