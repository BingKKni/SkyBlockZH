#!/usr/bin/env python
"""Explore SkyZH runtime-capture files.

Usage:
  python tools/cap.py grep <regex> [path-substring]   # search text/raw of every captured line
  python tools/cap.py show <file> [regex]             # dump lines of one capture file
  python tools/cap.py files [path-substring]          # list capture files w/ line counts
"""
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs", "skyzh-capture")


def walk(path_filter=""):
    for base, _, names in os.walk(ROOT):
        for n in names:
            if not n.endswith(".json"):
                continue
            p = os.path.join(base, n)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            if path_filter and path_filter.lower() not in rel.lower():
                continue
            yield rel, p


def load(p):
    with open(p, encoding="utf-8") as fh:
        return json.load(fh)


def esc(s):
    return s.replace("§", "&")


def cmd_grep(pattern, path_filter=""):
    rx = re.compile(pattern, re.I)
    hits = 0
    for rel, p in walk(path_filter):
        try:
            d = load(p)
        except Exception as e:
            print(f"!! {rel}: {e}")
            continue
        for ln in d.get("lines", []):
            blob = (ln.get("text", "") or "") + "\n" + (ln.get("raw", "") or "")
            if rx.search(blob):
                hits += 1
                print(f"--- {rel}  id={ln.get('id')}")
                print(f"    text: {ln.get('text')!r}")
                if ln.get("raw") and ln.get("raw") != ln.get("text"):
                    print(f"    raw : {esc(ln.get('raw'))!r}")
                cap = ln.get("_capture", {})
                if cap.get("where"):
                    print(f"    where: {cap.get('where')}  count={cap.get('count')}")
                if cap.get("matched_record"):
                    print(f"    matched: {cap.get('matched_file')}#{cap.get('matched_record')}")
                if cap.get("fix"):
                    print(f"    fix: {cap.get('fix')}")
                if cap.get("observed"):
                    for k, v in cap["observed"].items():
                        print(f"    observed {k}: {v[:8]}")
                if ln.get("segments"):
                    for s in ln["segments"]:
                        print(f"      seg {s.get('color','')!r} {s.get('text')!r} zh={s.get('zh','')!r}")
    print(f"\n== {hits} hit(s)")


def cmd_show(f, pattern=None):
    rx = re.compile(pattern, re.I) if pattern else None
    for rel, p in walk(f):
        d = load(p)
        print("=" * 30, rel)
        for ln in d.get("lines", []):
            if rx and not rx.search((ln.get("text", "") or "") + (ln.get("raw", "") or "")):
                continue
            print(f"  id={ln.get('id')}")
            print(f"    text: {ln.get('text')!r}")
            if ln.get("raw") and ln.get("raw") != ln.get("text"):
                print(f"    raw : {esc(ln.get('raw'))!r}")
            cap = ln.get("_capture", {})
            if cap.get("matched_record"):
                print(f"    matched: {cap.get('matched_file')}#{cap.get('matched_record')}")
            if cap.get("fix"):
                print(f"    fix: {cap.get('fix')}")
            if cap.get("observed"):
                for k, v in cap["observed"].items():
                    print(f"    observed {k}: {v[:8]}")
            if ln.get("segments"):
                for s in ln["segments"]:
                    print(f"      seg {s.get('color','')!r} {s.get('text')!r} zh={s.get('zh','')!r}")


def cmd_files(path_filter=""):
    for rel, p in sorted(walk(path_filter)):
        try:
            d = load(p)
            print(f"{len(d.get('lines', [])):5d}  {rel}")
        except Exception as e:
            print(f"    ?  {rel}  ({e})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    c = sys.argv[1]
    if c == "grep":
        cmd_grep(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "")
    elif c == "show":
        cmd_show(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif c == "files":
        cmd_files(sys.argv[2] if len(sys.argv) > 2 else "")
    else:
        print(__doc__)
