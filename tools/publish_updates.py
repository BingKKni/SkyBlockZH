#!/usr/bin/env python3
"""Stage translations and Mod builds on the update domain, then verify deployment and record its manifest."""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PAGES = "https://skyblock.xbdqaq.com/"
LEGACY_PAGES = "https://bingkkni.github.io/SkyBlockZH/"
MAX_ZIP = 64 * 1024 * 1024
RETAIN_PREVIOUS = 2
INPUTS = (
    "original_text", "gradle.properties", "build.gradle", "gradle/target.gradle",
    "tools/export_translations.py", "tools/publish_updates.py", ".github/workflows/translations.yml",
    # Test Mod builds: any source or target change publishes a new one.
    "src", "settings.gradle", "gradle/wrapper", "fabric-26.1", "fabric-26.2", "fabric-26.3",
)
TARGETS = ("26.1", "26.2", "26.3")
BETA_VERSION = re.compile(r"[0-9][0-9A-Za-z.-]*\+mc26\.[0-9]+\.beta\.[0-9]+")


def git(*arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def changed(revision: str | None) -> bool:
    if not revision:
        return True
    return subprocess.run(["git", "diff", "--quiet", revision, "HEAD", "--", *INPUTS], cwd=ROOT).returncode != 0


def request(url: str, token: str, method: str = "GET", data: bytes | None = None):
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
               "User-Agent": "SkyZH-Translation-Publisher", "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=120) as response:
        return json.load(response)


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def archive_url(digest: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("无效的 ZIP 摘要。")
    return f"{PAGES}skyzh-updates/{digest}/translations.zip"


def descriptor(update: dict) -> dict:
    size, digest = update["zip_size"], update["zip_sha256"]
    if type(size) is not int or not 0 < size <= MAX_ZIP:
        raise ValueError("无效的翻译包大小。")
    url = archive_url(digest)
    if update["download_url"] not in (url, url.replace(PAGES, LEGACY_PAGES, 1)):
        raise ValueError("翻译包地址不属于项目的 Pages 更新目录。")
    return {"download_url": url, "zip_size": size, "zip_sha256": digest}


def fetch(url: str, size: int, digest: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "SkyZH-Translation-Publisher"})
    with urllib.request.urlopen(req, timeout=60) as response:
        if response.status != 200:
            raise RuntimeError(f"Pages 下载返回 HTTP {response.status}。")
        payload = response.read(size + 1)
    if len(payload) != size or hashlib.sha256(payload).hexdigest() != digest:
        raise RuntimeError("Pages 上的文件大小或摘要不符。")
    return payload


def download(update: dict) -> bytes:
    item = descriptor(update)
    return fetch(update["download_url"], item["zip_size"], item["zip_sha256"])


def beta_url(digest: str, name: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{64}", digest) or not re.fullmatch(r"SkyBlockZH-[0-9A-Za-z.+-]+\.jar", name):
        raise ValueError("无效的测试版文件。")
    return f"{PAGES}skyzh-updates/mod/{digest}/{name}"


def build_beta() -> list[Path]:
    """Builds one test jar per Minecraft target, versioned by commit count."""
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(wrapper), "--console=plain", "-Pskyzh.channel=beta", *[f":fabric-{t}:shadowJar" for t in TARGETS]],
                   cwd=ROOT, check=True)
    jars = []
    for target in TARGETS:
        found = sorted((ROOT / f"fabric-{target}/build/libs").glob(f"SkyBlockZH-*-Fabric-{target}.jar"))
        if len(found) != 1:
            raise RuntimeError(f"{target} 测试版应恰好产出一个 JAR，实际 {len(found)} 个。")
        jars.append(found[0])
    return jars


def beta_assets(jars: list[Path]) -> tuple[dict, list[tuple[str, bytes]]]:
    assets, files = {}, []
    for target, jar in zip(TARGETS, jars):
        payload = jar.read_bytes()
        with zipfile.ZipFile(jar) as bundle:
            version = json.loads(bundle.read("fabric.mod.json"))["version"]
        if not BETA_VERSION.fullmatch(version) or f"+mc{target}." not in version:
            raise RuntimeError(f"测试版版本号不符：{version}")
        digest = hashlib.sha256(payload).hexdigest()
        url = beta_url(digest, jar.name)
        assets[target] = {"version": version, "download_url": url, "size": len(payload), "sha256": digest}
        files.append((url[len(PAGES):], payload))
    return assets, files


def release_assets() -> tuple[dict, list[tuple[str, bytes]]]:
    """Mirror the latest published release; GitHub is contacted only by the publisher."""
    api = "https://api.github.com/repos/BingKKni/SkyBlockZH/releases/latest"
    try:
        release = request(api, os.environ["GITHUB_TOKEN"])
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        return {"tag_name": "0", "prerelease": False, "assets": []}, []
    version = release["tag_name"]
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"[vV]?[0-9]+(?:\.[0-9]+)*", version):
        raise ValueError("正式 Release 版本号或发布状态无效。")
    assets, files, targets = [], [], set()
    for asset in release["assets"]:
        name = asset["name"]
        match = re.fullmatch(r"SkyBlockZH-[0-9A-Za-z.+-]+-Fabric-(26\.[123])\.jar", name)
        if not match:
            continue
        target = match[1]
        if target in targets:
            raise ValueError("正式 Release 含重复的 Minecraft 目标。")
        targets.add(target)
        size = asset["size"]
        digest = asset.get("digest", "").removeprefix("sha256:")
        origin = asset["browser_download_url"]
        if (type(size) is not int or not 0 < size <= 128 * 1024 * 1024
                or not asset.get("digest", "").startswith("sha256:")
                or not re.fullmatch(r"[0-9a-f]{64}", digest)
                or not origin.startswith("https://github.com/BingKKni/SkyBlockZH/releases/download/")
                or origin.rsplit("/", 1)[-1] != name):
            raise ValueError("正式 Release 附件地址、大小或 SHA-256 无效。")
        payload = fetch(origin, size, digest)
        with zipfile.ZipFile(io.BytesIO(payload)) as bundle:
            metadata = json.loads(bundle.read("fabric.mod.json"))
            actual = metadata["version"]
            if (metadata["id"] != "skyzh" or actual != f"{version.lstrip('vV')}+mc{target}"
                    or not re.search(rb"(?m)^channel=release\r?$", bundle.read("skyzh-build.properties"))):
                raise ValueError("正式 Release 附件的 Mod 身份、版本或渠道不符。")
        url = beta_url(digest, name)
        assets.append({"name": name, "size": size, "digest": f"sha256:{digest}", "browser_download_url": url})
        files.append((url[len(PAGES):], payload))
    if targets != set(TARGETS):
        raise ValueError("正式 Release 必须包含全部三个 Minecraft 目标的 JAR。")
    return {"tag_name": version, "prerelease": False, "assets": assets}, files


def output(packaged: bool) -> None:
    destination = os.environ.get("GITHUB_OUTPUT")
    if destination:
        with open(destination, "a", encoding="utf-8") as stream:
            stream.write(f"packaged={'true' if packaged else 'false'}\n")


def prepare(force: bool) -> bool:
    previous_file = ROOT / "skyzh-updates/update.json"
    previous = json.loads(previous_file.read_text(encoding="utf-8")) if previous_file.exists() else {}
    if not force and not changed(previous.get("revision")):
        output(False)
        print("翻译、API 和打包逻辑没有变化，本次无需打包。")
        return False
    source = git("rev-parse", "HEAD")
    subprocess.run([sys.executable, "-B", str(ROOT / "tools/export_translations.py")], cwd=ROOT, check=True)
    assets, beta_files = beta_assets(build_beta())
    release, release_files = release_assets()
    archive = ROOT / "build/translations/translations.zip"
    with zipfile.ZipFile(archive) as bundle:
        manifest = json.loads(bundle.read("manifest.json"))
    if manifest["source_dirty"] or manifest["revision"] != source:
        raise RuntimeError("发布必须来自无未提交打包输入修改的 Git 提交。")
    payload = archive.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    update = dict(manifest, update_format=1, zip_size=len(payload), zip_sha256=digest, download_url=archive_url(digest))
    descriptor(update)
    # A Pages deployment replaces the whole site: carry forward the last two published archives.
    retained: list[tuple[dict, bytes]] = []
    seen = {digest}
    for item in [previous, *previous.get("previous_archives", [])]:
        if not item or item.get("zip_sha256") in seen:
            continue
        # The first migration leaves the legacy Release alone until Pages has been verified.
        if not str(item.get("download_url", "")).startswith((PAGES, LEGACY_PAGES)):
            continue
        entry = descriptor(item)
        retained.append((entry, download(item)))
        seen.add(entry["zip_sha256"])
        if len(retained) == RETAIN_PREVIOUS:
            break
    update["previous_archives"] = [entry for entry, _ in retained]
    update["mod_beta"] = {"revision": source, "assets": assets}
    update["mod_release"] = release
    # Clients may still be downloading the previous test build; keep its files for one more deployment.
    current = {asset["sha256"] for asset in assets.values()}
    for asset in previous.get("mod_beta", {}).get("assets", {}).values():
        if asset.get("sha256") in current or not str(asset.get("download_url", "")).startswith((PAGES, LEGACY_PAGES)):
            continue
        name = asset["download_url"].rsplit("/", 1)[-1]
        url = beta_url(asset["sha256"], name)
        if asset["download_url"] not in (url, url.replace(PAGES, LEGACY_PAGES, 1)):
            raise ValueError("旧测试版地址不属于项目更新目录。")
        beta_files.append((url[len(PAGES):], fetch(asset["download_url"], asset["size"], asset["sha256"])))
    # Retain the last formal release while clients finish downloads from an older manifest.
    current_release = {asset["digest"] for asset in release["assets"]}
    retained_release = []
    for asset in [*previous.get("mod_release", {}).get("assets", []), *previous.get("previous_release_assets", [])]:
        if asset["digest"] in current_release or any(old["name"].rsplit("-Fabric-", 1)[-1] == asset["name"].rsplit("-Fabric-", 1)[-1]
                                                     for old in retained_release):
            continue
        digest = asset["digest"].removeprefix("sha256:")
        url = beta_url(digest, asset["name"])
        if asset["browser_download_url"] != url:
            raise ValueError("旧正式版地址不属于更新站点。")
        release_files.append((url[len(PAGES):], fetch(url, asset["size"], digest)))
        retained_release.append(asset)
    update["previous_release_assets"] = retained_release
    staging = ROOT / "build/pages"
    if staging.exists():
        shutil.rmtree(staging)
    for entry, data in [(descriptor(update), payload), *retained]:
        path = staging / "skyzh-updates" / entry["zip_sha256"] / "translations.zip"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    (staging / "skyzh-updates/translations.zip").write_bytes(payload)
    for relative, data in [*beta_files, *release_files]:
        path = staging / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    (staging / "skyzh-updates/update.json").write_bytes(encode(update) + b"\n")
    (staging / "skyzh-updates/release.json").write_bytes(encode(release) + b"\n")
    (staging / "CNAME").write_text("skyblock.xbdqaq.com\n", encoding="utf-8")
    (staging / "index.html").write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>SkyZH 翻译文件</title>'
        '<h1>SkyZH 翻译文件</h1><p><a href="skyzh-updates/translations.zip">下载最新翻译包</a></p>'
        '<p><a href="https://github.com/BingKKni/SkyBlockZH">返回项目</a></p></html>\n', encoding="utf-8")
    candidate = ROOT / "build/updates/update.json"
    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.write_bytes(encode(update) + b"\n")
    output(True)
    print(f"Pages 文件已准备：{len(payload):,} 字节，保留 {len(retained)} 份旧包，测试版 {len(beta_files)} 个 JAR。")
    return True


def publish() -> bool:
    repository = os.environ.get("GITHUB_REPOSITORY", "BingKKni/SkyBlockZH")
    if repository != "BingKKni/SkyBlockZH":
        raise RuntimeError("只允许向正式 SkyBlockZH 仓库发布更新。")
    token = os.environ["GITHUB_TOKEN"]
    api = f"https://api.github.com/repos/{repository}"
    candidate = ROOT / "build/updates/update.json"
    update = json.loads(candidate.read_text(encoding="utf-8"))
    descriptor(update)
    source = update["revision"]
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", source) or update.get("source_dirty") is not False:
        raise RuntimeError("待发布清单的来源信息无效。")
    # Deployment completion may precede CDN propagation. Do not publish an unusable URL.
    for attempt in range(6):
        try:
            download(update)
            for asset in update["mod_beta"]["assets"].values():
                fetch(asset["download_url"], asset["size"], asset["sha256"])
            for asset in update["mod_release"]["assets"]:
                fetch(asset["browser_download_url"], asset["size"], asset["digest"].removeprefix("sha256:"))
            break
        except (OSError, RuntimeError):
            if attempt == 5:
                raise
            time.sleep(5)
    content = encode(update) + b"\n"
    head = request(f"{api}/git/ref/heads/main", token)["object"]["sha"]
    if head != source:
        subprocess.run(["git", "fetch", "origin", "main"], cwd=ROOT, check=True)
        if subprocess.run(["git", "diff", "--quiet", source, head, "--", *INPUTS], cwd=ROOT).returncode != 0:
            print("构建期间打包输入已有新提交，保留原更新清单；后续 Actions 会处理新提交。")
            return False
    blob = request(f"{api}/git/blobs", token, "POST", encode({"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"}))
    tree = request(f"{api}/git/trees", token, "POST", encode({
        "base_tree": request(f"{api}/git/commits/{head}", token)["tree"]["sha"],
        "tree": [{"path": "skyzh-updates/update.json", "mode": "100644", "type": "blob", "sha": blob["sha"]}],
    }))
    commit = request(f"{api}/git/commits", token, "POST", encode({
        "message": "发布自动翻译更新", "tree": tree["sha"], "parents": [head],
    }))
    request(f"{api}/git/refs/heads/main", token, "PATCH", encode({"sha": commit["sha"], "force": False}))
    print(f"翻译与测试版更新已发布：{update['download_url']}（{update['zip_size']:,} 字节）")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="准备 Pages 翻译文件，部署并验证后再发布更新清单。")
    phase = parser.add_mutually_exclusive_group(required=True)
    phase.add_argument("--prepare", action="store_true", help="生成待部署的 Pages 文件")
    phase.add_argument("--publish", action="store_true", help="验证已部署文件并提交更新清单")
    parser.add_argument("--force", action="store_true", help="立即打包，仅与 --prepare 一起使用")
    args = parser.parse_args()
    if args.force and not args.prepare:
        parser.error("--force 只能与 --prepare 一起使用")
    try:
        if args.prepare:
            prepare(args.force)
        else:
            publish()
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"发布失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
