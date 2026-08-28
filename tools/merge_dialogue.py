#!/usr/bin/env python3
"""Bring an NPC_Message file up to the full set of lines the wiki has for that NPC.

Two problems this fixes, both of which looked like "the mod does not translate NPCs" in game.

**Missing lines.** The first collection pass worked from prose summaries and got a fraction of each
NPC's dialogue — Rhys had 0 of his 30 lines, Don Expresso 1 of 26. Nothing warned about it, because
a line nobody collected is indistinguishable from a line nobody translated.

**Merged lines.** Several records ran two or three of the game's lines together into one ``text``,
because that is how a wiki page reads them out. Hypixel sends one chat line at a time, so such a
record matches nothing, ever. ``dalir_first_encounter`` held three lines and had a finished
translation that could never appear on screen.

What this does: keeps every existing record whose text is exactly one line the game sends, together
with its id, its Chinese and its notes; adds an empty record for every line the wiki has and the
file does not; and moves a record that turns out to be several lines glued together into a
``superseded`` list at the bottom of the file, so its Chinese can be split by hand rather than lost.
Records the wiki does not know about are kept untouched — the wiki is not complete either, and
SkyHanni's regexes are a real source.

Order follows the wiki, which lists dialogue in the order the game plays it.

    python3 tools/merge_dialogue.py <cache-dir> <npc-file.json> [wiki-page ...]
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki_to_corpus as w


def lines_for(cache, names, pages):
    """Every line the wiki records for this NPC, in page order, de-duplicated.

    `names` is the NPC plus any `aka` the file lists: Hypixel tags one character several ways (the
    four Keepers all speak as "Keeper of <material>"), and the corpus keeps them in one file.
    """
    out, seen = [], set()

    for page in pages:
        for block in w.blocks(cache, page):
            for line in block.split("\n"):
                match = w.SPEAKER.match(w.clean(line))

                if not match or not any(w.same_speaker(match.group("name"), n) for n in names):
                    continue

                body = match.group("body").strip()
                raw = ("&f" + body if not body.startswith("&") else body).replace("&", "§")
                raw = w.SLOT.sub("%s", raw)
                plain = w.strip_codes(raw).strip()

                if plain and plain not in seen:
                    seen.add(plain)
                    out.append((plain, raw))

    return out


def merge(cache, path, pages):
    doc = json.load(open(path, encoding="utf-8"))
    npc = doc.get("npc") or os.path.basename(path)[:-5].replace("_", " ")
    wiki = lines_for(cache, [npc] + list(doc.get("aka") or []), pages)
    known = {plain for plain, _ in wiki}
    prefix = re.sub(r"[^a-z0-9]+", "_", npc.lower()).strip("_")

    existing = {}
    keep_unknown = []
    superseded = []

    for record in doc.get("messages", []):
        text = record.get("text", "")

        if text in known:
            existing[text] = record
            continue

        parts = [p for p in known if p and p in text and len(p) > 8]

        if len(parts) >= 2 and sum(len(p) for p in parts) > len(text) * 0.7:
            superseded.append(record)
        else:
            keep_unknown.append(record)

    used = {r["id"] for r in existing.values()} | {r["id"] for r in keep_unknown}
    messages = []

    for plain, raw in wiki:
        record = existing.get(plain)

        if record is not None:
            record["raw"] = raw  # the wiki's codes are the game's; refresh them
            parts = w.segments(raw)
            translated = bool(record.get("zh")) or any(s.get("zh") for s in record.get("segments", []))

            if parts and "segments" not in record and not translated:
                # Only for a record nobody has translated yet. Bolting empty segments onto a record
                # that already has a flat `zh` would silently switch it off — segments win over the
                # top-level `zh`, so the line would go back to English with nothing to show for it.
                # Splitting finished Chinese across colour runs is a judgement call, not a merge.
                record["segments"] = parts
            elif parts and "segments" in record:
                # Keep the translator's Chinese, take the wiki's colour boundaries only when they
                # still line up; a mismatch is a real change and should be looked at by hand.
                if len(parts) == len(record["segments"]):
                    for new, old in zip(parts, record["segments"]):
                        new["zh"] = old.get("zh", "")
                    record["segments"] = parts

            messages.append(record)
            continue

        rid = f"{prefix}_{w.slug(plain, used)}"
        record = {
            "id": rid,
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

    doc["messages"] = messages + keep_unknown

    if superseded:
        doc["superseded"] = superseded
        doc["superseded_note"] = (
            "这些记录把游戏里的好几行台词并成了一条 text,永远匹配不上(Hypixel 一次只发一行)。"
            "已被上面按行拆开的记录取代,保留在这里只是为了把原来的译文拆分/搬运过去;"
            "搬完请整条删掉,不要留在文件里。"
        )

    with open(path, "w", encoding="utf-8") as handle:
        json.dump(doc, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    filled = sum(1 for m in messages if m.get("zh"))
    print(f"{os.path.basename(path)}: {len(messages)} 行(已译 {filled}), "
          f"其他来源 {len(keep_unknown)}, 待拆分 {len(superseded)}")


if __name__ == "__main__":
    cache, path = sys.argv[1], sys.argv[2]
    npc = json.load(open(path, encoding="utf-8")).get("npc") or os.path.basename(path)[:-5].replace("_", " ")
    merge(cache, path, sys.argv[3:] or [npc])
