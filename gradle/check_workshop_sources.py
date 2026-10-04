#!/usr/bin/env python3
"""Validate the rendered Wiki archive, not semantic or live-game coverage.

No network or file writes. Decode lore without splitting escaped quantity
slashes or mistaking HTML entities for Minecraft colour codes.
"""
import hashlib
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/sources/jerrys-workshop-wiki.json'
NPCS = {
    'Banker Barry', 'Daan', 'Dirk', 'Einary', 'Frosty', 'Frozen Alex',
    'Gary', 'Generow', 'Gregory', 'Gulliver', 'Helena', 'Hendrik', 'Jerry',
    'Maaike', 'Mees', 'Sherry', 'St. Jerry', 'Terry',
}


def decode_minetip(value):
    lines, current, i = [], [], 0
    while i < len(value):
        if value[i] == '\\' and i + 1 < len(value):
            current.append(value[i + 1])
            i += 2
            continue
        if value[i] == '/':
            lines.append(''.join(current))
            current = []
        else:
            current.append(value[i])
        i += 1
    lines.append(''.join(current))
    return [re.sub(r'&([0-9a-fk-or])', r'§\1', html.unescape(line)) for line in lines]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    require(decode_minetip(r'&70&6\/&710//&8&#160;Note') ==
            ['§70§6/§710', '', '§8\u00a0Note'], 'slash/entity/blank-line regression')
    require(decode_minetip('&2#3C6746') == ['§2#3C6746'], 'hex colour corrupted')
    source = json.loads(SOURCE.read_text(encoding='utf-8'))
    pages = source['pages']
    tips = source['tooltips']
    require(set(source['island_npcs']) == NPCS, 'island NPC roster changed')
    titles = [p['title'] for p in pages]
    require(len(titles) == len(set(titles)), 'duplicate pages')
    require(NPCS <= set(titles), 'missing NPC source pages')
    for key, tip in tips.items():
        payload = tip['title'] + '\n' + tip['minetip_text']
        require(hashlib.sha256(payload.encode()).hexdigest()[:12] == key,
                f'tooltip {key} no longer matches its content hash')
        require(isinstance(tip['title'], str) and isinstance(tip['minetip_text'], str),
                f'invalid tooltip {key}')
    used = set()
    for page in pages:
        require(page['url'].startswith('https://hypixelskyblock.minecraft.wiki/w/'),
                f'not a rendered community Wiki page: {page["url"]}')
        require(bool(page['last_edited']), f'missing update evidence: {page["title"]}')
        require(set(page['tooltips']) <= tips.keys(), f'dangling tooltip: {page["title"]}')
        used.update(page['tooltips'])
        for menu in page['menus']:
            require(menu['title'] is None or isinstance(menu['title'], str), 'invalid menu title')
            require(set(menu['slots']) <= set(page['tooltips']),
                    f'dangling menu slot: {page["title"]}/{menu["title"]}')
        for line in page['dialogue']:
            require(line['raw'].startswith('§'), f'no dialogue colour: {page["title"]}')
    require(used == tips.keys(), 'orphaned tooltips')
    menu_count = sum(bool(m['title']) for p in pages for m in p['menus'])
    dialogue_count = sum(len(p['dialogue']) for p in pages if p['title'] in NPCS)
    print(f'Archive OK: {len(pages)} pages, {len(NPCS)} NPCs, '
          f'{dialogue_count} dialogue samples, {menu_count} named menus, {len(tips)} tooltips.')
    print('Counts include repeated dialogue, generic submenus and related recipes; '
          'they are not translation coverage percentages.')


if __name__ == '__main__':
    main()
