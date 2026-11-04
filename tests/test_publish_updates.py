#!/usr/bin/env python3
"""Verify Pages staging, retention, deployment checks and manifest publication without GitHub writes."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("publish_updates", Path(__file__).resolve().parents[1] / "tools/publish_updates.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="skyzh-pages-check-")
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
        self.payload = self.archive.read_bytes()
        self.jars = []
        for target in publisher.TARGETS:
            jar = self.root / f"fabric-{target}/build/libs/SkyBlockZH-0.8-Beta-Fabric-{target}.jar"
            jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar, "w") as bundle:
                bundle.writestr("fabric.mod.json", json.dumps({"id": "skyzh", "version": f"0.8+mc{target}.beta.42"}))
            self.jars.append(jar)
        self.calls = []
        self.verified = False
        self.blob = None
        for item in [patch.object(publisher, "ROOT", self.root),
                     patch.object(publisher, "git", return_value="a" * 40),
                     patch.object(publisher, "changed", return_value=True),
                     patch.object(publisher, "build_beta", side_effect=lambda: self.jars),
                     patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=0)),
                     patch.dict(publisher.os.environ, {"GITHUB_REPOSITORY": "BingKKni/SkyBlockZH", "GITHUB_TOKEN": "test",
                                                      "GITHUB_OUTPUT": str(self.root / "outputs")})]:
            item.start()
            self.addCleanup(item.stop)

    def stage(self):
        self.assertTrue(publisher.prepare(True))
        return json.loads((self.root / "build/updates/update.json").read_text(encoding="utf-8"))

    def verify(self, update):
        self.verified = True
        return self.payload

    def api(self, url, token, method="GET", data=None):
        self.calls.append((url, method, data))
        self.assertNotIn("/releases", url, "Translation publishing must not create Releases")
        if url.endswith("/git/ref/heads/main"):
            return {"object": {"sha": "a" * 40}}
        if url.endswith("/git/blobs"):
            self.assertTrue(self.verified, "Never publish a manifest before verifying the Pages download")
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

    def test_preparation_only_writes_deployment_files(self):
        with patch.object(publisher, "request") as network:
            update = self.stage()
        network.assert_not_called()
        self.assertEqual(update["download_url"], publisher.archive_url(hashlib.sha256(self.payload).hexdigest()))
        self.assertEqual((self.root / f"build/pages/skyzh-updates/{update['zip_sha256']}/translations.zip").read_bytes(), self.payload)
        self.assertEqual((self.root / "build/pages/skyzh-updates/translations.zip").read_bytes(), self.payload)
        self.assertFalse((self.root / "build/pages/skyzh-updates/update.json").exists(),
                         "The only public update manifest must be the repository copy published after verification")
        self.assertEqual((self.root / "outputs").read_text().strip(), "packaged=true")
        self.assertFalse((self.root / "skyzh-updates/update.json").exists())

    def test_beta_jars_are_staged_and_listed_per_target(self):
        update = self.stage()
        self.assertEqual(sorted(update["mod_beta"]["assets"]), list(publisher.TARGETS))
        for target, jar in zip(publisher.TARGETS, self.jars):
            asset = update["mod_beta"]["assets"][target]
            self.assertEqual(asset["version"], f"0.8+mc{target}.beta.42")
            self.assertEqual(asset["sha256"], hashlib.sha256(jar.read_bytes()).hexdigest())
            staged = self.root / "build/pages" / asset["download_url"][len(publisher.PAGES):]
            self.assertEqual(staged.read_bytes(), jar.read_bytes())

    def test_release_versioned_jar_is_not_published_as_beta(self):
        with zipfile.ZipFile(self.jars[0], "w") as bundle:
            bundle.writestr("fabric.mod.json", json.dumps({"id": "skyzh", "version": "0.8+mc26.1"}))
        with self.assertRaises(RuntimeError):
            publisher.prepare(True)

    def test_verified_deployment_precedes_git_manifest(self):
        self.stage()
        with patch.object(publisher, "download", side_effect=self.verify), patch.object(publisher, "request", side_effect=self.api):
            self.assertTrue(publisher.publish())
        self.assertEqual(self.blob["zip_sha256"], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(self.calls[-1][1], "PATCH")

    def test_failed_deployment_does_not_publish_manifest(self):
        self.stage()
        with patch.object(publisher, "download", side_effect=OSError("site unavailable")), patch.object(publisher.time, "sleep"), \
                patch.object(publisher, "request") as api, self.assertRaises(OSError):
            publisher.publish()
        api.assert_not_called()

    def test_no_changed_inputs_skip_deployment(self):
        with patch.object(publisher, "changed", return_value=False), patch.object(publisher, "download") as transfer:
            self.assertFalse(publisher.prepare(False))
        publisher.subprocess.run.assert_not_called()
        transfer.assert_not_called()
        self.assertEqual((self.root / "outputs").read_text().strip(), "packaged=false")

    def test_force_dispatch_builds_even_without_input_changes(self):
        with patch.object(publisher, "changed", return_value=False):
            self.stage()
        publisher.subprocess.run.assert_called_once()

    def test_previous_two_archives_survive_whole_site_replacement(self):
        entries = []
        payloads = {}
        for number in range(3):
            payload = f"previous archive {number}".encode()
            digest = hashlib.sha256(payload).hexdigest()
            entry = {"zip_sha256": digest, "zip_size": len(payload), "download_url": publisher.archive_url(digest)}
            entries.append(entry)
            payloads[digest] = payload
        previous = dict(entries[0], revision="b" * 40, previous_archives=entries[1:])
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps(previous))
        with patch.object(publisher, "download", side_effect=lambda entry: payloads[entry["zip_sha256"]]):
            update = self.stage()
        self.assertEqual(update["previous_archives"], entries[:2])
        for entry in entries[:2]:
            self.assertEqual((self.root / f"build/pages/skyzh-updates/{entry['zip_sha256']}/translations.zip").read_bytes(), payloads[entry["zip_sha256"]])
        self.assertFalse((self.root / f"build/pages/skyzh-updates/{entries[2]['zip_sha256']}").exists())

    def test_migration_does_not_download_legacy_release(self):
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps(dict(self.manifest, zip_sha256="c" * 64, zip_size=123,
                                       download_url="https://github.com/BingKKni/SkyBlockZH/releases/download/legacy/translations.zip")))
        with patch.object(publisher, "download") as transfer:
            update = self.stage()
        transfer.assert_not_called()
        self.assertEqual(update["previous_archives"], [])

    def test_new_input_commit_during_deployment_keeps_old_manifest(self):
        self.stage()
        def api(url, *args, **kwargs):
            if url.endswith("/git/ref/heads/main"):
                return {"object": {"sha": "c" * 40}}
            return self.api(url, *args, **kwargs)
        with patch.object(publisher, "download", side_effect=self.verify), patch.object(publisher, "request", side_effect=api), \
                patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            self.assertFalse(publisher.publish())
        self.assertIsNone(self.blob)

    def test_pages_download_checks_real_payload_size_and_hash(self):
        update = self.stage()
        response = io.BytesIO(self.payload)
        response.status = 200
        with patch.object(publisher.urllib.request, "urlopen", return_value=response):
            self.assertEqual(publisher.download(update), self.payload)
        response = io.BytesIO(b"x" * len(self.payload))
        response.status = 200
        with patch.object(publisher.urllib.request, "urlopen", return_value=response), self.assertRaises(RuntimeError):
            publisher.download(update)

    def test_download_rejects_foreign_urls_before_connecting(self):
        update = self.stage()
        update["download_url"] = "https://example.org/translations.zip"
        with patch.object(publisher.urllib.request, "urlopen") as network, self.assertRaises(ValueError):
            publisher.download(update)
        network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
