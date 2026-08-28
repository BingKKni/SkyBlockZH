#!/usr/bin/env python3
"""Print full capture records (raw/text/segments) for one bucket, so a translator can see
exactly what the runtime saw. Companion to capture_report.py, which only counts.

Usage: capture_show.py <capture-root> <bucket> [substring-of-path]
"""
import json
import glob
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main():
    root, bucket = sys.argv[1], sys.argv[2]
    needle = sys.argv[3] if len(sys.argv) > 3 else ""
    os.chdir(root)

    for path in sorted(glob.glob(f"{bucket}/**/*.json", recursive=True)):
        rel = path.replace("\\", "/")
        if needle and needle not in rel:
            continue
        doc = json.load(open(path, encoding="utf-8"))
        printed = False
        for key, value in doc.items():
            if not isinstance(value, list):
                continue
            for r in value:
                if not isinstance(r, dict) or "id" not in r:
                    continue
                if not printed:
                    print(f"\n=========== {rel}")
                    printed = True
                print(f"\n  id      : {r.get('id')}")
                print(f"  raw     : {r.get('raw','')}")
                print(f"  text    : {r.get('text','')}")
                if r.get("_capture"):
                    print(f"  capture : {json.dumps(r['_capture'], ensure_ascii=False)}")
                for name in ("context", "gloss", "zh", "continuation", "layout"):
                    if r.get(name):
                        print(f"  {name:8s}: {r[name]}")
                for i, s in enumerate(r.get("segments", []) or []):
                    extra = "".join(
                        f" {k}={s[k]}" for k in ("omit", "order") if k in s
                    )
                    print(f"    seg[{i}] {s.get('color','')!r} {s.get('text','')!r} -> {s.get('zh','')!r}{extra}")
                for p in r.get("placeholders", []) or []:
                    print(f"    ph {p.get('token')} type={p.get('type')} eg={p.get('example')!r}")


if __name__ == "__main__":
    main()
