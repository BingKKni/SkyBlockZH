#!/usr/bin/env python3
"""Export and verify the same minified corpus shipped in SkyZH jars."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main() -> int:
    parser = argparse.ArgumentParser(description="导出并校验 SkyZH 完整翻译包（需要项目的 Java/Gradle 环境）。")
    parser.add_argument("--output", type=Path, help="可选的目标 ZIP 文件路径；默认留在 build/translations/translations.zip")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        subprocess.run([str(wrapper), "--console=plain", "packTranslations", "checkTranslationPack"],
                       cwd=root, check=True)
        source = root / "build/translations/translations.zip"
        target = args.output.expanduser().resolve() if args.output else source
        if target != source:
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name + ".", suffix=".tmp", delete=False) as stream:
                    temporary = Path(stream.name)
                    with source.open("rb") as incoming:
                        shutil.copyfileobj(incoming, stream)
                os.replace(temporary, target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        print(f"翻译包已导出并通过校验：{target}")
        print("放入当前 Minecraft 实例的 skyzh-translations/translations.zip，执行 /skyzh reload 或 /skyblockzh reload。")
        return 0
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"导出失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
