#!/usr/bin/env python3
"""Summarise a logs/skyzh-capture tree: how many records each bucket holds, and what they say.

The runtime capture writes three buckets — untranslated/ (no record answered the line),
mixed/ (the record answered but the result still has Latin words in it) and colour/ (the
record answered as one flat colour where the live line changed colour mid-line). This
prints them so the next translation pass can be planned instead of guessed at.
"""
import json
import glob
import os
import sys
from collections import defaultdict

# SkyBlock text is full of glyphs (☠ ᠅ ✦) that the zh-CN default console codepage cannot
# encode, and Python raises rather than dropping them. Say UTF-8 explicitly instead.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def records(doc):
    out = []
    for key, value in doc.items():
        if isinstance(value, list):
            for element in value:
                if isinstance(element, dict) and "id" in element:
                    out.append(element)
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    mode = sys.argv[2] if len(sys.argv) > 2 else "summary"
    os.chdir(root)

    by_bucket = defaultdict(int)
    by_file = defaultdict(int)

    for path in sorted(glob.glob("**/*.json", recursive=True)):
        rel = path.replace("\\", "/")
        bucket = rel.split("/")[0]
        doc = json.load(open(path, encoding="utf-8"))
        rs = records(doc)
        by_bucket[bucket] += len(rs)
        by_file[bucket] += 1

        if mode == "dump" and bucket == sys.argv[3]:
            print(f"\n##### {rel}  ({len(rs)} records)")
            for r in rs:
                print(f"  [{r.get('id','?')}] {r.get('text','')!r}")

    if mode == "summary":
        for bucket in sorted(by_bucket):
            print(f"{bucket:15s} {by_bucket[bucket]:5d} records across {by_file[bucket]:3d} files")
        print(f"{'TOTAL':15s} {sum(by_bucket.values()):5d}")


if __name__ == "__main__":
    main()
