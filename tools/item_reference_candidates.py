#!/usr/bin/env python3
"""Find fixed item references using existing translations; optionally add explicit metadata.

Only metadata is added. English matching, Chinese prose and live colour boundaries stay unchanged.
Candidates are evidence for a human review, not proof that a noun denotes a physical item.
"""
import argparse
import collections
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'original_text'


def records(document):
    for value in document.values():
        for row in value if isinstance(value, list) else [value]:
            if isinstance(row, dict) and 'id' in row:
                yield row


def english(row):
    return ''.join(s.get('text', '') for s in row['segments']) if row.get('segments') else row.get('text', '')


def chinese_parts(row):
    if row.get('segments'):
        return [s.get('zh', '') for s in row['segments'] if not s.get('omit')]
    return [row.get('zh', '')]


def reference_candidates(documents):
    catalog = documents['_data/item-names.json']['names']
    identities = {name.casefold(): name for name in catalog}
    translations = collections.defaultdict(set)
    for path, document in documents.items():
        if '/GUI_Item/' not in path and not path.startswith('_shared/'):
            continue
        for row in records(document):
            name = english(row)
            if row.get('ref') or row.get('translate') is False or name.casefold() not in identities:
                continue
            zh = ''.join(chinese_parts(row))
            if zh and zh != name and '%' not in zh:
                translations[name.casefold()].add(zh)
    # All catalog names participate, so an untranslated long name shields its translated short part.
    aliases = dict(identities)
    for folded, name in list(identities.items()):
        words = name.split(' ')
        for i, word in enumerate(words):
            if len(word) >= 3 and word[0].isupper() and not word.lower().endswith('s'):
                plural = words[:i] + [word + 's'] + words[i + 1:]
                aliases.setdefault(' '.join(plural).casefold(), name)
        if name.endswith('y'):
            aliases.setdefault((name[:-1] + 'ies').casefold(), name)
    pattern = re.compile(r'(?<![A-Za-z0-9_])(?:' + '|'.join(re.escape(n) for n in sorted(aliases, key=len, reverse=True))
                         + r')(?![A-Za-z0-9_])', re.IGNORECASE)
    nonitems = set()
    for term in documents['_shared/Terms.json']['terms']:
        if set(term.get('types', [])) & {'location_name', 'mob_name', 'accessory_power'}:
            if term['en'].casefold() not in identities:
                nonitems.add(term['en'].casefold())
    compound = re.compile(r'^\s+(?:Powder|Fortune|Wisdom|Resistance|Speed|Protector|Deposits?|Ore|Dunes|Mine|Mines|Quarry|Forest|Woods)\b', re.I)
    return identities, translations, aliases, pattern, nonitems, compound


def annotate(root=CORPUS, surfaces=(), apply=False):
    documents = {p.relative_to(root).as_posix(): json.loads(p.read_text(encoding='utf-8-sig')) for p in root.rglob('*.json')}
    identities, translations, aliases, pattern, nonitems, compound = reference_candidates(documents)
    result = []
    for path, document in documents.items():
        surface = path.split('/')[-2]
        if surfaces and surface not in surfaces:
            continue
        additions = {}
        for row in records(document):
            if row.get('ref') or row.get('translate') is False or row.get('item_refs') or row.get('item_name'):
                continue
            en, parts = english(row), chinese_parts(row)
            zh = ''.join(parts)
            if not en or not zh or en == zh:
                continue
            # Item-surface exact names already carry this role in the loader.
            if en.casefold() in identities:
                if surface not in ('GUI_Item', '_shared'):
                    additions[row['id']] = {'item_name': True}
                    result.append({'file': path, 'id': row['id'], 'text': en, 'zh': zh, 'metadata': additions[row['id']]})
                continue
            refs, skipped = [], []
            for match in pattern.finditer(en):
                text = match.group()
                name = aliases[text.casefold()]
                choices = [z for z in translations.get(name.casefold(), ()) if any(z in part for part in parts)]
                if not choices:
                    continue
                target = max(choices, key=len)
                if compound.match(en[match.end():]) or any(n in en.casefold() and text.casefold() in n for n in nonitems):
                    skipped.append(text)
                    continue
                if row.get('segments') and not any(text in s.get('text', '') for s in row['segments']):
                    skipped.append(text)
                    continue
                ref = {'text': text, 'zh': target}
                if ref not in refs:
                    refs.append(ref)
            # An ambiguous Chinese spelling needs explicit target occurrence selection.
            ambiguous = len({r['zh'] for r in refs}) != len(refs)
            if refs and not ambiguous:
                additions[row['id']] = {'item_refs': refs}
                result.append({'file': path, 'id': row['id'], 'text': en, 'zh': zh, 'metadata': additions[row['id']], 'skipped': skipped})
            elif refs or skipped:
                result.append({'file': path, 'id': row['id'], 'text': en, 'zh': zh, 'review': refs, 'skipped': skipped})
        if apply and additions:
            target = root / path
            with target.open(encoding='utf-8', newline='') as handle:
                original = handle.read()
            for record_id, fields in additions.items():
                marker = re.compile(r'(?m)^(?P<indent>[ \t]*)"id"[ \t]*:[ \t]*' + re.escape(json.dumps(record_id, ensure_ascii=False)) + r',[ \t]*(?P<eol>\r?\n)')
                def insert(match):
                    return match.group() + ''.join(match['indent'] + json.dumps(key) + ': ' + json.dumps(value, ensure_ascii=False)
                                                  + ',' + match['eol'] for key, value in fields.items())
                original, count = marker.subn(insert, original)
                if count == 0:
                    inline = re.compile(r'"id"[ \\t]*:[ \\t]*' + re.escape(json.dumps(record_id, ensure_ascii=False)) + ',')
                    fields_text = ''.join(' ' + json.dumps(key) + ': ' + json.dumps(value, ensure_ascii=False) + ',' for key, value in fields.items())
                    original, count = inline.subn(lambda match: match.group() + fields_text, original)
                if count != 1:
                    raise ValueError(f'Cannot safely locate {path}#{record_id}: {count}')
            with target.open('w', encoding='utf-8', newline='') as handle:
                handle.write(original)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--surfaces', nargs='+', default=[])
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = annotate(surfaces=args.surfaces, apply=args.apply)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'records': len(result), 'annotated': sum('metadata' in r for r in result),
                      'files': len({r['file'] for r in result}), 'by_surface': dict(collections.Counter(r['file'].split('/')[-2] for r in result))}, ensure_ascii=False))


if __name__ == '__main__':
    main()
