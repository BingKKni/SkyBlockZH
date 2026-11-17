"""Propose scoped display references in existing records. Review additions before committing.

No substring replacements run against live chat. In particular, item/ability names, owners,
ordinary words and already annotated ranges do not become currencies/places by spelling alone.
"""
import json
import re
from item_reference_candidates import CORPUS, records, english, chinese_parts


def chinese(row): return ''.join(chinese_parts(row))


def load(root):
    return {p.relative_to(root).as_posix(): json.loads(p.read_text(encoding='utf-8-sig')) for p in root.rglob('*.json')}


# Canonical currency, Chinese spellings used by existing records, server aliases.
CURRENCIES = [
    ('Coins', '硬币', 'Coin'), ('Bits', '点券', 'Bit'), ('Motes', '微尘', 'Mote'),
    ('Sowdust', '播尘', ''), ('Copper', '铜币|铜', ''), ('Pelts', '毛皮', 'Pelt'),
    ('Bingo Points', '宾果点数', 'Bingo Point'), ('North Stars', '北极星', 'North Star'),
    ('Fishy Treats', '鱼味零食', 'Fishy Treat'), ('Carnival Tokens', '嘉年华代币', 'Carnival Token'),
    ('NotCoins', '非币', 'NotCoin'), ('Forest Whispers', '森林低语', 'Forest Whisper'),
    ('Chocolate', '巧克力', ''), ('Tokens of the Mountain', '山心代币', 'Token of the Mountain'),
    ('Fossil Dust', '化石粉尘|化石尘', ''),
] + [(en+' Powder', zh+'粉末', '') for en, zh in [('Gemstone', '宝石'), ('Mithril', '秘银'), ('Glacite', '极冰')]] + [
    (en+' Essence', zh+'精华', '') for en, zh in [('Wither', '凋零'), ('Undead', '亡灵'), ('Crimson', '绯红'),
    ('Spider', '蜘蛛'), ('Dragon', '巨龙'), ('Gold', '黄金'), ('Diamond', '钻石'), ('Ice', '冰霜'),
    ('Forest', '森林'), ('Fossil', '化石'), ('Safari', '游猎')]]

# These are map names only with evidence of a destination, not e.g. the NPC Blacksmith, the
# Bank enchantment, the Carnival event, the Kuudra boss or the Forge crafting menu.
AMBIGUOUS = set('Bank Forest Garden Museum Village Farm Blacksmith Mountain Dungeon Kuudra Carnival Forge '
                'Catacombs Jungle Graveyard Wilderness Ruins Library Tavern Inferno Veins Safari Greenhouse Windmill '
                'Cathedral Courtyard Stronghold Dojo End Quarry Plaza Colosseum Mineshaft'.split())
PLACE = re.compile(r'\b(?:at|to|in|from|near|visit|enter|reach|leave|outside|inside|around|through)\s+(?:the\s+)?$', re.I)
PROTECTED = re.compile(r'\b(?:Sam the Garden Assistant|Chocolate (?:(?:Breakfast|Brunch|Lunch|Déjeuner|Dinner|Supper) Egg|Injections)|Coin Conversion|Motes Grubber|Visitor Copper|Heart of the Forest|Copper (?:Ring|Dye|Ingot)|Chocolate (?:Factory|Rabbit|Eggs?|Dye|Bars?|Shop|Fortune)|Hot Chocolate)\b', re.I)


def expression(words):
    return re.compile(r'(?<![a-zA-Z0-9_])(?:' + '|'.join(map(re.escape, sorted(words, key=len, reverse=True))) + r')(?![a-zA-Z0-9_])', re.I)


def proposals(documents):
    aliases = {s.lower(): (name, zh) for name, zh, alias in CURRENCIES for s in (name, alias) if s}
    aliases.update({'purse': ('Coins', '硬币|余额'), 'piggy': ('Coins', '硬币'), 'token': ('Carnival Tokens', '代币'), 'tokens': ('Carnival Tokens', '代币')})
    maps = {'forge': '熔炉'}
    areas = documents['_data/areas.json']['areas']
    for t in documents['_shared/Terms.json']['terms']:
        if (t['en'] in areas or 'location_name' in t.get('types', [])) and t['en'] != t['zh']:
            maps[t['en'].lower()] = t['zh']
    for excluded in ('island', 'your island', '"your" island', 'dark auction', 'fishing hotspots', 'far', 'reserve', "rampart's"):
        maps.pop(excluded, None)
    # First consume entire known item, ability, attribute and event names, not a term inside one.
    shields = set(documents['_data/item-names.json']['names'])
    shields.update(t['en'] for t in documents['_shared/Terms.json']['terms'] if t.get('types') and
                   not set(t['types']) & {'raw', 'location_name'})
    protected = {s.lower() for s in shields} - aliases.keys() - maps.keys()
    matcher = expression(aliases.keys() | maps.keys() | protected)
    catalog = set(documents['_data/item-names.json']['names'])
    for path, doc in documents.items():
        for row in records(doc):
            src, dst = english(row), chinese(row)
            if not dst or not src or src in catalog or row.get('item_name') or src.startswith(('Ability:', 'Set Bonus:', 'Full Set Bonus:')): continue
            blocked = [m.span() for m in PROTECTED.finditer(src)]
            occupied = [ref['zh'] for field in ('mob_refs', 'item_refs') for ref in row.get(field, [])]
            result = {}
            def add(field, word, zh, currency=None):
                if field in row or any(zh in other or other in zh for other in occupied): return
                ref = {'text': word, 'zh': zh}
                if currency: ref['currency'] = currency
                # Do not restore e.g. Chocolate inside Chocolate Factory just because the same
                # sentence also contains a production amount. Source and target may be reordered.
                protected_zh = {'Chocolate': r'热巧克力|巧克力(?:工厂|兔子?|彩蛋|商店|棒)'}.get(word)
                if protected_zh:
                    spans = [m.span() for m in re.finditer(protected_zh, dst)]
                    targets = [i for i, hit in enumerate(re.finditer(re.escape(zh), dst)) if
                               not any(a <= hit.start() < b for a,b in spans)]
                    sources = [i for i, hit in enumerate(re.finditer(re.escape(word), src)) if
                               not any(a <= hit.start() < b for a,b in blocked)]
                    if not sources or not targets: return
                    if len(targets) != dst.count(zh) or len(sources) != src.count(word):
                        for i, t in enumerate(targets):
                            result.setdefault(field, []).append(dict(ref, target_occurrence=t,
                                source_occurrence=sources[min(i, len(sources)-1)]))
                        occupied.append(zh)
                        return
                result.setdefault(field, []).append(ref)
                occupied.append(zh)
            for m in re.finditer(r'%(?:\d+\$)?s (Powder|Essence)\b', src):
                token = m[0].split(' ')[0]
                for sep in ('', ' '):
                    zh = token + sep + {'Powder': '粉末', 'Essence': '精华'}[m[1]]
                    if zh in dst:
                        add('currency_refs', m[0], zh)
                        break
            for m in matcher.finditer(src):
                word, key = m[0], m[0].lower()
                if key in protected or any(a < m.end() and b > m.start() for a,b in blocked): continue
                if key in aliases:
                    name, variants = aliases[key]
                    if key in ('piggy','purse') and not src[m.end():].startswith(':'): continue
                    if key in ('token','tokens') and 'carnival' not in (path+row['id']).lower(): continue
                    if key == 'chocolate' and not re.search(r'(?:%[\d$s]+[x%]? |more |produce |production of |^)(?:Chocolate)(?:[ :.!]|$)', src): continue
                    for zh in variants.split('|'):
                        if zh in dst:
                            add('currency_refs', word, zh, name if key != name.lower() else None)
                            break
                elif maps[key] in dst:
                    if word != word.upper() and word[:1].islower() and key not in ('the forge','the garden','the end'): continue
                    if word.title() in AMBIGUOUS and not (PLACE.search(src[:m.start()]) or src.strip() == word and
                                                          any(s in path for s in ('/Hologram/', '/ScoreBoard/', '/Sign/'))): continue
                    add('map_refs', word, maps[key])
            if path.endswith('Mining/TabList/Tab_Widget_Enum.json') and src in ('Mithril:', 'Glacite:', 'Gemstone:'):
                add('currency_refs', src[:-1], dst.rstrip(':：'), src[:-1] + ' Powder')
            if result: yield path, row['id'], result


if __name__ == '__main__':
    for path, record, fields in proposals(load(CORPUS)):
        print(path, record, fields)
