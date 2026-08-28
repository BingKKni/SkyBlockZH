#!/usr/bin/env python3
"""Print corpus records by id, across the whole corpus. Usage: show_record.py <id> [<id>...]"""
import json
import glob
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

os.chdir(os.path.join(os.path.dirname(__file__), "..", "original_text"))
wanted = set(sys.argv[1:])

for path in sorted(glob.glob("**/*.json", recursive=True)):
    doc = json.load(open(path, encoding="utf-8"))
    for key, value in doc.items():
        if not isinstance(value, list):
            continue
        for r in value:
            if isinstance(r, dict) and r.get("id") in wanted:
                print(f"----- {path.replace(chr(92), '/')}  [{key}]")
                print(json.dumps(r, ensure_ascii=False, indent=2))
