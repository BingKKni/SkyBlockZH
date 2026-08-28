#!/usr/bin/env python3
"""Look up SkyBlock terms on the community wikis, for translation context.

Run this before writing any new translation. SkyBlock is full of words whose ordinary English
meaning is wrong in context — a "Commission" is a job from the Dwarven Mines task board, not a
fee; "Powder" is a currency; "Fortune" is a drop multiplier — and translating from the string
alone is what produces 机翻味.

The official wiki (wiki.hypixel.net) was shut down in July 2026 and now redirects to a forum
announcement; Hypixel endorsed no replacement. Two community wikis carry the content, and this
queries them in order:

  1. hypixelskyblock.minecraft.wiki — the more current of the two. It had the Mythological mob
     rarity ladder (Stalwart / Venerable / Exalted / Empyrean) that the other one did not, which
     is what made "Empyrean Gaia Construct" translatable at all.
  2. hypixel-skyblock.fandom.com — older, but has pages the first one lacks.

Usage:
  wiki_lookup.py <page title> [<page title> ...]     # each page, plain text or wikitext
  wiki_lookup.py --search <phrase>                   # find the page a string belongs to
  wiki_lookup.py --grep <page> <pattern>             # matching lines of a page's wikitext,
                                                     # for reading one row out of a stat table
"""
import json
import re
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MIRRORS = (
    "https://hypixelskyblock.minecraft.wiki/api.php",
    "https://hypixel-skyblock.fandom.com/api.php",
)
# Both serve a redirect chain or a challenge page to clients with no User-Agent.
HEADERS = {"User-Agent": "SkyZH-translation-context/0.1 (github.com/BingKKni/SkyBlockZH)"}


def call(params, api=None):
    last = None

    for endpoint in ([api] if api else MIRRORS):
        url = f"{endpoint}?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(url, headers=HEADERS)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return json.loads(response.read().decode("utf-8")), endpoint
        except Exception as error:  # a mirror being down is not a reason to stop
            last = error

    raise last


def extract(titles):
    """Plain-text extract, falling back to raw wikitext.

    Fandom's SkyBlock pages are built almost entirely out of infobox and lore templates, so
    TextExtracts returns an empty string for most of them. The wikitext is what actually holds
    the item's lore and the prose describing what it is for, which is the context a translator
    needs — so it is worth reading even though it is noisier.
    """
    for endpoint in MIRRORS:
        try:
            data, used = call({
                "action": "query",
                "prop": "extracts|revisions",
                "explaintext": "1",
                "rvprop": "content",
                "rvslots": "main",
                "redirects": "1",
                "format": "json",
                "titles": "|".join(titles),
            }, api=endpoint)
        except Exception as error:
            print(f"  ({endpoint} unreachable: {error})")
            continue

        pages = data.get("query", {}).get("pages", {})
        missing = [p for p in pages.values() if "missing" in p]

        for page in pages.values():
            if "missing" in page:
                continue

            print(f"\n=========== {page.get('title')}   [{used.split('/')[2]}]")
            text = (page.get("extract") or "").strip()
            if text:
                print(text[:3000])
                continue

            revisions = page.get("revisions") or []
            if revisions:
                content = revisions[0].get("slots", {}).get("main", {}).get("*", "")
                print("  --- no plain extract; raw wikitext follows ---")
                print(content[:3500])
            else:
                print("  (page exists but returned no content)")

        # Only fall through to the older mirror for titles the current one does not have.
        if not missing:
            return

        titles = [p["title"] for p in missing]

    for title in titles:
        print(f"\n=========== {title}")
        print("  (no such page on either wiki)")


def grep(title, pattern):
    """Matching lines of a page's wikitext.

    SkyBlock's pages put the facts a translator needs — a mob's rarity tiers, an item's stats —
    inside wikitable markup, where a plain-text extract returns nothing and the full wikitext is
    thousands of lines. This pulls out the rows that mention a word.
    """
    data, used = call({
        "action": "query",
        "prop": "revisions",
        "rvprop": "content",
        "rvslots": "main",
        "redirects": "1",
        "format": "json",
        "titles": title,
    })

    for page in data.get("query", {}).get("pages", {}).values():
        if "missing" in page:
            print(f"  (no such page: {title})")
            continue

        content = page.get("revisions", [{}])[0].get("slots", {}).get("main", {}).get("*", "")
        print(f"=========== {page.get('title')}   [{used.split('/')[2]}]  /{pattern}/")

        for line in content.splitlines():
            if re.search(pattern, line, re.IGNORECASE):
                print(" ", line.strip()[:300])


def search(phrase):
    for endpoint in MIRRORS:
        try:
            data, used = call({
                "action": "query",
                "list": "search",
                "srsearch": phrase,
                "srlimit": "8",
                "format": "json",
            }, api=endpoint)
        except Exception as error:
            print(f"  ({endpoint} unreachable: {error})")
            continue

        hits = data.get("query", {}).get("search", [])
        print(f"--- {used.split('/')[2]}: {len(hits)} hit(s)")

        for hit in hits:
            snippet = hit.get("snippet", "")
            for tag in ("<span class=\"searchmatch\">", "</span>"):
                snippet = snippet.replace(tag, "")
            snippet = re.sub(r"<[^>]+>", "", snippet)
            print(f"  - {hit['title']}: {snippet}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    if sys.argv[1] == "--search":
        search(" ".join(sys.argv[2:]))
    elif sys.argv[1] == "--grep" and len(sys.argv) >= 4:
        grep(sys.argv[2], " ".join(sys.argv[3:]))
    else:
        extract(sys.argv[1:])


if __name__ == "__main__":
    main()
