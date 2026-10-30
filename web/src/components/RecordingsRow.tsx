// Home "Recordings" row: recording now (● REC), ready and unwatched (NEW), scheduled (time), watched. OK on a ready one
// plays it; on a scheduled one a small sheet offers Cancel. Shown whenever the person has any recordings.
import { useState } from "react";
import { cancelRecording } from "../api/iptv";
import { refreshRecs, useRecs } from "../api/recStore";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { StopRecordingSheet } from "./StopRecordingSheet";
import { chipFor, resumeAt, lengthText, orderRecs, type Rec } from "../lib/recs";
import { targetPath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import "../screens/iptv/iptv.css";

const playPath = (r: Rec) => `${targetPath({ recording: String(r.id) })}&t=${encodeURIComponent(r.title)}${resumeAt(r.progress)}`;

function RecTile({ r, onOpen }: { r: Rec; onOpen: () => void }) {
  const chip = chipFor(r);
  const p = r.progress;
  const pct = p && !p.played && p.duration ? Math.min(100, (p.position / p.duration) * 100) : 0;
  const sub = [r.channel, lengthText(r)].filter(Boolean).join(" · ");
  return (
    <button className={`rec-tile ${r.state}`} data-nav onClick={onOpen} aria-label={`${r.title}, ${chip.text}, ${r.channel}`} title={r.title}>
      <span className={`rec-chip ${chip.tone}`}>{chip.text}</span>
      <span className="rec-title">{r.title}</span>
      <span className="rec-sub">{sub}</span>
      {r.desc && <span className="rec-desc">{r.desc}</span>}
      {pct > 0 && (
        <span className="onnow-progress" aria-hidden>
          <span style={{ width: `${pct}%` }} />
        </span>
      )}
    </button>
  );
}

export function RecordingsRow() {
  const recs = useRecs();
  const [sheet, setSheet] = useState<Rec | null>(null);
  const [stopping, setStopping] = useState<Rec | null>(null);
  useEscapeClose(() => setSheet(null), !!sheet && !stopping);
  const list = recs ? orderRecs(recs) : [];
  if (!list.length) return null;
  const open = (r: Rec) => (r.state === "recording" ? setStopping(r) : r.playable ? go(playPath(r)) : r.state === "scheduled" ? setSheet(r) : go("/recordings"));
  return (
    <section className="row recs-row">
      <h2 className="row-title">
        <button className="row-title-link" data-nav onClick={() => go("/recordings")}>
          Recordings
          <span className="row-title-more" aria-hidden>
            ›
          </span>
        </button>
      </h2>
      <div className="row-scroll">
        {list.map((r) => (
          <RecTile key={r.id} r={r} onOpen={() => open(r)} />
        ))}
      </div>
      {stopping && <StopRecordingSheet rec={stopping} onClose={() => setStopping(null)} onStopped={() => void refreshRecs(true)} onWatch={stopping.playable ? () => go(playPath(stopping)) : undefined} />}
      {sheet && (
        <div className="livetv-sheet-back" onClick={() => setSheet(null)}>
          <div className="livetv-sheet" role="dialog" aria-label={sheet.title} onClick={(e) => e.stopPropagation()}>
            <SheetClose onClose={() => setSheet(null)} />
            <p className="iptv-hint">
              {sheet.channel} · {chipFor(sheet).text}
            </p>
            <h2>{sheet.title}</h2>
            {sheet.desc && <p className="livetv-desc">{sheet.desc}</p>}
            <div className="buttons">
              <button
                className="btn primary"
                data-nav
                data-autofocus
                onClick={async () => {
                  await cancelRecording(sheet.id);
                  setSheet(null);
                  void refreshRecs(true);
                }}
              >
                Cancel recording
              </button>
              <button className="btn" data-nav onClick={() => setSheet(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
