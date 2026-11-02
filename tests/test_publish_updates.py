#!/usr/bin/env python3
"""Exercise publication ordering and failure handling without writing to GitHub."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("publish_updates", Path(__file__).resolve().parents[1] / "tools/publish_updates.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="skyzh-publisher-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.archive = self.root / "build/translations/translations.zip"
        self.archive.parent.mkdir(parents=True)
        self.manifest = {"format_version": 1, "translation_api": 4, "revision": "a" * 40,
                         "source_dirty": False, "build_at_utc8": "2026-11-02T12:00:00+08:00",
                         "content_sha256": "b" * 64, "file_count": 1}
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
            bundle.writestr("manifest.json", json.dumps(self.manifest))
            bundle.writestr("original_text/example.json", "{}")
        self.calls = []
        self.uploaded = False
        self.blob = None
        self.patches = [patch.object(publisher, "ROOT", self.root),
                        patch.object(publisher, "git", return_value="a" * 40),
                        patch.object(publisher.subprocess, "run"),
                        patch.dict(publisher.os.environ, {"GITHUB_REPOSITORY": "BingKKni/SkyBlockZH", "GITHUB_TOKEN": "test"})]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def api(self, url, token, method="GET", data=None, content_type=None):
        self.calls.append((url, method, data))
        if "/releases/tags/" in url:
            raise publisher.urllib.error.HTTPError(url, 404, "not found", {}, None)
        if url.endswith("/releases"):
            body = json.loads(data)
            self.assertTrue(body["prerelease"])
            self.assertEqual(body["make_latest"], "false")
            return {"assets": [], "upload_url": "https://uploads.github.com/test{?name}"}
        if "uploads.github.com" in url:
            self.uploaded = True
            return {"size": self.archive.stat().st_size,
                    "digest": "sha256:" + publisher.hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                    "browser_download_url": "https://github.com/BingKKni/SkyBlockZH/releases/download/test/translations.zip"}
        if url.endswith("/git/ref/heads/main"):
            return {"object": {"sha": "a" * 40}}
        if url.endswith("/git/blobs"):
            self.assertTrue(self.uploaded, "Never publish a manifest before uploading its ZIP")
            self.blob = json.loads(publisher.base64.b64decode(json.loads(data)["content"]))
            return {"sha": "blob"}
        if "/git/commits/" in url:
            return {"tree": {"sha": "base"}}
        if url.endswith("/git/trees"):
            self.assertEqual([item["path"] for item in json.loads(data)["tree"]], ["skyzh-updates/update.json"])
            return {"sha": "tree"}
        if url.endswith("/git/commits"):
            return {"sha": "commit"}
        if url.endswith("/git/refs/heads/main"):
            self.assertFalse(json.loads(data)["force"])
            return {}
        raise AssertionError(url)

    def test_zip_upload_precedes_manifest_and_git_contains_only_json(self):
        with patch.object(publisher, "request", side_effect=self.api):
            publisher.publish(True)
        self.assertEqual(self.blob["translation_api"], 4)
        self.assertEqual(self.blob["zip_sha256"], publisher.hashlib.sha256(self.archive.read_bytes()).hexdigest())
        self.assertEqual(self.blob["zip_size"], self.archive.stat().st_size)
        self.assertEqual(self.calls[-1][1], "PATCH")

    def test_upload_failure_never_publishes_manifest(self):
        def api(url, *args, **kwargs):
            if "uploads.github.com" in url:
                raise OSError("download service unavailable")
            return self.api(url, *args, **kwargs)
        with patch.object(publisher, "request", side_effect=api), self.assertRaises(OSError):
            publisher.publish(True)
        self.assertFalse(any("/git/blobs" in url for url, *_ in self.calls))

    def test_no_changed_inputs_skips_packaging(self):
        target = self.root / "skyzh-updates/update.json"
        target.parent.mkdir()
        target.write_text(json.dumps(self.manifest), encoding="utf-8")
        with patch.object(publisher, "changed", return_value=False), patch.object(publisher, "request") as network:
            publisher.publish(False)
        network.assert_not_called()
        publisher.subprocess.run.assert_not_called()

    def test_manual_dispatch_forces_packaging(self):
        with patch.object(publisher, "changed", return_value=False), patch.object(publisher, "request", side_effect=self.api):
            publisher.publish(True)
        publisher.subprocess.run.assert_called_once()

    def test_existing_asset_without_digest_is_not_published(self):
        def api(url, *args, **kwargs):
            if "/releases/tags/" in url:
                return {"assets": [{"name": "translations.zip", "size": self.archive.stat().st_size,
                                    "browser_download_url": "https://github.com/BingKKni/SkyBlockZH/releases/download/test/translations.zip"}]}
            return self.api(url, *args, **kwargs)
        with patch.object(publisher, "request", side_effect=api), self.assertRaises(RuntimeError):
            publisher.publish(True)
        self.assertFalse(any("/git/blobs" in url for url, *_ in self.calls))

    def test_verified_existing_asset_can_be_reused(self):
        def api(url, *args, **kwargs):
            if "/releases/tags/" in url:
                self.uploaded = True
                return {"assets": [{"name": "translations.zip", "size": self.archive.stat().st_size,
                                    "digest": "sha256:" + publisher.hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                                    "browser_download_url": "https://github.com/BingKKni/SkyBlockZH/releases/download/test/translations.zip"}]}
            return self.api(url, *args, **kwargs)
        with patch.object(publisher, "request", side_effect=api):
            publisher.publish(True)
        self.assertIsNotNone(self.blob)
        self.assertFalse(any("uploads.github.com" in url for url, *_ in self.calls))

    def test_new_input_commit_during_build_cannot_publish_old_manifest(self):
        def api(url, *args, **kwargs):
            if url.endswith("/git/ref/heads/main"):
                return {"object": {"sha": "c" * 40}}
            return self.api(url, *args, **kwargs)
        with patch.object(publisher, "changed_between", return_value=True), patch.object(publisher, "request", side_effect=api):
            publisher.publish(True)
        self.assertFalse(any("/git/blobs" in url for url, *_ in self.calls))


if __name__ == "__main__":
    unittest.main()
