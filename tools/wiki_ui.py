#!/usr/bin/env python3
"""Read a chest interface — title, every slot's name, its whole lore — out of a wiki /UI subpage.

Why this exists
---------------
`original_text/SOURCES.md` used to say GUI text was the one category the wiki barely covered, and
that GUI_Title would have to come from SkyHanni's regex constants or from screenshots. That was
wrong: the community wiki keeps each interface on a subpage of its own — ``Fragilis/UI``,
``Gwendolyn/UI``, ``Emissaries/UI``, ``Heart of the Mountain/UI`` — transcluded into the article
with ``{{/UI}}``. There are 700-odd of them in `Category:UI Subpages`, and they hold the exact
strings with Hypixel's colour codes.

One slot looks like this::

    |3, 5=Minecart, none, &3Join the Crystal Hollows, /&7This pass grants you.../&2Crystal Hollows&7...
          item      link  name (with colour codes)    lore: "/" starts a line, "//" leaves a blank

so the parsing is: split the field on commas (a comma inside the text is escaped as ``\\,``), the
third cell is the name, everything after it is the lore.

Usage
-----
    python3 tools/fetch_wiki.py pages <cache> "Fragilis/UI"
    python3 tools/wiki_ui.py <cache>/Fragilis__UI.wikitext

Prints every slot as ``name`` followed by its lore lines, ready to be turned into GUI_Item records.
Icons come through in whichever spelling the page used — leave them as symbols in the corpus and let
``text/Glyphs.java`` fold the private-use ones onto them.
"""

import re
import sys


def blocks(text):
    """Each {{UI|...}} template body in a page, braces counted rather than searched for."""
    out, i = [], 0

    while True:
        i = text.find('{{UI|', i)

        if i < 0:
            return out

        depth, j = 0, i

        while j < len(text):
            if text.startswith('{{', j):
                depth, j = depth + 1, j + 2
            elif text.startswith('}}', j):
                depth, j = depth - 1, j + 2

                if depth == 0:
                    break
            else:
                j += 1

        out.append(text[i + 2:j - 2])
        i = j


def split_escaped(s, sep):
    """Split on sep, honouring the wiki's backslash escapes (``\\,`` is a comma in the text)."""
    parts, buf, k = [], '', 0

    while k < len(s):
        if s[k] == '\\' and k + 1 < len(s):
            buf += s[k + 1]
            k += 2
        elif s.startswith(sep, k):
            parts.append(buf)
            buf = ''
            k += len(sep)
        else:
            buf += s[k]
            k += 1

    parts.append(buf)
    return parts


def slots(body):
    """(interface title, position, item name, lore lines) for every filled slot.

    The body opens ``UI|<chest title>|fill=true|...``, so the title is the field after the template
    name — and that title is the container title the corpus needs for GUI_Title as well.
    """
    fields = body.split('|')
    title = fields[1].strip() if len(fields) > 1 else ''


    for field in body.split('\n|'):
        match = re.match(r'^(\d+,\s*\d+)\s*=(.*)$', field.strip(), re.S)

        if not match:
            continue

        cells = split_escaped(match.group(2), ',')

        if len(cells) < 3:
            continue

        name = cells[2].strip()

        if not name or name.lower() == 'none':
            # A decorative pane: no name, nothing to translate.
            continue

        lore, chunks = [], ','.join(cells[3:]).strip()

        for chunk in chunks.split('//'):
            lore += chunk.split('/')
            lore.append('')

        while lore and lore[-1] == '':
            lore.pop()

        yield title, match.group(1), name, lore


def main():
    for path in sys.argv[1:]:
        text = open(path, encoding='utf-8').read()

        for body in blocks(text):
            for title, position, name, lore in slots(body):
                print(f'--- [{title}] {position}  NAME: {name}')

                for line in lore:
                    print(f'      {line!r}')


if __name__ == '__main__':
    main()
