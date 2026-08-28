#!/usr/bin/env python3
"""Build GUI_Item corpus files from a NotEnoughUpdates-REPO checkout.

    python3 tools/neu_to_corpus.py <repo> <ids.txt> <category>

Writes ``original_text/<category>/GUI_Item/<Item Name>.json`` for every id, plus
``original_text/_shared/Item_Lore.json`` for the lines more than one item shares. Anything already
translated is carried across by matching the English, so running this over a category that has been
worked on by hand does not throw that work away — it only fills in what NEU knows and the corpus
did not.

**Every number in a lore line becomes a placeholder; numbers in an item's name do not.** Hypixel
rebalances numbers constantly and NEU is a snapshot, so a record that spells one out is a record
that stops matching on the next patch. Templating them also collapses `Damage: +65` and
`Damage: +75` into one line to translate instead of two.

The obvious worry — that `Pickonimbus 2000` becomes a template matching things it should not — does
not apply, because a `number` placeholder only ever matches digits (see `text/Capture.java`). So
`Pickonimbus %s` matches "Pickonimbus 2000" and nothing else, and the value is copied through
verbatim either way. An item's *name* is left alone regardless, since a model number there is part
of what the thing is called.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki_to_corpus as w

NUMBER = re.compile(r"[+-]?\d[\d,.]*%?")
CODE = re.compile(r"§[0-9a-fk-or]", re.IGNORECASE)
SHARED = "_shared/Item_Lore.json"


def strip(text):
    return CODE.sub("", text)


def templated(line):
    """A lore line with every number replaced by a placeholder, colour codes stepped over."""
    out, i = [], 0

    while i < len(line):
        if line[i] == "§" and i + 1 < len(line):
            out.append(line[i:i + 2])
            i += 2
            continue

        match = NUMBER.match(line, i)

        if match:
            out.append("%s")
            i = match.end()
            continue

        out.append(line[i])
        i += 1

    return "".join(out)


def existing_records():
    """Everything already translated, keyed by its English."""
    known = {}

    for path in sorted(p for p in _walk("original_text") if p.endswith(".json")):
        try:
            doc = json.load(open(path, encoding="utf-8"))
        except Exception:
            continue

        def visit(o):
            if isinstance(o, dict):
                if "text" in o and "id" in o:
                    known.setdefault(o["text"], o)
                for key, value in o.items():
                    if key != "segments":
                        visit(value)
            elif isinstance(o, list):
                for value in o:
                    visit(value)

        visit(doc)

    return known


def _walk(root):
    for base, _, files in os.walk(root):
        for name in files:
            yield os.path.join(base, name)


def carry(record, known):
    """Copy a finished translation onto a freshly generated record."""
    old = known.get(record["text"])

    if old is None:
        return record

    for key in ("context", "gloss", "zh", "continuation", "layout"):
        if old.get(key):
            record[key] = old[key]

    if old.get("translate") is False:
        record["translate"] = False

    if old.get("segments") and record.get("segments"):
        if len(old["segments"]) == len(record["segments"]):
            for new, prev in zip(record["segments"], old["segments"]):
                if prev.get("zh"):
                    new["zh"] = prev["zh"]
                if prev.get("omit"):
                    new["omit"] = True
    elif old.get("segments") and not record.get("segments"):
        record["segments"] = old["segments"]

    return record


def make(rid, raw, known):
    plain = strip(raw)
    record = {
        "id": rid,
        "context": "",
        "raw": raw,
        "text": plain,
        "placeholders": [
            {"token": "%s", "desc": "数值", "type": "number", "example": ""}
        ] * plain.count("%s"),
        "gloss": "",
        "translate": True,
        "zh": "",
    }
    parts = w.segments(raw)

    if parts:
        record["segments"] = parts

    if not strip(plain).replace("%s", "").strip():
        # A blank line, or a line that is nothing but its value: real layout, nothing to translate.
        record["translate"] = False
        record["context"] = "空行/纯数值行,是 Lore 的排版,不翻译但必须保留以保持行序"

    return carry(record, known)


def slug(text, used, prefix):
    words = re.sub(r"[^a-z0-9]+", " ", strip(text).lower()).split()
    base = prefix + "_" + ("_".join(words[:5]) or "blank")
    name, n = base, 2

    while name in used:
        name, n = f"{base}_{n}", n + 1

    used.add(name)
    return name


if __name__ == "__main__":
    repo, ids_file, category = sys.argv[1], sys.argv[2], sys.argv[3]
    ids = [line.strip() for line in open(ids_file, encoding="utf-8") if line.strip()]
    known = existing_records()

    # Pass one: which templated lines more than one item shares.
    groups, per_item = {}, {}

    for item_id in ids:
        path = os.path.join(repo, "items", item_id + ".json")

        if not os.path.exists(path):
            continue

        item = json.load(open(path, encoding="utf-8"))
        per_item[item_id] = item

        for line in item.get("lore", []):
            groups.setdefault(templated(line), set()).add(item_id)

    shared_of = {}
    shared, used = [], set()

    for key, items in sorted(groups.items()):
        if len(items) < 2:
            continue

        raw = key

        if not strip(raw).replace("%s", "").strip():
            continue  # blank lines stay local; they are layout, not a shared sentence

        rid = slug(raw, used, "item")
        shared.append(make(rid, raw, known))
        shared_of[key] = rid

    os.makedirs("original_text/_shared", exist_ok=True)
    json.dump({
        "purpose": "跨物品重复出现的 Lore 行。同一句话在几十件物品上一字不差地出现(如 'This item can be reforged!' 出现在 32 件上),按 README §5.6 抽到这里翻一次,物品文件用 ref 引用,不要各抄一份。",
        "source": "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO (items/*.json 的 lore 数组,带颜色码的精确原文)",
        "fetched_at": "2026-08-19",
        "verified_ingame": False,
        "lines": shared,
    }, open("original_text/" + SHARED, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    out_dir = f"original_text/{category}/GUI_Item"
    os.makedirs(out_dir, exist_ok=True)
    written = 0

    for item_id, item in per_item.items():
        display = item.get("displayname", item_id)
        plain_name = strip(display)
        prefix = re.sub(r"[^a-z0-9]+", "_", plain_name.lower()).strip("_")
        used_ids = set()
        lore = []

        for index, line in enumerate(item.get("lore", [])):
            key = raw = templated(line)

            if key in shared_of:
                lore.append({
                    "id": slug(raw, used_ids, prefix),
                    "ref": SHARED + "#" + shared_of[key],
                    "context": "",
                })
                continue

            lore.append(make(slug(raw, used_ids, prefix), raw, known))

        # The name keeps its numbers: DR-X455 is what the drill is called, not a value.
        name_record = make(prefix + "_name", display, known)
        name_record["context"] = name_record["context"] or "物品名称"

        path = os.path.join(out_dir, re.sub(r"[^\w\-' ]", "", plain_name).replace(" ", "_") + ".json")
        json.dump({
            "item_id": item_id,
            "source": "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/blob/master/items/%s.json" % item_id,
            "fetched_at": "2026-08-19",
            "verified_ingame": False,
            "name": name_record,
            "lore": lore,
        }, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        written += 1

    print(f"物品文件 {written} 个,共享行 {len(shared)} 条")
