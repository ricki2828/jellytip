// In-app history for the hash router (pure; tested in web/test/navStack.test.ts). Every entry carries its depth in
// history.state (0 = where the app was opened, e.g. a deep link), so Back knows whether there is an in-app screen to
// return to, and its scroll position + focused tile, so returning lands exactly where the user was.

export interface HistoryLike {
  readonly state: unknown;
  replaceState(state: unknown, unused: string): void;
  back(): void;
}

export interface NavEntry {
  depth: number;
  scrollY?: number;
  focus?: string;
}

const KEY = "jtNav";

export function createNavStack(h: HistoryLike) {
  let depth = 0;
  let replacing = false;
  const state = () => (h.state && typeof h.state === "object" ? (h.state as Record<string, unknown>) : {});
  const entry = (): NavEntry | null => {
    const e = state()[KEY] as NavEntry | undefined;
    return e && typeof e.depth === "number" ? e : null;
  };
  const write = (e: NavEntry) => h.replaceState({ ...state(), [KEY]: e }, "");

  return {
    /** On start: an entry restored by the browser keeps its depth, a fresh one is the root (0). */
    init() {
      const e = entry();
      depth = e ? e.depth : 0;
      if (!e) write({ depth });
    },
    /** The next hash change replaces the current entry (same depth) instead of adding one. */
    markReplace() {
      replacing = true;
    },
    /** Call on every hash change. Returns the entry when it was a Back / Forward (restore it), null for a new screen. */
    onHashChange(): NavEntry | null {
      const e = entry();
      const wasReplace = replacing;
      replacing = false;
      if (e) {
        depth = e.depth;
        return e;
      }
      if (!wasReplace) depth += 1;
      write({ depth });
      return null;
    },
    depth: () => depth,
    canGoBack: () => depth > 0,
    /** Remember where the user is on the current entry. */
    save(scrollY: number, focus?: string) {
      write({ depth, scrollY, focus });
    },
    back: () => h.back(),
  };
}
