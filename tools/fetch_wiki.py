#!/usr/bin/env python3
"""Pull source text out of the community wiki, losslessly, in bulk.

Why this exists
---------------
The first collection pass was done by asking a fetch tool to read wiki pages, and it
under-collected in a way nobody could see: a summarising fetcher silently drops sections of a long
page, and two runs over the same page drop different sections. The corpus ended up with most of an
NPC's lines and none of another's, and the only symptom in game was English text.

The wiki runs MediaWiki 1.45 with its API open, so none of that is necessary. ``action=query``
returns the exact wikitext of up to 50 pages per request, and the wikitext is where the good stuff
is: dialogue is stored inside ``{{Dialogue|...}}`` templates *with Hypixel's own colour codes*,
which is precisely the ``raw`` field original_text/README asks for and which no rendered page or
prose summary preserves.

Usage
-----
    python3 tools/fetch_wiki.py category "Dwarven Mines" "Crystal Hollows"
        list every page in those categories, one title per line

    python3 tools/fetch_wiki.py pages <cache-dir> Bomin "Royal Mines" ...
        write <cache-dir>/<title>.wikitext for each page, skipping ones already there

    python3 tools/fetch_wiki.py dialogue <cache-dir>
        print every {{Dialogue}} block found in the cache, page by page

Everything is cached on disk because the corpus is built over many sessions and the wiki should be
asked once per page, not once per question about that page.
"""

import json
import os
import sys
import time
import urllib.parse
import urllib.request

API = "https://hypixelskyblock.minecraft.wiki/api.php"
AGENT = "SkyZH-corpus-collector/1.0 (Chinese translation layer; contact via github.com/bingkkni)"


def call(**params):
    params.setdefault("format", "json")
    params.setdefault("formatversion", "2")
    url = API + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})

    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - retry anything transient
            if attempt == 3:
                raise
            print(f"  retry {attempt + 1} after {error}", file=sys.stderr)
            time.sleep(2 * (attempt + 1))


def category(name):
    """Every page in a category, following continuation to the end."""
    titles, cont = [], {}

    while True:
        data = call(
            action="query", list="categorymembers", cmtitle=f"Category:{name}",
            cmlimit="500", **cont,
        )
        titles += [m["title"] for m in data["query"]["categorymembers"]]

        if "continue" not in data:
            return titles

        cont = data["continue"]


def safe(title):
    return title.replace("/", "__").replace(":", "_")


def pages(cache, titles):
    """Fetch wikitext for titles not already cached, 50 at a time."""
    os.makedirs(cache, exist_ok=True)
    missing = [t for t in titles if not os.path.exists(os.path.join(cache, safe(t) + ".wikitext"))]
    print(f"{len(titles)} titles, {len(missing)} to fetch", file=sys.stderr)

    for i in range(0, len(missing), 50):
        batch = missing[i:i + 50]
        data = call(
            action="query", prop="revisions", rvslots="main", rvprop="content",
            titles="|".join(batch),
        )

        for page in data["query"]["pages"]:
            path = os.path.join(cache, safe(page["title"]) + ".wikitext")
            body = ""

            if "revisions" in page:
                body = page["revisions"][0]["slots"]["main"]["content"]
            elif page.get("missing"):
                body = ""  # cached as empty so it is not asked for again

            with open(path, "w", encoding="utf-8") as handle:
                handle.write(body)

        print(f"  {i + len(batch)}/{len(missing)}", file=sys.stderr)


def dialogue(cache):
    """Every {{Dialogue|...}} body in the cache, with the page it came from."""
    for name in sorted(os.listdir(cache)):
        if not name.endswith(".wikitext"):
            continue

        text = open(os.path.join(cache, name), encoding="utf-8").read()
        start = 0

        while True:
            start = text.find("{{Dialogue", start)

            if start < 0:
                break

            # Braces nest — a dialogue line may hold {{Item|...}} — so count rather than search.
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

            print(f"===== {name[:-9]}")
            print(text[start:i])
            start = i


if __name__ == "__main__":
    command = sys.argv[1]

    if command == "category":
        seen = set()

        for name in sys.argv[2:]:
            for title in category(name):
                if title not in seen and not title.startswith(("Template:", "Category:", "User", "Calculator:")):
                    seen.add(title)
                    print(title)
    elif command == "pages":
        pages(sys.argv[2], [line.strip() for line in sys.stdin if line.strip()] or sys.argv[3:])
    elif command == "dialogue":
        dialogue(sys.argv[2])
    else:
        raise SystemExit(__doc__)
