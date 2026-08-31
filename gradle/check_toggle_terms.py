#!/usr/bin/env python3
"""Check that a term and the record it cross-references say the same thing.

Some names live in two places on purpose:

  * a record — the menu row itself, "✔ Death Messages", a whole line the corpus spells out
  * a term   — the same name as a *value*, for a template like "%1$s are now disabled!"

Neither can be derived from the other, so the pair can drift: somebody improves the menu translation
and the chat line keeps saying the old thing, leaving one setting with two Chinese names. That is
worse than either translation alone, and nothing else in the build notices it.

Any term whose note points at a record — "<some/path/File.json>#<record_id>" — is checked. When the
record it names is a template (it has placeholders), the term is understood to *fill* that template
rather than duplicate it, and only the record's existence is checked: "Personal" going into
"%1$s Settings" is not supposed to match it.

Exit status is 1 if any pair disagrees, so this can go in a hook or a build task.
"""
import io
import os
import re
import sys
import json

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS = os.path.join(ROOT, "original_text")
TERMS = os.path.join(CORPUS, "_shared", "Terms.json")

# "Hub_General/GUI_Item/Settings.json#death_messages" — the path must be spelled out from the
# corpus root, so that Settings.json and Garden_Settings.json cannot be mistaken for each other.
#
# Hyphens and apostrophes belong in the character class because real corpus files carry them —
# `Bits_Shop_-_Upgrade_Components.json`, `Will-o'-wisp.json`, `Amber-Polished_Drill_Engine.json`.
# Without them the match simply starts after the hyphen, so a correct pointer is read as the
# non-existent `_Upgrade_Components.json` and reported as a broken reference: a false failure that
# pushes whoever hits it into deleting a perfectly good cross-reference.
POINTER = re.compile(r"([A-Za-z0-9_'-]+(?:/[A-Za-z0-9_'-]+)*\.json)#([a-z0-9_]+)")


def records_of(relative):
    """Every record in one corpus file, by id. The array key differs between files.

    A file may hold more than one record array — SkyBlock_Menu.json splits itself into
    `lore` / `menu_lines` / `garden_lines`, Pity.json into `names` / `lore`, and
    Professor_Robot.json into `messages` / `superseded`. Reading only the first array
    made a pointer into any of the others look like a pointer at nothing, which is how
    the Bingo Account term got reported as dangling when the record it names has been
    in `menu_lines` all along. So collect from every array.
    """
    path = os.path.join(CORPUS, *relative.split("/"))

    if not os.path.exists(path):
        return None

    doc = json.load(io.open(path, encoding="utf-8"))
    found = {}

    for key in doc:
        if not isinstance(doc[key], list) or not doc[key]:
            continue

        for record in doc[key]:
            if isinstance(record, dict) and "id" in record:
                found.setdefault(record["id"], record)

    return found


def main():
    terms = json.load(io.open(TERMS, encoding="utf-8"))["terms"]
    cache = {}
    checked = 0
    filled = 0
    problems = []

    for term in terms:
        for relative, record_id in POINTER.findall(term.get("note") or ""):
            if relative not in cache:
                cache[relative] = records_of(relative)

            records = cache[relative]

            if records is None:
                problems.append(
                    "词条 %s 的注释指向 %s，但这个文件不存在" % (term["en"], relative)
                )
                continue

            record = records.get(record_id)

            if record is None:
                problems.append(
                    "词条 %s 指向 %s#%s，但那条记录不存在" % (term["en"], relative, record_id)
                )
                continue

            if record.get("placeholders"):
                # The term fills this template rather than duplicating it.
                filled += 1
                continue

            checked += 1

            if record.get("text") != term["en"]:
                problems.append(
                    "词条 %s 指向的记录 %s#%s 英文是 %r，两边对不上"
                    % (term["en"], relative, record_id, record.get("text"))
                )

            if (record.get("zh") or "") != term["zh"]:
                problems.append(
                    "%s 的译名不一致：记录 %s#%s 写 %r，词表写 %r"
                    % (term["en"], relative, record_id, record.get("zh") or "", term["zh"])
                )

    print("检查了 %d 对同名的词条和记录，另有 %d 条词条是填进模板的（不比对）。" % (checked, filled))

    if problems:
        print("\n发现 %d 处不一致：" % len(problems))
        for p in problems:
            print("  -", p)
        print("\n改译名时两边一起改：改完再跑一次这个脚本应当是 0 处。")
        return 1

    print("两边完全一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
