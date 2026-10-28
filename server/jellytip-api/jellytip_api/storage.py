"""GET /v1/storage (admin only): the seedbox user quota, which is the real limit (not df)."""
import re
import subprocess
import threading
import time

TTL = 300
_UNITS = {"K": 1 / 1024 / 1024, "M": 1 / 1024, "G": 1.0, "T": 1024.0, "P": 1024.0 * 1024}
_lock = threading.Lock()
_cache = {"at": 0.0, "val": None}


def _gb(tok):
    m = re.fullmatch(r"(\d+(?:\.\d+)?)([KMGTP]?)", tok.rstrip("*"))
    if not m:
        raise ValueError(tok)
    return float(m.group(1)) * _UNITS.get(m.group(2) or "K", 1.0)


def parse(text):
    """`quota -s` rows: filesystem, space, quota, limit (the fs name may sit on its own line)."""
    for line in text.splitlines():
        toks = line.split()
        nums = [t for t in toks if re.fullmatch(r"\d+(?:\.\d+)?[KMGTP]?\*?", t)]
        if len(nums) >= 3 and (toks[0].startswith("/") or len(nums) == len(toks)):
            used, quota = _gb(nums[0]), _gb(nums[1])
            if quota > 0:
                return {"usedGb": round(used, 1), "quotaGb": round(quota, 1), "freeGb": round(max(quota - used, 0), 1)}
    raise ValueError("no quota line")


def get():
    with _lock:
        now = time.time()
        if _cache["val"] is None or now - _cache["at"] > TTL:
            out = subprocess.run(["quota", "-s"], capture_output=True, text=True, timeout=20).stdout
            _cache["val"] = {**parse(out), "updatedAt": int(now)}
            _cache["at"] = now
        return _cache["val"]
