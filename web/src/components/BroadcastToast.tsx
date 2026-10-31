// Lower-third style toast: a team-colour bar, an icon block, a small-caps kicker, a big condensed message and a sub line. Slides in
// from the right (transform only). Used by the flip, moment and fantasy toasts.
import "../screens/iptv/iptv.css";

export interface ToastData {
  color: string;
  kicker: string;
  text: string;
  sub?: string | null;
  icon?: "flip" | "moment" | "fantasy";
}

const ICON = {
  flip: "M3 17l6-6 4 4 8-9",
  moment: "M13 2 4 14h7l-1 8 9-12h-7Z",
  fantasy: "M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.2 6.4 20.2l1.1-6.2L3 9.6l6.2-.9Z",
};

export function BroadcastToast({ t, level = 0, id }: { t: ToastData; level?: number; id?: number }) {
  return (
    <div key={id} className="bc-toast" role="status" style={{ ["--tc" as string]: t.color, top: `calc(var(--safe-t, 0px) + ${64 + level * 84}px)` }}>
      <i aria-hidden />
      <span className="ic" aria-hidden>
        <svg viewBox="0 0 24 24">
          <path d={ICON[t.icon ?? "moment"]} />
        </svg>
      </span>
      <div className="tx">
        <span className="k">{t.kicker}</span>
        <b className="m">{t.text}</b>
        {t.sub && <span className="s">{t.sub}</span>}
      </div>
    </div>
  );
}
