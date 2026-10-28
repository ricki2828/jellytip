"""Minimal Jellyfin admin client (API key). Stdlib only."""
import json
import urllib.error
import urllib.parse
import urllib.request


class JfError(Exception):
    def __init__(self, status, body=""):
        super().__init__(f"jellyfin {status}")
        self.status, self.body = status, body


class Jellyfin:
    def __init__(self, base, key, user="ricki28"):
        self.base, self.key, self.user, self._uid = (base or "").rstrip("/"), key, user, None

    @property
    def ok(self):
        return bool(self.base and self.key)

    def call(self, method, path, params=None, timeout=30):
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        req = urllib.request.Request(url, method=method, headers={"Authorization": f'MediaBrowser Token="{self.key}"'})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            raise JfError(e.code, e.read(2000).decode("utf-8", "replace")) from None
        return json.loads(raw) if raw else None

    def uid(self):
        if not self._uid:
            users = self.call("GET", "/Users")
            self._uid = next((u["Id"] for u in users if u["Name"] == self.user), users[0]["Id"])
        return self._uid

    def items(self, **params):
        """User-scoped listing (the admin /Items endpoint under-reports)."""
        return self.call("GET", f"/Users/{self.uid()}/Items", {"Recursive": "true", **params}).get("Items", [])
