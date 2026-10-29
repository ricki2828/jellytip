// Tiny IndexedDB wrapper for the phone app's offline data: download records, their images and subtitles, and watch
// progress recorded while offline. Everything is keyed by string.

const DB_NAME = "jellytip-offline";
const STORES = ["downloads", "blobs", "progress"] as const;
export type StoreName = (typeof STORES)[number];

let dbPromise: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        for (const s of STORES) if (!req.result.objectStoreNames.contains(s)) req.result.createObjectStore(s);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => {
        dbPromise = null;
        reject(req.error);
      };
    });
  }
  return dbPromise;
}

function run<T>(store: StoreName, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  return db().then(
    (d) =>
      new Promise<T>((resolve, reject) => {
        const req = fn(d.transaction(store, mode).objectStore(store));
        req.onsuccess = () => resolve(req.result as T);
        req.onerror = () => reject(req.error);
      }),
  );
}

export const idbGet = <T>(store: StoreName, key: string) => run<T | undefined>(store, "readonly", (s) => s.get(key));
export const idbPut = (store: StoreName, key: string, value: unknown) => run<void>(store, "readwrite", (s) => s.put(value, key));
export const idbDelete = (store: StoreName, key: string) => run<void>(store, "readwrite", (s) => s.delete(key));
export const idbAll = <T>(store: StoreName) => run<T[]>(store, "readonly", (s) => s.getAll());
export const idbKeys = (store: StoreName) => run<string[]>(store, "readonly", (s) => s.getAllKeys() as IDBRequest);
