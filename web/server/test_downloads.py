"""python3 -m unittest web/server/test_downloads.py  (no network: Jellyfin is stubbed)"""
import io
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import downloads as dl  # noqa: E402


class Stub(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


class DownloadsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.urls = []

        def fetch(url):
            self.urls.append(url)
            return Stub(b"x" * 2_500_000)
        self.d = dl.Downloads("http://jf/jellyfin", "KEY", self.dir, ffmpeg="", fetch=fetch)

    def wait(self, jid):
        for _ in range(100):
            if self.d.status(jid)[1]["state"] in ("ready", "failed"):
                return self.d.status(jid)[1]
            time.sleep(0.02)
        self.fail("job did not finish")

    def test_prepare_ready_and_join(self):
        item = "a" * 32
        code, body = self.d.prepare(item, "720", runtime_ticks=60 * 10_000_000)
        self.assertEqual(code, 202)
        done = self.wait(body["jobId"])
        self.assertEqual(done["state"], "ready")
        self.assertEqual(done["fileUrl"], f"v1/downloads/files/{body['jobId']}")
        self.assertIn("VideoCodec=h264", self.urls[0])
        self.assertIn("MaxWidth=1280", self.urls[0])
        code, again = self.d.prepare(item, "720")
        self.assertEqual((code, again["state"]), (200, "ready"))
        self.assertEqual(len(self.urls), 1)  # no second transcode

    def test_rejects_bad_input(self):
        self.assertEqual(self.d.prepare("../etc", "720")[0], 400)
        self.assertEqual(self.d.prepare("a" * 32, "4k")[0], 400)
        self.assertEqual(self.d.file("../../x")[0], 404)

    def test_range(self):
        _, body = self.d.prepare("b" * 32, "1080")
        jid = self.wait(body["jobId"])["jobId"]
        code, headers, path, off, n = self.d.file(jid, "bytes=100-199")
        self.assertEqual((code, off, n, headers["Content-Range"]), (206, 100, 100, "bytes 100-199/2500000"))
        self.assertEqual(self.d.file(jid, "bytes=9999999-")[0], 416)
        self.assertEqual(self.d.file(jid)[0], 200)
        self.assertEqual(dl.parse_range("bytes=-500", 1000), (500, 999))

    def test_restart_keeps_ready_files(self):
        _, body = self.d.prepare("c" * 32, "720")
        self.wait(body["jobId"])
        again = dl.Downloads("http://jf/jellyfin", "KEY", self.dir, ffmpeg="")
        self.assertEqual(again.status(body["jobId"])[1]["state"], "ready")


if __name__ == "__main__":
    unittest.main()
