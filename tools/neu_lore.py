#!/usr/bin/env python3
"""Read exact item names and lore out of a NotEnoughUpdates-REPO checkout.

The wiki renders an item's tooltip from a template, so its wikitext holds the *stats* but never the
strings the game actually draws. NEU-REPO holds the strings: every item's ``lore`` array exactly as
Hypixel sends it, colour codes included. For GUI_Item that makes it the right source and the wiki
the fallback, which is what original_text/SOURCES.md said all along.

    python3 tools/neu_lore.py <repo> ids <ids.txt>       print name + lore for each id
    python3 tools/neu_lore.py <repo> lines <ids.txt>     distinct lore lines, numbers turned into
                                                        %s, most-repeated first

The second form is the one that matters. Lore repeats enormously across items — every drill says
"This item can be reforged!" — so the number of *distinct sentences* to translate is a small
fraction of the number of lore lines, and templating the numbers out shrinks it again.
"""

import json
import os
import re
import sys

# 1,234 / +75 / 20% / 3k — the parts of a lore line that are values rather than words.
NUMBER = re.compile(r"[+-]?\d[\d,.]*%?")
CODE = re.compile(r"§[0-9a-fk-or]", re.IGNORECASE)


def load(repo, item_id):
    path = os.path.join(repo, "items", item_id + ".json")

    if not os.path.exists(path):
        return None

    return json.load(open(path, encoding="utf-8"))


def template(line):
    """A lore line with its numbers replaced by placeholders.

    Colour codes are stepped over rather than substituted: the 7 in §7 is a colour, not a value, and
    templating it turns every grey line in the game into the same unreadable pattern.
    """
    out, i = [], 0

    while i < len(line):
        if line[i] == "\u00a7" and i + 1 < len(line):
            out.append(line[i:i + 2])
            i += 2
            continue

        match = NUMBER.match(line, i)

        if match and match.group():
            out.append("%s")
            i = match.end()
            continue

        out.append(line[i])
        i += 1

    return "".join(out)


if __name__ == "__main__":
    repo, command, ids_file = sys.argv[1], sys.argv[2], sys.argv[3]
    ids = [line.strip() for line in open(ids_file, encoding="utf-8") if line.strip()]

    if command == "ids":
        for item_id in ids:
            item = load(repo, item_id)

            if item is None:
                print(f"### {item_id}  (NEU 里没有)")
                continue

            print(f"### {item_id}")
            print(f"  name: {item.get('displayname', '')}")

            for line in item.get("lore", []):
                print(f"  | {line}")
    elif command == "lines":
        counts, examples = {}, {}

        for item_id in ids:
            item = load(repo, item_id)

            if item is None:
                continue

            for line in item.get("lore", []):
                key = template(line)
                counts[key] = counts.get(key, 0) + 1
                examples.setdefault(key, (item_id, line))

        for key, count in sorted(counts.items(), key=lambda kv: -kv[1]):
            if not CODE.sub("", key).strip():
                continue

            item_id, example = examples[key]
            print(f"{count:4d}  {key}")
