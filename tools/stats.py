#!/usr/bin/env python
"""Count corpus entries and translated share, per the README's stated method.

Counting rule (README.md footnote): walk original_text/<gameplay>/<surface>/*.json,
an entry is any object carrying both `id` and `text`; `ref`-only entries and the
glossaries do not count. Translated = non-empty `zh`, or every `segments[].zh`
filled, or `continuation: true`. Entries with `translate: false` are excluded from
the denominator — they are English on purpose.
"""
import json
import os
import sys
from collections import defaultdict

# The labels below are Chinese, and a zh-CN Windows console defaults to code page 936, which Python
# then uses for stdout — every one of them comes out as mojibake. Say UTF-8 explicitly.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "original_text")


def entries(node):
    if isinstance(node, dict):
        if "id" in node and "text" in node:
            yield node
        for value in node.values():
            yield from entries(value)
    elif isinstance(node, list):
        for value in node:
            yield from entries(value)


def translated(entry):
    if entry.get("continuation"):
        return True
    if (entry.get("zh") or "").strip():
        return True
    segments = entry.get("segments")
    if segments:
        return all((s.get("zh") or "").strip() or s.get("omit") for s in segments)
    return False


# "已收录" counts every entry, including the ones kept in English on purpose; the percentage
# leaves those out of the denominator. Two different numbers, both quoted in the README.
collected = defaultdict(int)
countable = defaultdict(int)
done = defaultdict(int)

for base, _, names in os.walk(ROOT):
    for name in sorted(names):
        if not name.endswith(".json"):
            continue
        path = os.path.join(base, name)
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        gameplay = rel.split("/")[0]
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        for entry in entries(data):
            collected[gameplay] += 1
            if entry.get("translate") is False:
                continue
            countable[gameplay] += 1
            if translated(entry):
                done[gameplay] += 1

for gameplay in sorted(collected, key=lambda g: -collected[g]):
    n, c, d = collected[gameplay], countable[gameplay], done[gameplay]
    print(f"{gameplay:16} 已收录 {n:5d}   计入分母 {c:5d}   已翻 {d:5d}   {d * 100 // c if c else 0}%")
print(f"{'合计':14} 已收录 {sum(collected.values()):5d}   已翻 {sum(done.values()):5d}")
