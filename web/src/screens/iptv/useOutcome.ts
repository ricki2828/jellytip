import { useMemo, useRef } from "react";
import { reportOutcome } from "../../api/iptv";
import { OutcomeSession, outcomeTarget, type FailReason } from "../../lib/streamFail";
import type { PlayTarget } from "../../lib/iptvPlay";

/** Failed-stream reporting for one player: one failure report per viewing session (a person's own report always), a success at 60 s of playback. */
export function useOutcome(target: PlayTarget) {
  const ot = useMemo(() => outcomeTarget(target), [target.channel, target.start, target.vod, target.episode, target.recording]); // eslint-disable-line react-hooks/exhaustive-deps
  const session = useRef<OutcomeSession | null>(null);
  if (!session.current) session.current = new OutcomeSession(reportOutcome);
  const ref = useRef({ ot });
  ref.current.ot = ot;
  return useMemo(() => ({
    fail: (reason: FailReason, posSec?: number) => session.current!.fail(ref.current.ot, reason, posSec),
    played: (sec: number) => session.current!.played(ref.current.ot, sec),
    target: () => ref.current.ot,
  }), []);
}
