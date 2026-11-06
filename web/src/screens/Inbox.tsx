// #/inbox: when things the person cares about become watchable (requests that arrived, new episodes of followed
// series, a requested title appearing on their IPTV). Newest first, 30 days. Derivation lives in lib/inbox.ts.
import { useEffect } from "react";
import { markRead, refreshInbox, useInbox } from "../api/inboxStore";
import { ago, eventText, unreadCount } from "../lib/inbox";
import { go } from "../lib/router";
import "./Inbox.css";

export function Inbox() {
  const store = useInbox();
  useEffect(() => void refreshInbox(), []);
  const unread = unreadCount(store);
  const events = store.events;
  return (
    <div className="inbox">
      <div className="inbox-head">
        <h1 className="page-title">Inbox</h1>
        {unread > 0 && (
          <button className="btn small" data-nav onClick={markRead}>
            Mark all read
          </button>
        )}
      </div>
      {events.length === 0 ? (
        <p className="empty">Follow or request titles to hear when they arrive.</p>
      ) : (
        <ul className="inbox-list">
          {events.map((e) => (
            <li key={e.id}>
              <button className={`inbox-item${e.read ? "" : " unread"}`} data-nav onClick={() => go(e.link)}>
                <span className="inbox-dot" aria-hidden />
                <span className="inbox-text">{eventText(e)}</span>
                <span className="inbox-when">{ago(e.at)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <p className="inbox-note">Covers favourite and up-next series, and titles you requested here. Items drop off after 30 days.</p>
    </div>
  );
}
