#!/usr/bin/env python3
"""Group the untranslated capture bucket by where the text is, so a translation pass can be scoped.

The capture writes one file per menu, which is the right shape for staging into original_text/ but
the wrong shape for deciding what to do next: 100 files of one to two hundred records each says
nothing about which of them are worth an afternoon. This sorts them by how often the lines were
actually seen on screen, which is what the `_capture.count` field records.
"""
import json
import glob
import os
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "logs/skyzh-capture/untranslated"
    os.chdir(root)

    by_surface = defaultdict(lambda: [0, 0])   # surface -> [records, total sightings]
    rows = []

    for path in sorted(glob.glob("**/*.json", recursive=True)):
        rel = path.replace("\\", "/")
        parts = rel.split("/")
        surface = parts[1] if len(parts) > 2 else "?"
        doc = json.load(open(path, encoding="utf-8"))

        records = 0
        sightings = 0
        for key, value in doc.items():
            if not isinstance(value, list):
                continue
            for r in value:
                if isinstance(r, dict) and "id" in r:
                    records += 1
                    sightings += (r.get("_capture") or {}).get("count", 0)

        if records:
            by_surface[surface][0] += records
            by_surface[surface][1] += sightings
            rows.append((sightings, records, rel))

    print("=== by render surface ===")
    for surface, (records, sightings) in sorted(by_surface.items(), key=lambda kv: -kv[1][1]):
        print(f"  {surface:12s} {records:5d} records, seen {sightings:6d} times")

    print("\n=== the menus worth doing first (most sightings) ===")
    for sightings, records, rel in sorted(rows, reverse=True)[:20]:
        print(f"  seen {sightings:6d}x  {records:4d} records  {rel}")

    print(f"\ntotal: {sum(r for r, _ in by_surface.values())} records across {len(rows)} files")


if __name__ == "__main__":
    main()
