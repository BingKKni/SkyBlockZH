#!/usr/bin/env python3
"""Publish verified translation ZIPs as GitHub Release assets, committing only a small update.json."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INPUTS = (
    "original_text", "gradle.properties", "build.gradle", "gradle/target.gradle",
    "tools/export_translations.py", "tools/publish_updates.py", ".github/workflows/translations.yml",
)


def git(*arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def changed(revision: str | None) -> bool:
    if not revision:
        return True
    result = subprocess.run(["git", "diff", "--quiet", revision, "HEAD", "--", *INPUTS], cwd=ROOT)
    if result.returncode not in (0, 1):
        return True  # Unknown history: rebuild rather than silently missing a publication.
    return result.returncode == 1


def request(url: str, token: str, method: str = "GET", data: bytes | None = None,
            content_type: str = "application/vnd.github+json"):
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
               "User-Agent": "SkyZH-Translation-Publisher", "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        headers["Content-Type"] = content_type
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=120) as response:
        return json.load(response)


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def publish(force: bool) -> None:
    repository = os.environ.get("GITHUB_REPOSITORY", "BingKKni/SkyBlockZH")
    if repository != "BingKKni/SkyBlockZH":
        raise RuntimeError("只允许向正式 SkyBlockZH 仓库发布更新。")
    token = os.environ["GITHUB_TOKEN"]
    api = f"https://api.github.com/repos/{repository}"
    source = git("rev-parse", "HEAD")
    metadata_file = ROOT / "skyzh-updates/update.json"
    previous = json.loads(metadata_file.read_text(encoding="utf-8")) if metadata_file.exists() else {}
    if not force and not changed(previous.get("revision")):
        print("翻译、API 和打包逻辑没有变化，本次无需打包。")
        return
    subprocess.run([sys.executable, "-B", str(ROOT / "tools/export_translations.py")], cwd=ROOT, check=True)
    archive = ROOT / "build/translations/translations.zip"
    with zipfile.ZipFile(archive) as bundle:
        manifest = json.loads(bundle.read("manifest.json"))
    if manifest["source_dirty"] or manifest["revision"] != source:
        raise RuntimeError("发布必须来自无未提交打包输入修改的 Git 提交。")
    payload = archive.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    # Include the archive hash so a manually forced rebuild of the same source remains immutable.
    tag = f"translations-{source[:12]}-{digest[:12]}"
    try:
        release = request(f"{api}/releases/tags/{tag}", token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = request(f"{api}/releases", token, "POST", encode({
            "tag_name": tag, "target_commitish": source, "name": f"翻译包 {manifest['build_at_utc8']}",
            "body": "由 GitHub Actions 自动校验和发布的完整翻译包。Mod 本体请下载正式发布中的 JAR。",
            "prerelease": True, "make_latest": "false",
        }))
    assets = release.get("assets", [])
    asset = next((item for item in assets if item["name"] == "translations.zip"), None)
    if asset is None:
        upload = release["upload_url"].split("{", 1)[0] + "?name=translations.zip"
        asset = request(upload, token, "POST", payload, "application/zip")
    if asset["size"] != len(payload) or asset.get("digest") != f"sha256:{digest}":
        raise RuntimeError("GitHub 未提供附件摘要，或已上传附件与本次构建不符，停止发布。")
    update = dict(manifest, update_format=1, zip_size=len(payload), zip_sha256=digest,
                  download_url=asset["browser_download_url"])
    content = encode(update) + b"\n"
    # Git data API publishes only update.json and rejects non-fast-forward writes. Never force-push main.
    head = request(f"{api}/git/ref/heads/main", token)["object"]["sha"]
    if head != source:
        # Another push may have arrived while Gradle was running. Permit unrelated edits only.
        subprocess.run(["git", "fetch", "origin", "main"], cwd=ROOT, check=True)
        if changed_between(source, head):
            print("构建期间翻译或打包逻辑已有新提交，跳过旧版本发布；后续 Actions 会处理新提交。")
            return
    blob = request(f"{api}/git/blobs", token, "POST", encode({"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"}))
    tree = request(f"{api}/git/trees", token, "POST", encode({
        "base_tree": request(f"{api}/git/commits/{head}", token)["tree"]["sha"],
        "tree": [{"path": "skyzh-updates/update.json", "mode": "100644", "type": "blob", "sha": blob["sha"]}],
    }))
    commit = request(f"{api}/git/commits", token, "POST", encode({
        "message": "发布自动翻译更新", "tree": tree["sha"], "parents": [head],
    }))
    request(f"{api}/git/refs/heads/main", token, "PATCH", encode({"sha": commit["sha"], "force": False}))
    print(f"翻译包已发布：{asset['browser_download_url']}（{len(payload):,} 字节，API {manifest['translation_api']}）")


def changed_between(before: str, after: str) -> bool:
    return subprocess.run(["git", "diff", "--quiet", before, after, "--", *INPUTS], cwd=ROOT).returncode != 0


def main() -> int:
    parser = argparse.ArgumentParser(description="自动发布翻译 ZIP 和 skyzh-updates/update.json。")
    parser.add_argument("--force", action="store_true", help="即使打包输入未变，也立即打包并发布")
    args = parser.parse_args()
    try:
        publish(args.force)
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"发布失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
