#!/usr/bin/env python3
"""Turn cached wiki dialogue into a draft NPC_Message file.

The wiki stores dialogue as ``{{Dialogue|&e[NPC] &bTicket Master&f: Hop on in!}}`` — Hypixel's own
string, colour codes and all. That is a better source than any prose summary, and it is regular
enough that transcribing it by hand is both slow and the step where lines got dropped.

So this writes the draft: one record per distinct line, with ``raw`` (colour codes intact),
``text`` (colours stripped, which is what a translator reads and what the engine matches on), and
``segments`` when the line changes colour more than once — the rule from original_text/README §3.

What it deliberately does NOT do is decide anything. Every ``zh`` comes out empty, every
``context`` is a stub, and ``verified_ingame`` is false. A draft is a transcription, and the
judgement — what a line means, whether a word is a proper noun, which of two similar lines is the
one the game actually sends — is the translator's, done against a file that is at least complete.

    python3 tools/wiki_to_corpus.py <cache-dir> "Ticket Master" [id-prefix] > draft.json
    python3 tools/wiki_to_corpus.py <cache-dir> "Cult of the Fallen Star" --by-speaker <out-dir>

The second form matters more than it looks. A wiki page is organised around a *place or an event*,
not around who is talking: the Cult of the Fallen Star page holds 188 lines spoken by Dalir, Thondin
and Brarnas between them, and the corpus is organised one file per NPC. Splitting by the speaker tag
is what turns "a page about the cult" into the three files that were each sitting there with a todo
saying their dialogue had never been found.
"""

import json
import os
import re
import sys

# &e[NPC] &bTicket Master&f: the line   /   &e[NPC] Bomin&f: the line
SPEAKER = re.compile(r"^(?:&.)*\[NPC\] (?:&.)*(?P<name>[^&:]+?)(?:&.)*: ?(?P<body>.*)$")
CODE = re.compile(r"[&§]([0-9a-fk-or])", re.IGNORECASE)
# <location>, <player>, {{PLAYER}} — the wiki's way of writing "the server fills this in"
SLOT = re.compile(r"<(?!/?nowiki)[a-z_ ]+>|\[(?:player|name|username|user)\]|\{\{PLAYER\}\}", re.IGNORECASE)
TEMPLATE = re.compile(r"\{\{(?:Item|Coll|Zone|Stat|stat)\|([^}|]*)(?:\|[^}]*)?\}\}")


def blocks(cache, page):
    """Every {{Dialogue|...}} body on one cached page."""
    path = os.path.join(cache, page.replace("/", "__").replace(":", "_") + ".wikitext")

    if not os.path.exists(path):
        return []

    text = open(path, encoding="utf-8").read()
    found, start = [], 0

    while True:
        start = text.find("{{Dialogue", start)

        if start < 0:
            return found

        depth, i = 0, start

        while i < len(text):
            if text.startswith("{{", i):
                depth, i = depth + 1, i + 2
            elif text.startswith("}}", i):
                depth, i = depth - 1, i + 2

                if depth == 0:
                    break
            else:
                i += 1

        found.append(text[start + len("{{Dialogue|"):i - 2])
        start = i


def same_speaker(actual, wanted):
    """Whether a speaker tag names this NPC.

    Hypixel writes the tag several ways for one character. The King's tag is "King <name>" on the
    days he has a name and just "<name>" on the page's own dialogue; the Castle Guards file covers
    lines tagged "Castle Guard". Comparing the strings exactly loses all of it.

    Prefix matching is deliberately *not* allowed: "Geo" is not a shorter way of writing "Geonathan
    Greatforge", and "King" is not "King Yolkar". They are different characters who happen to share
    a few letters, and treating them as one silently files somebody's dialogue under somebody else.
    """
    a = SLOT.sub("", actual).strip().lower()
    b = wanted.strip().lower()

    if not a:
        # The tag was nothing but the slot — the NPC's own rotating name. On that NPC's page there
        # is nobody else it could be.
        return True

    return a == b or a.rstrip("s") == b.rstrip("s")


def speakers(cache, page):
    """Who talks on this page, in the order they first do."""
    found = []

    for block in blocks(cache, page):
        for line in block.split("\n"):
            match = SPEAKER.match(clean(line))

            if match and match.group("name").strip() not in found:
                found.append(match.group("name").strip())

    return found


def clean(line):
    line = line.replace("\\/", "/").replace("&nbsp;", " ").replace("‎", "")
    line = re.sub(r"</?nowiki\s*/?>", "", line)
    line = re.sub(r"<!--.*?-->", "", line, flags=re.S)
    line = TEMPLATE.sub(lambda m: m.group(1), line)
    return line.replace("[[", "").replace("]]", "").strip()


def strip_codes(text):
    return CODE.sub("", text)


def segments(raw):
    """Colour runs of a line, or None when the whole line is one colour."""
    parts = []
    cursor, colour = 0, ""

    for match in CODE.finditer(raw):
        if match.start() > cursor:
            parts.append({"color": colour, "text": raw[cursor:match.start()], "zh": ""})

        # Consecutive codes (&6&l) belong to the same run.
        if match.start() == cursor:
            colour += "§" + match.group(1)
        else:
            colour = "§" + match.group(1)

        cursor = match.end()

    if cursor < len(raw):
        parts.append({"color": colour, "text": raw[cursor:], "zh": ""})

    return parts if len(parts) > 1 else None


def slug(text, used):
    words = re.sub(r"[^a-z0-9]+", " ", strip_codes(text).lower()).split()
    base = "_".join(words[:5]) or "line"
    name, n = base, 2

    while name in used:
        name, n = f"{base}_{n}", n + 1

    used.add(name)
    return name


def draft(cache, page, prefix, speaker=None):
    seen, used, records = set(), set(), []

    for block in blocks(cache, page):
        for line in block.split("\n"):
            line = clean(line)

            if not line:
                continue

            match = SPEAKER.match(line)

            if not match:
                continue

            if speaker is not None and match.group("name").strip() != speaker:
                continue

            body = match.group("body").strip()
            # A body with no code of its own inherits the colour the speaker tag left behind, which
            # for every line the wiki records is the &f after the colon.
            raw = ("&f" + body if not body.startswith("&") else body).replace("&", "§")
            plain = SLOT.sub("%s", strip_codes(raw)).strip()

            if not plain or plain in seen:
                continue

            seen.add(plain)
            record = {
                "id": f"{prefix}_{slug(plain, used)}",
                "context": "",
                "raw": SLOT.sub("%s", raw),
                "text": plain,
                "placeholders": [
                    {"token": "%s", "desc": "", "type": "raw", "example": ""}
                ] * plain.count("%s"),
                "gloss": "",
                "translate": True,
                "zh": "",
            }

            parts = segments(SLOT.sub("%s", raw))

            if parts:
                record["segments"] = parts

            records.append(record)

    return records


def document(page, npc, messages):
    return {
        "npc": npc,
        "source": f"https://hypixelskyblock.minecraft.wiki/w/{page.replace(' ', '_')}",
        "fetched_at": "2026-08-19",
        "verified_ingame": False,
        "messages": messages,
    }


if __name__ == "__main__":
    cache, page = sys.argv[1], sys.argv[2]

    if len(sys.argv) > 3 and sys.argv[3] == "--by-speaker":
        out = sys.argv[4]
        os.makedirs(out, exist_ok=True)

        for name in speakers(cache, page):
            prefix = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
            body = document(page, name, draft(cache, page, prefix, speaker=name))
            path = os.path.join(out, name.replace(" ", "_") + ".json")

            with open(path, "w", encoding="utf-8") as handle:
                json.dump(body, handle, ensure_ascii=False, indent=2)

            print(f"{path}: {len(body['messages'])}", file=sys.stderr)
    else:
        prefix = sys.argv[3] if len(sys.argv) > 3 else re.sub(r"[^a-z0-9]+", "_", page.lower()).strip("_")
        print(json.dumps(document(page, page, draft(cache, page, prefix)), ensure_ascii=False, indent=2))
