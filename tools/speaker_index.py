#!/usr/bin/env python3
"""Every speaker in the whole wiki cache, and how much of them the corpus has.

Per-page drafting misses lines on principle: a line spoken by Odawa can sit on the Wishing Compass
page, and the Cult of the Fallen Star page holds three NPCs' dialogue and none of its own. Grouping
the entire cache by speaker instead of by page is the only way to be able to say "this is all of
them" — which is the claim the corpus needs to be able to make.

    python3 tools/speaker_index.py <cache-dir>                         coverage table
    python3 tools/speaker_index.py <cache-dir> "Odawa"                 that speaker's lines, with pages
    python3 tools/speaker_index.py <cache-dir> "Odawa" --write <dir>   write <dir>/Odawa.json
    python3 tools/speaker_index.py <cache-dir> "Odawa" --write <dir> --aka "No Name"
        also take the lines tagged with that other name — Hypixel renames an NPC partway through a
        conversation (the Royal Resident becomes "No Name"), and both halves are one character
"""

import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki_to_corpus as w


def index(cache):
    """{speaker: [(plain, raw, page)]}, in page order, de-duplicated per speaker."""
    found, seen = {}, {}

    for path in sorted(glob.glob(os.path.join(cache, "*.wikitext"))):
        page = os.path.basename(path)[:-9]

        for block in w.blocks(cache, page):
            for line in block.split("\n"):
                match = w.SPEAKER.match(w.clean(line))

                if not match:
                    continue

                name = w.SLOT.sub("", match.group("name")).strip()

                if not name:
                    name = page  # a tag that was only the NPC's rotating name

                body = match.group("body").strip()
                raw = w.SLOT.sub("%s", ("&f" + body if not body.startswith("&") else body).replace("&", "§"))
                plain = w.strip_codes(raw).strip()

                if not plain or plain in seen.setdefault(name, set()):
                    continue

                seen[name].add(plain)
                found.setdefault(name, []).append((plain, raw, page))

    return found


def corpus_lines():
    """{npc: {line}} for everything already collected, however it was spelled."""
    out = {}

    for path in glob.glob("original_text/*/NPC_Message/*.json"):
        doc = json.load(open(path, encoding="utf-8"))
        npc = doc.get("npc") or os.path.basename(path)[:-5].replace("_", " ")
        lines = {m.get("text", "") for m in doc.get("messages", [])}
        out[npc] = lines

        # An NPC Hypixel renames partway through a conversation is one file with one `aka` list;
        # without this the other name looks like a whole speaker nobody has collected.
        for alias in doc.get("aka") or []:
            out[alias] = lines

    return out


if __name__ == "__main__":
    cache = sys.argv[1]
    speakers = index(cache)

    if len(sys.argv) > 2 and "--write" in sys.argv:
        name = sys.argv[2]
        out = sys.argv[sys.argv.index("--write") + 1]
        names = [name]

        if "--aka" in sys.argv:
            names.append(sys.argv[sys.argv.index("--aka") + 1])

        lines, seen = [], set()

        for alias in names:
            for plain, raw, page in speakers.get(alias, []):
                if plain not in seen:
                    seen.add(plain)
                    lines.append((plain, raw, page))

        prefix = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
        used, messages = set(), []

        for plain, raw, page in lines:
            record = {
                "id": f"{prefix}_{w.slug(plain, used)}",
                "context": "",
                "raw": raw,
                "text": plain,
                "placeholders": [{"token": "%s", "desc": "", "type": "raw", "example": ""}] * plain.count("%s"),
                "gloss": "",
                "translate": True,
                "zh": "",
            }
            parts = w.segments(raw)

            if parts:
                record["segments"] = parts

            messages.append(record)

        os.makedirs(out, exist_ok=True)
        path = os.path.join(out, name.replace(" ", "_") + ".json")
        doc = {
            "npc": name,
            "aka": names[1:] or None,
            "location": "",
            "note": "",
            "source": "https://hypixelskyblock.minecraft.wiki/w/" + sorted({p for _, _, p in lines})[0].replace(" ", "_"),
            "fetched_at": "2026-08-19",
            "verified_ingame": False,
            "messages": messages,
        }

        if doc["aka"] is None:
            del doc["aka"]

        with open(path, "w", encoding="utf-8") as handle:
            json.dump(doc, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

        print(f"{path}: {len(messages)}")
        raise SystemExit

    if len(sys.argv) > 2:
        for plain, raw, page in speakers.get(sys.argv[2], []):
            print(f"[{page}] {raw}")
        raise SystemExit

    have = corpus_lines()
    rows = []

    for name, lines in speakers.items():
        mine = next((v for k, v in have.items() if w.same_speaker(name, k)), set())
        missing = [p for p, _, _ in lines if p not in mine]
        rows.append((len(missing), len(lines), name, bool(mine)))

    rows.sort(reverse=True)
    print(f"{'缺':>4} {'共':>4}  说话人")

    for missing, total, name, known in rows:
        if missing:
            print(f"{missing:4d} {total:4d}  {name}{'' if known else '   (语料里还没有这个文件)'}")

    print(f"\n合计缺 {sum(r[0] for r in rows)} 行，涉及 {sum(1 for r in rows if r[0])} 位说话人")
