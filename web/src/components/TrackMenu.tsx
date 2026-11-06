// The ONE audio / subtitle control for both players: trigger + menu (+ "What did they say?" replay).
// Keyboard: Arrow Up/Down/Home/End move, Escape closes and returns focus to the trigger; a click outside closes.
import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";
import "./TrackMenu.css";
import { audioSelectable, initialFocus, menuKeyAction, nextFocus, rowTarget, tabTarget, type TrackOption } from "../lib/trackMenu.ts";
import { SUB_BACKGROUNDS, SUB_SIZES, offsetLabel, stepOffset, SUB_OFFSET_MAX, SUB_OFFSET_MIN, type SubtitlePrefs } from "../lib/subtitlePrefs.ts";

export type TrackMenuProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  audio: TrackOption[];
  subtitles: TrackOption[];
  subtitlesOff: boolean;
  onPickAudio: (id: string) => void;
  /** null = Off */
  onPickSubtitle: (id: string | null) => void;
  /** shown when the engine exposes no audio tracks at all (omit to hide the section) */
  audioEmptyNote?: string;
  /** shown instead of the track lists when the engine can't expose tracks, with an optional way out */
  unavailable?: { note: string; action?: { label: string; run: () => void } };
  prefs: SubtitlePrefs;
  onPrefs: (patch: Partial<SubtitlePrefs>) => void;
  /** "What did they say?"; omit when no caption track exists */
  replay?: { disabled: boolean; run: () => void };
  /** class of the bar holding trigger (+ replay); carries the positioning */
  barClassName: string;
  menuClassName: string;
  triggerClassName?: string;
  onActivity?: () => void;
  extra?: ReactNode;
};

const BTN = "btn small";

export function TrackMenu(p: TrackMenuProps) {
  const { open, onOpenChange, audio, subtitles } = p;
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  const wasOpen = useRef(false);
  const replayBtn = useRef<HTMLButtonElement>(null);
  const replayDisabled = !!p.replay?.disabled;
  // A focused replay button that becomes disabled would drop focus to <body>: hand it to the trigger
  useEffect(() => {
    if (replayDisabled && document.activeElement === replayBtn.current) trigger.current?.focus();
  }, [replayDisabled]);
  const items = () => Array.from(menu.current?.querySelectorAll<HTMLElement>("[data-track-item]") ?? []);

  // Focus: into the menu on open (active choice first); back to the trigger on close if focus would otherwise be lost.
  useEffect(() => {
    if (open) {
      const els = items();
      const info = els.map((el) => ({ active: el.getAttribute("aria-checked") === "true", disabled: (el as HTMLButtonElement).disabled }));
      const i = initialFocus(info);
      (els[i] ?? menu.current)?.focus();
    } else if (wasOpen.current) {
      const a = document.activeElement;
      if (!a || a === document.body || menu.current?.contains(a)) trigger.current?.focus();
    }
    wasOpen.current = open;
  }, [open]);

  const close = (refocus = true) => {
    onOpenChange(false);
    if (refocus) trigger.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    p.onActivity?.();
    const act = menuKeyAction(e.key, e.shiftKey);
    if (act.kind === "pass") return;
    e.stopPropagation();
    if (act.kind === "swallow") return; // let the button's click happen, but nothing reaches playback
    e.preventDefault();
    const els = items();
    const cur = els.indexOf(document.activeElement as HTMLElement);
    const dis = els.map((el) => (el as HTMLButtonElement).disabled);
    if (act.kind === "close") close();
    else if (act.kind === "move") els[nextFocus(dis, cur < 0 ? 0 : cur, act.key)]?.focus();
    else if (act.kind === "tab") els[tabTarget(els.length, cur, act.shift)]?.focus();
    else {
      // Left/Right: along the S/M/L/XL, None/Shadow/Box or Lower/Higher row only
      const row = (document.activeElement as HTMLElement | null)?.closest(".track-appearance-row");
      if (!row) return;
      const rowEls = Array.from(row.querySelectorAll<HTMLElement>("[data-track-item]"));
      const at = rowEls.indexOf(document.activeElement as HTMLElement);
      rowEls[rowTarget(rowEls.map((el) => (el as HTMLButtonElement).disabled), at, act.dir)]?.focus();
    }
  };

  const radio = (o: TrackOption, group: string, pick: () => void) => (
    <button key={`${group}${o.id}`} type="button" role="menuitemradio" aria-checked={o.active} data-track-item data-nav disabled={o.disabled} className={`${BTN}${o.active ? " on" : ""}`} onClick={pick}>
      {o.label}
      {o.active && <span aria-hidden="true"> ✓</span>}
    </button>
  );
  const appearance = (label: string, group: string, values: readonly string[], current: string, set: (v: string) => void, text: (v: string) => string = (v) => v) => (
    <div role="group" aria-label={label} className="track-appearance">
      <span className="menu-head" aria-hidden="true">{label}</span>
      <div className="track-appearance-row">
        {values.map((v) => (
          <button key={`${group}${v}`} type="button" role="menuitemradio" aria-checked={current === v} data-track-item data-nav className={`${BTN}${current === v ? " on" : ""}`} onClick={() => set(v)}>
            {text(v)}
          </button>
        ))}
      </div>
    </div>
  );

  const selectable = audioSelectable(audio);
  const head = (text: string, key: string) => (
    <div className="menu-head" id={`${id}-${key}`} aria-hidden="true">{text}</div>
  );

  return (
    <>
      <div className={p.barClassName}>
        {p.replay && (
          <button ref={replayBtn} type="button" className={`${BTN} track-replay`} data-nav disabled={p.replay.disabled} onClick={(e) => { e.stopPropagation(); p.replay!.run(); p.onActivity?.(); }} aria-label="What did they say? Replay the last 10 seconds with captions" title="Replay the last 10 seconds with captions">
            What did they say?
          </button>
        )}
        <button
          ref={trigger}
          type="button"
          className={`${BTN} ${p.triggerClassName ?? ""}`}
          data-nav
          aria-haspopup="menu"
          aria-expanded={open}
          aria-controls={open ? `${id}-menu` : undefined}
          onClick={(e) => {
            e.stopPropagation();
            onOpenChange(!open);
            p.onActivity?.();
          }}
        >
          Audio &amp; subtitles
        </button>
      </div>
      {open && <div className="track-scrim" onPointerDown={(e) => e.stopPropagation()} onClick={(e) => { e.preventDefault(); e.stopPropagation(); close(); }} />}
      {open && (
        <div ref={menu} id={`${id}-menu`} role="menu" aria-label="Audio and subtitles" tabIndex={-1} className={p.menuClassName} onClick={(e) => e.stopPropagation()} onKeyDown={onKeyDown}>
          {p.unavailable ? (
            <>
              <p role="presentation">{p.unavailable.note}</p>
              {p.unavailable.action && (
                <button type="button" role="menuitem" data-track-item data-nav className={BTN} onClick={() => { close(false); p.unavailable!.action!.run(); }}>
                  {p.unavailable.action.label}
                </button>
              )}
            </>
          ) : (
            <>
              {audio.length > 0 ? (
                <div role="group" aria-labelledby={`${id}-audio`}>
                  {head("Audio", "audio")}
                  {selectable ? audio.map((o) => radio(o, "a", () => { close(); p.onPickAudio(o.id); })) : <p role="presentation">{audio[0].label} (only track)</p>}
                </div>
              ) : (
                p.audioEmptyNote && <p role="presentation">{p.audioEmptyNote}</p>
              )}
              <div role="group" aria-labelledby={`${id}-subs`}>
                {head("Subtitles", "subs")}
                {radio({ id: "off", label: "Off", active: p.subtitlesOff }, "s", () => { close(); p.onPickSubtitle(null); })}
                {subtitles.map((o) => radio(o, "s", () => { close(); p.onPickSubtitle(o.id); }))}
              </div>
              {subtitles.length > 0 && (
                <div role="group" aria-label="Subtitle appearance" className="track-appearance-group">
                  {appearance("Size", "z", SUB_SIZES, p.prefs.size, (v) => p.onPrefs({ size: v as SubtitlePrefs["size"] }))}
                  {appearance("Background", "b", SUB_BACKGROUNDS, p.prefs.background, (v) => p.onPrefs({ background: v as SubtitlePrefs["background"] }), (v) => v[0].toUpperCase() + v.slice(1))}
                  <div role="group" aria-label="Position" className="track-appearance">
                    <span className="menu-head" aria-hidden="true">Position · {offsetLabel(p.prefs.offset)}</span>
                    <div className="track-appearance-row">
                      <button type="button" role="menuitem" data-track-item data-nav className={BTN} aria-disabled={p.prefs.offset <= SUB_OFFSET_MIN} onClick={() => p.prefs.offset > SUB_OFFSET_MIN && p.onPrefs({ offset: stepOffset(p.prefs.offset, -1) })} aria-label="Move captions lower">Lower</button>
                      <button type="button" role="menuitem" data-track-item data-nav className={BTN} aria-disabled={p.prefs.offset >= SUB_OFFSET_MAX} onClick={() => p.prefs.offset < SUB_OFFSET_MAX && p.onPrefs({ offset: stepOffset(p.prefs.offset, 1) })} aria-label="Move captions higher">Higher</button>
                    </div>
                  </div>
                </div>
              )}
              {p.extra}
            </>
          )}
        </div>
      )}
    </>
  );
}
