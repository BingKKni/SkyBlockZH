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
REAL_FETCH = publisher.fetch


class BetaBuildTests(unittest.TestCase):
    def test_beta_build_uses_installable_shadow_jars(self):
        with tempfile.TemporaryDirectory(prefix="skyzh-beta-check-") as directory:
            root = Path(directory)
            expected = []
            for target in publisher.TARGETS:
                jar = root / f"fabric-{target}/build/libs/SkyBlockZH-0.8-Beta-Fabric-{target}.jar"
                jar.parent.mkdir(parents=True)
                jar.touch()
                expected.append(jar)
            with patch.object(publisher, "ROOT", root), patch.object(publisher.subprocess, "run") as run:
                self.assertEqual(publisher.build_beta(), expected)
            command = run.call_args.args[0]
            self.assertIn("-Pskyzh.channel=beta", command)
            self.assertEqual(command[3:], [f":fabric-{target}:shadowJar" for target in publisher.TARGETS])
            self.assertTrue(run.call_args.kwargs["check"])


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
                     patch.object(publisher, "legacy_manifest", side_effect=lambda update, token: update),
                     patch.object(publisher, "release_assets", return_value=({"tag_name": "0", "assets": []}, [])),
                     patch.object(publisher, "fetch", return_value=b"verified jar"),
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
        self.assertEqual(json.loads((self.root / "build/pages/skyzh-updates/update.json").read_bytes()), update)
        self.assertEqual(json.loads((self.root / "build/pages/skyzh-updates/release.json").read_bytes()), update["mod_release"])
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

    def test_failed_legacy_upload_does_not_write_repository_manifest(self):
        self.stage()
        with patch.object(publisher, "download", side_effect=self.verify), patch.object(publisher, "request", side_effect=self.api), \
                patch.object(publisher, "legacy_manifest", side_effect=RuntimeError("upload failed")), self.assertRaises(RuntimeError):
            publisher.publish()
        self.assertIsNone(self.blob)
        self.assertEqual([method for _, method, _ in self.calls], ["GET"])

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

    def test_legacy_pages_archives_migrate_without_losing_retention(self):
        digest = hashlib.sha256(b"legacy").hexdigest()
        entry = {"zip_sha256": digest, "zip_size": 6,
                 "download_url": publisher.archive_url(digest).replace(publisher.PAGES, publisher.LEGACY_PAGES)}
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps(entry))
        with patch.object(publisher, "fetch", return_value=b"legacy") as transfer:
            update = self.stage()
        transfer.assert_called_once_with(entry["download_url"], 6, digest)
        self.assertEqual(update["previous_archives"][0]["download_url"], publisher.archive_url(digest))
        self.assertEqual((self.root / f"build/pages/skyzh-updates/{digest}/translations.zip").read_bytes(), b"legacy")

    def test_repository_legacy_wrapper_preserves_pages_retention_state(self):
        payload = b"previous pages archive"
        digest = hashlib.sha256(payload).hexdigest()
        previous = {"zip_sha256": digest, "zip_size": len(payload), "download_url": publisher.archive_url(digest)}
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps({"download_url": "https://github.com/legacy/translations.zip", "pages_state": previous}))
        with patch.object(publisher, "download", return_value=payload) as transfer:
            update = self.stage()
        transfer.assert_called_once_with(previous)
        self.assertEqual(update["previous_archives"], [previous])

    def test_legacy_beta_reads_original_url_and_stages_new_domain_path(self):
        payload = b"legacy beta"
        digest = hashlib.sha256(payload).hexdigest()
        name = "SkyBlockZH-0.7-Beta-Fabric-26.1.jar"
        url = publisher.beta_url(digest, name).replace(publisher.PAGES, publisher.LEGACY_PAGES)
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps({"mod_beta": {"assets": {"26.1": {
            "download_url": url, "size": len(payload), "sha256": digest}}}}))
        with patch.object(publisher, "fetch", return_value=payload) as transfer:
            self.stage()
        transfer.assert_called_once_with(url, len(payload), digest)
        self.assertEqual((self.root / f"build/pages/skyzh-updates/mod/{digest}/{name}").read_bytes(), payload)

    def test_previous_formal_release_survives_other_deployments(self):
        payload = b"old release"
        digest = hashlib.sha256(payload).hexdigest()
        name = "SkyBlockZH-0.7-Fabric-26.1.jar"
        asset = {"name": name, "size": len(payload), "digest": "sha256:" + digest,
                 "browser_download_url": publisher.beta_url(digest, name)}
        file = self.root / "skyzh-updates/update.json"
        file.parent.mkdir()
        file.write_text(json.dumps({"mod_release": {"assets": [asset]}}))
        with patch.object(publisher, "fetch", return_value=payload):
            self.stage()
        self.assertEqual((self.root / "build/pages" / asset["browser_download_url"][len(publisher.PAGES):]).read_bytes(), payload)

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
        with patch.object(publisher, "fetch", wraps=REAL_FETCH), \
                patch.object(publisher.urllib.request, "urlopen", return_value=response):
            self.assertEqual(publisher.download(update), self.payload)
        response = io.BytesIO(b"x" * len(self.payload))
        response.status = 200
        with patch.object(publisher, "fetch", wraps=REAL_FETCH), \
                patch.object(publisher.urllib.request, "urlopen", return_value=response), self.assertRaises(RuntimeError):
            publisher.download(update)

    def test_download_rejects_foreign_urls_before_connecting(self):
        update = self.stage()
        update["download_url"] = "https://example.org/translations.zip"
        with patch.object(publisher.urllib.request, "urlopen") as network, self.assertRaises(ValueError):
            publisher.download(update)
        network.assert_not_called()


class LegacyCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="skyzh-legacy-check-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.payloads = {}
        payload = b"translation fixture"
        digest = hashlib.sha256(payload).hexdigest()
        self.update = {"revision": "a" * 40, "zip_size": len(payload), "zip_sha256": digest,
                       "download_url": publisher.archive_url(digest), "mod_beta": {"assets": {}}}
        self.add_file(self.update["download_url"], payload)
        for target in publisher.TARGETS:
            payload = f"beta fixture {target}".encode()
            digest = hashlib.sha256(payload).hexdigest()
            url = publisher.beta_url(digest, f"SkyBlockZH-0.8.3-Beta-Fabric-{target}.jar")
            self.update["mod_beta"]["assets"][target] = {"download_url": url, "size": len(payload), "sha256": digest,
                                                       "version": f"0.8.3+mc{target}.beta.190"}
            self.add_file(url, payload)
        self.release = {"id": 123, "tag_name": "legacy-updates", "prerelease": True, "draft": False, "assets": []}
        for item in [patch.object(publisher, "ROOT", self.root),
                     patch.object(publisher, "request", return_value=self.release),
                     patch.object(publisher, "fetch", side_effect=self.fetch),
                     patch.object(publisher, "upload_legacy", side_effect=self.upload)]:
            item.start()
            self.addCleanup(item.stop)

    def add_file(self, url, payload):
        path = self.root / "build/pages" / url[len(publisher.PAGES):]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)

    def upload(self, endpoint, token, payload):
        name = endpoint.split("?name=", 1)[1]
        url = "https://github.com/BingKKni/SkyBlockZH/releases/download/legacy-updates/" + name
        self.payloads[url] = payload
        asset = {"name": name, "browser_download_url": url, "size": len(payload),
                 "digest": "sha256:" + hashlib.sha256(payload).hexdigest()}
        self.release["assets"].append(asset)
        return asset

    def fetch(self, url, size, digest):
        payload = self.payloads[url]
        self.assertEqual((len(payload), hashlib.sha256(payload).hexdigest()), (size, digest))
        return payload

    def test_old_client_manifest_uses_github_without_changing_pages_state(self):
        original = json.loads(json.dumps(self.update))
        result = publisher.legacy_manifest(self.update, "test")
        self.assertEqual(self.update, original)
        self.assertEqual(result["pages_state"], original)
        self.assertTrue(result["download_url"].startswith("https://github.com/BingKKni/SkyBlockZH/releases/download/legacy-updates/"))
        for target, asset in result["mod_beta"]["assets"].items():
            self.assertTrue(asset["download_url"].startswith("https://github.com/BingKKni/SkyBlockZH/releases/download/legacy-updates/"))
            self.assertEqual(asset["version"], original["mod_beta"]["assets"][target]["version"])
            self.assertEqual(asset["sha256"], original["mod_beta"]["assets"][target]["sha256"])
        self.assertEqual(publisher.upload_legacy.call_count, 4)
        self.assertEqual(publisher.fetch.call_count, 4)

    def test_retry_reuses_immutable_attachments(self):
        first = publisher.legacy_manifest(self.update, "test")
        publisher.upload_legacy.reset_mock()
        self.assertEqual(publisher.legacy_manifest(self.update, "test"), first)
        publisher.upload_legacy.assert_not_called()

    def test_wrong_attachment_digest_stops_legacy_publication(self):
        publisher.legacy_manifest(self.update, "test")
        self.release["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaises(ValueError):
            publisher.legacy_manifest(self.update, "test")

    def test_missing_compatibility_release_is_created_as_non_latest_prerelease(self):
        missing = publisher.urllib.error.HTTPError("test", 404, "missing", None, None)
        publisher.request.side_effect = [missing, self.release]
        publisher.legacy_manifest(self.update, "test")
        call = publisher.request.call_args
        body = json.loads(call.args[3])
        self.assertTrue(body["prerelease"])
        self.assertFalse(body["draft"])
        self.assertEqual(body["make_latest"], "false")
        self.assertEqual(body["tag_name"], "legacy-updates")

    def test_formal_release_is_never_used_as_legacy_storage(self):
        self.release["prerelease"] = False
        with self.assertRaises(ValueError):
            publisher.legacy_manifest(self.update, "test")
        publisher.upload_legacy.assert_not_called()


class ReleaseMirrorTests(unittest.TestCase):
    def release(self):
        assets, payloads = [], {}
        for target in publisher.TARGETS:
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps({"id": "skyzh", "version": f"0.8+mc{target}"}))
                jar.writestr("skyzh-build.properties", "channel=release\n")
            payload = stream.getvalue()
            name = f"SkyBlockZH-0.8-Fabric-{target}.jar"
            url = f"https://github.com/BingKKni/SkyBlockZH/releases/download/0.8/{name}"
            assets.append({"name": name, "size": len(payload), "digest": "sha256:" + hashlib.sha256(payload).hexdigest(),
                           "browser_download_url": url})
            payloads[url] = payload
        return {"tag_name": "0.8", "assets": assets}, payloads

    def test_release_is_mirrored_with_verified_digests(self):
        release, payloads = self.release()
        def transfer(url, size, digest):
            payload = payloads[url]
            self.assertEqual((len(payload), hashlib.sha256(payload).hexdigest()), (size, digest))
            return payload
        with patch.dict(publisher.os.environ, {"GITHUB_TOKEN": "test"}), \
                patch.object(publisher, "request", return_value=release), patch.object(publisher, "fetch", side_effect=transfer):
            result, files = publisher.release_assets()
        self.assertEqual(result["tag_name"], "0.8")
        self.assertEqual(len(files), 3)
        for asset, (path, payload) in zip(result["assets"], files):
            self.assertEqual(asset["browser_download_url"], publisher.PAGES + path)
            self.assertEqual(asset["digest"], "sha256:" + hashlib.sha256(payload).hexdigest())

    def test_missing_target_or_bad_digest_blocks_publication(self):
        for mutation in (lambda r: r["assets"].pop(), lambda r: r["assets"][0].update(digest="invalid")):
            release, payloads = self.release()
            mutation(release)
            with patch.dict(publisher.os.environ, {"GITHUB_TOKEN": "test"}), \
                    patch.object(publisher, "request", return_value=release), \
                    patch.object(publisher, "fetch", side_effect=lambda url, *args: payloads[url]), self.assertRaises(ValueError):
                publisher.release_assets()


if __name__ == "__main__":
    unittest.main()
