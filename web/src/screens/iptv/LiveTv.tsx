// Live TV guide (#/livetv): opted-in groups as chips, then an EPG grid: channel column (logo, number, name), half-hour
// ruler, now-line, a ~3 h window (2 h on a phone) that steps by an hour. Only the rows on screen are rendered and only
// their guide data is fetched. The programme on now plays; others open a small sheet (catch-up, record).
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { addRecording, epg, errorText, liveChannels, liveGroups, logoSrc, setFavourite, type Channel, type LiveGroup } from "../../api/iptv";
import { chunks, layoutRow, ticks, visibleRows, windowStart, type Prog } from "../../lib/epg";
import { targetPath, type PlayTarget } from "../../lib/iptvPlay";
import { useIsPhone } from "../../lib/device";
import { go } from "../../lib/router";
import "./iptv.css";

const ROW_H = 56;
const GROUP_KEY = "jellytip.livetv.group";
const MAX_CHANNELS = 2000;
const EPG_CHUNK = 100;

const clock = (t: number) => new Date(t * 1000).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
/** Compact time for programme blocks: no AM/PM. */
const short = (t: number) => clock(t).replace(/\s?[ap]\.?m\.?$/i, "");
const nowSec = () => Math.floor(Date.now() / 1000);

/** Companion-proxied logo; initials when there is none or it fails (404 = no image at the panel). */
function ChannelLogo({ ch }: { ch: Channel }) {
  const src = logoSrc(ch.logo);
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <span className="livetv-logo-blank">{ch.name.replace(/^[A-Z]{2,3}\s*[|:-]\s*/, "").slice(0, 2)}</span>;
  return <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}

interface Sheet {
  ch: Channel;
  prog: Prog;
}

export function LiveTv() {
  const phone = useIsPhone();
  const hours = phone ? 2 : 3;
  const chanW = phone ? 76 : 220;
  const [groups, setGroups] = useState<LiveGroup[] | null>(null);
  const [group, setGroup] = useState<string | null>(() => localStorage.getItem(GROUP_KEY));
  const [channels, setChannels] = useState<Channel[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [now, setNow] = useState(nowSec);
  const [from, setFrom] = useState(() => windowStart(nowSec()));
  const to = from + hours * 3600;
  const [guide, setGuide] = useState<Record<string, Prog[]>>({});
  const asked = useRef<Set<string>>(new Set());
  const scroller = useRef<HTMLDivElement>(null);
  const [range, setRange] = useState<[number, number]>([0, 20]);
  const [width, setWidth] = useState(0);
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  // ---- data ----
  useEffect(() => {
    liveGroups().then((a) => {
      if (a.status !== 200 || !a.data) return setErr(errorText(a));
      const gs = a.data.groups.filter((g) => g.count > 0);
      setGroups(gs);
      setGroup((cur) => (cur && gs.some((g) => g.id === cur) ? cur : gs[0]?.id ?? null));
    });
  }, []);

  useEffect(() => {
    if (!group) return;
    localStorage.setItem(GROUP_KEY, group);
    let alive = true;
    setChannels(null);
    setGuide({});
    asked.current = new Set();
    scroller.current?.scrollTo(0, 0);
    (async () => {
      const all: Channel[] = [];
      for (let off = 0; off < MAX_CHANNELS; off += 500) {
        const a = await liveChannels(group, off, 500);
        if (!alive) return;
        if (a.status !== 200 || !a.data) return setErr(errorText(a));
        all.push(...a.data.channels);
        if (all.length >= a.data.total || a.data.channels.length === 0) break;
      }
      setChannels(all);
    })();
    return () => {
      alive = false;
    };
  }, [group]);

  // A new window needs new guide data
  useEffect(() => {
    setGuide({});
    asked.current = new Set();
  }, [from, hours]);

  // Guide for the rows on screen (plus overscan), in chunks the server accepts
  useEffect(() => {
    if (!channels) return;
    const want = channels.slice(range[0], range[1]).map((c) => c.id).filter((id) => !asked.current.has(id));
    if (!want.length) return;
    const t = setTimeout(() => {
      want.forEach((id) => asked.current.add(id));
      for (const part of chunks(want, EPG_CHUNK))
        epg(part, from, to).then((a) => {
          if (a.status === 200 && a.data && a.data.from === from) setGuide((g) => ({ ...g, ...a.data!.channels }));
          else part.forEach((id) => asked.current.delete(id));
        });
    }, 120);
    return () => clearTimeout(t);
  }, [channels, range, from, to]);

  // ---- layout ----
  const onScroll = useCallback(() => {
    const el = scroller.current;
    if (!el || !channels) return;
    const r = visibleRows(Math.max(0, el.scrollTop - ROW_H), el.clientHeight, ROW_H, channels.length);
    setRange((cur) => (cur[0] === r[0] && cur[1] === r[1] ? cur : r));
  }, [channels]);
  useEffect(() => {
    onScroll();
  }, [onScroll]);
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      setWidth(el.clientWidth);
      onScroll();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [onScroll, channels !== null]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const t = setInterval(() => setNow(nowSec()), 30_000);
    return () => clearInterval(t);
  }, []);

  const trackW = Math.max(300, width - chanW);
  const pps = trackW / (to - from);
  const rows = useMemo(() => (channels ?? []).slice(range[0], range[1]), [channels, range]);

  // ---- actions ----
  const playChannel = (ch: Channel, t: Partial<PlayTarget> = {}, label = ch.name) => go(`${targetPath({ channel: ch.id, ...t })}&t=${encodeURIComponent(label)}`);
  const open = (ch: Channel, p: Prog) => {
    if (p.start <= now && now < p.stop) playChannel(ch);
    else setSheet({ ch, prog: p });
  };
  const record = async (s: Sheet) => {
    const a = await addRecording(s.ch.id, s.prog.start);
    setSheet(null);
    setToast(a.status === 201 || a.status === 200 ? a.data?.warning || `Recording “${s.prog.title}”.` : errorText(a));
    window.setTimeout(() => setToast(null), 5000);
  };
  const fav = async (ch: Channel) => {
    const a = await setFavourite(ch.id, !ch.favourite);
    if (a.status === 200) setChannels((cs) => cs?.map((c) => (c.id === ch.id ? { ...c, favourite: !c.favourite } : c)) ?? null);
  };

  const nowX = (now - from) * pps;
  const empty = groups && groups.length === 0;

  return (
    <div className="livetv">
      <div className="livetv-head">
        <h1 className="page-title">Live TV</h1>
        <div className="livetv-window">
          <button className="btn small" data-nav onClick={() => setFrom((f) => f - 3600)} aria-label="Earlier">
            ‹
          </button>
          <button className="btn small" data-nav onClick={() => setFrom(windowStart(nowSec()))}>
            Now
          </button>
          <button className="btn small" data-nav onClick={() => setFrom((f) => f + 3600)} aria-label="Later">
            ›
          </button>
        </div>
      </div>
      {groups && groups.length > 1 && (
        <div className="chips livetv-groups" role="tablist" aria-label="Channel groups">
          {groups.map((g) => (
            <button key={g.id} className={`chip${g.id === group ? " on" : ""}`} data-nav role="tab" aria-selected={g.id === group} onClick={() => setGroup(g.id)}>
              {g.name}
              <span className="livetv-count">{g.count}</span>
            </button>
          ))}
        </div>
      )}
      {err && <p className="iptv-err iptv-pad">{err}</p>}
      {empty && (
        <p className="iptv-hint iptv-pad">
          No channels picked yet. <a href="#/iptv">Pick channel groups in Settings → Live TV / IPTV</a>.
        </p>
      )}
      {!err && !empty && (
        <div className="livetv-grid" ref={scroller} onScroll={onScroll} style={{ ["--chan-w" as string]: `${chanW}px`, ["--row-h" as string]: `${ROW_H}px` }}>
          <div className="livetv-ruler" style={{ width: chanW + trackW }}>
            <div className="livetv-corner">{new Date(from * 1000).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })}</div>
            <div className="livetv-ticks" style={{ width: trackW }}>
              {ticks(from, to).map((t) => (
                <span key={t} style={{ left: (t - from) * pps }}>
                  {clock(t)}
                </span>
              ))}
              {nowX >= 0 && nowX <= trackW && <span className="livetv-now-cap" style={{ left: nowX }} aria-hidden />}
            </div>
          </div>
          {channels === null && <p className="iptv-hint iptv-pad">Loading channels…</p>}
          {channels && (
            <div className="livetv-body" style={{ height: channels.length * ROW_H, width: chanW + trackW }}>
              {nowX >= 0 && nowX <= trackW && <div className="livetv-now" style={{ left: chanW + nowX }} aria-hidden />}
              {rows.map((ch, i) => {
                const idx = range[0] + i;
                const progs = guide[ch.id];
                const blocks = progs ? layoutRow(progs, from, to, pps, now) : [];
                return (
                  <div key={ch.id} className="livetv-row" style={{ top: idx * ROW_H }}>
                    <button className="livetv-chan" data-nav onClick={() => playChannel(ch)} onContextMenu={(e) => (e.preventDefault(), void fav(ch))} title={`${ch.name} · play`}>
                      <ChannelLogo ch={ch} />
                      {!phone && (
                        <span className="livetv-chan-text">
                          {ch.num != null && <span className="livetv-num">{ch.num}</span>}
                          <span className="livetv-chan-name">{ch.name}</span>
                        </span>
                      )}
                      {ch.favourite && <span className="livetv-fav" aria-label="favourite">★</span>}
                    </button>
                    <div className="livetv-track" style={{ width: trackW }}>
                      {progs && blocks.length === 0 && <span className="livetv-noguide">No guide</span>}
                      {!progs && <span className="livetv-loading" />}
                      {blocks.map((b) => (
                        <button
                          key={b.prog.start}
                          className={`livetv-prog${b.live ? " live" : ""}${b.past ? " past" : ""}${b.clipped ? " clipped" : ""}`}
                          style={{ left: b.left, width: b.width }}
                          data-nav
                          onClick={() => open(ch, b.prog)}
                          title={`${b.prog.title} · ${clock(b.prog.start)}–${clock(b.prog.stop)}`}
                        >
                          <span className="livetv-prog-title">{b.prog.title}</span>
                          {b.width > 90 && (
                            <span className="livetv-prog-time">
                              {short(b.prog.start)}–{short(b.prog.stop)}
                              {b.prog.catchup ? " · catch-up" : ""}
                            </span>
                          )}
                        </button>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
      {sheet && (
        <div className="livetv-sheet-back" onClick={() => setSheet(null)}>
          <div className="livetv-sheet" role="dialog" aria-label={sheet.prog.title} onClick={(e) => e.stopPropagation()}>
            <p className="iptv-hint">
              {sheet.ch.name} · {new Date(sheet.prog.start * 1000).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })} {clock(sheet.prog.start)}–{clock(sheet.prog.stop)}
            </p>
            <h2>{sheet.prog.title}</h2>
            {sheet.prog.desc && <p className="livetv-desc">{sheet.prog.desc}</p>}
            <div className="buttons">
              {sheet.prog.catchup && sheet.prog.stop <= now && (
                <button className="btn primary" data-nav data-autofocus onClick={() => playChannel(sheet.ch, { start: sheet.prog.start, stop: sheet.prog.stop }, sheet.prog.title)}>
                  Watch (catch-up)
                </button>
              )}
              {sheet.prog.start > now && (
                <button className="btn primary" data-nav data-autofocus onClick={() => void record(sheet)}>
                  Record
                </button>
              )}
              <button className="btn" data-nav onClick={() => playChannel(sheet.ch)}>
                Watch {sheet.ch.name} now
              </button>
              <button className="btn" data-nav onClick={() => setSheet(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
      {toast && (
        <p className="livetv-toast" role="status">
          {toast}
        </p>
      )}
    </div>
  );
}
