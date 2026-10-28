"""Tiny SQLite TTL cache. Values are JSON."""
import json
import sqlite3
import threading
import time

SCORES_TTL = 7 * 86400
EMPTY_TTL = 6 * 3600  # upstream answered but had nothing: recheck sooner than 7d
ERROR_TTL = 15 * 60  # negative cache so a dead upstream is not hammered


class Cache:
    def __init__(self, path):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._lock = threading.Lock()
        with self._lock:
            self._db.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, expires REAL NOT NULL)")
            self._db.commit()

    def get(self, key, now=None):
        now = time.time() if now is None else now
        with self._lock:
            row = self._db.execute("SELECT v, expires FROM kv WHERE k = ?", (key,)).fetchone()
        if not row or row[1] <= now:
            return None
        return json.loads(row[0])

    def put(self, key, value, ttl, now=None):
        now = time.time() if now is None else now
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO kv (k, v, expires) VALUES (?, ?, ?)",
                             (key, json.dumps(value), now + ttl))
            self._db.commit()
