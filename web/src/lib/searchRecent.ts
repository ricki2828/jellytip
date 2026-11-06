// Recent searches: per user, in localStorage, newest first, at most 10 (pure over a Storage-like; tested in web/test/searchRecent.test.ts).

export const MAX_RECENT = 10;
export interface Store {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

const key = (userId: string) => `jt.search.recent.${userId}`;

export function loadRecent(store: Store, userId: string): string[] {
  if (!userId) return [];
  try {
    const v = JSON.parse(store.getItem(key(userId)) ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && !!x.trim()).slice(0, MAX_RECENT) : [];
  } catch {
    return [];
  }
}

export function addRecent(store: Store, userId: string, q: string): string[] {
  const term = q.replace(/\s+/g, " ").trim();
  if (!userId || term.length < 2) return loadRecent(store, userId);
  const next = [term, ...loadRecent(store, userId).filter((x) => x.toLowerCase() !== term.toLowerCase())].slice(0, MAX_RECENT);
  try {
    store.setItem(key(userId), JSON.stringify(next));
  } catch {
    /* storage full or blocked: recents are a nicety */
  }
  return next;
}

export function clearRecent(store: Store, userId: string): string[] {
  if (userId) {
    try {
      store.removeItem(key(userId));
    } catch {
      /* ignore */
    }
  }
  return [];
}
