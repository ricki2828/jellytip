// Row titles seen on this device (Edit Home lists hidden rows, which are absent from today's rows, by name).
const TITLES_KEY = "jellytip.rowTitles";

/** Row titles seen on this device, so hidden rows (absent from today's rows) still have a name. */
export function rememberRowTitles(rows: { id: string; title: string }[]) {
  try {
    const t = JSON.parse(localStorage.getItem(TITLES_KEY) ?? "{}") as Record<string, string>;
    for (const r of rows) t[r.id] = r.title;
    const keys = Object.keys(t);
    for (const k of keys.slice(0, Math.max(0, keys.length - 500))) delete t[k];
    localStorage.setItem(TITLES_KEY, JSON.stringify(t));
  } catch {
    // storage full / blocked: hidden rows fall back to a generic label
  }
}
export const rowTitles = (): Record<string, string> => {
  try {
    return JSON.parse(localStorage.getItem(TITLES_KEY) ?? "{}") as Record<string, string>;
  } catch {
    return {};
  }
};
