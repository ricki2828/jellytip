import { LIVE_TAB_LABEL, liveTabFor, liveTabHref, liveTabs } from "../lib/navModel";
import { useLiveFlags } from "./navFlags";

/** Sub-tab strip shown above the Live screens (Guide · My Channels · Sport · Fantasy · Recordings). The screens themselves are unchanged. */
export function LiveTabs({ current }: { current: string }) {
  const f = useLiveFlags();
  const active = liveTabFor(current);
  return (
    <div className="live-tabs" role="tablist" aria-label="Live">
      {liveTabs({ ...f, liveTv: f.liveTv || current === "livetv" }).map((k) => (
        <a key={k} href={liveTabHref(k)} role="tab" aria-selected={active === k} data-nav className={active === k ? "active" : ""}>
          {LIVE_TAB_LABEL[k]}
          {k === "recordings" && f.ready > 0 && <span className="nav-badge" aria-label={`${f.ready} ready to watch`}>{f.ready}</span>}
        </a>
      ))}
    </div>
  );
}
