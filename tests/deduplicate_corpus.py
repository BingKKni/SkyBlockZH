"""Consolidate identical authored records into one-hop shared references, without changing runtime fields.

Run with --apply to rewrite duplicates. The default only reports; this is a maintenance tool,
not a rule that blocks two intentionally different translations of the same English.
"""
import argparse
from collections import defaultdict
from copy import deepcopy
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "original_text"
RUNTIME = {"text", "zh", "segments", "placeholders", "continuation", "layout", "exclude",
           "chat_join_next", "translate"}
SHARED = "_shared/Fragments/Common_Translations.json"
SURFACES = {"NPC_Message": "CHAT", "ChatMessage": "CHAT", "GUI_Item": "ITEM",
            "_shared": "ITEM", "GUI_Lore": "LORE", "GUI_Title": "TITLE",
            "ActionBar": "ACTION", "BossBar": "BOSS", "ScoreBoard": "SCORE",
            "TabList": "TAB", "Hologram": "HOLOGRAM", "Misc": "MISC"}


def records(document):
    for value in document.values():
        for record in value if isinstance(value, list) else [value]:
            if isinstance(record, dict) and "id" in record:
                yield record


def signature(record):
    data = {key: value for key, value in record.items() if key in RUNTIME}
    if isinstance(data.get("segments"), list):
        data["segments"] = [{key: value for key, value in segment.items()
                             if key in {"text", "zh", "omit", "order"}}
                            for segment in data["segments"]]
    if isinstance(data.get("placeholders"), list):
        data["placeholders"] = [{key: value for key, value in placeholder.items()
                                 if key in {"token", "type", "example"}}
                                for placeholder in data["placeholders"]]
    return json.dumps(data, ensure_ascii=False, sort_keys=True)


def rewrite_records(text, document):
    """Keep untouched records and file formatting byte-for-byte; rewrite only changed objects."""
    wanted = {record["id"]: record for record in records(document)}
    decoder = json.JSONDecoder()
    edits = []
    for opening in re.finditer(r"\{", text):
        start = opening.start()
        try:
            original, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            continue
        if not isinstance(original, dict) or original.get("id") not in wanted:
            continue
        replacement = wanted[original["id"]]
        if original == replacement:
            continue
        line_start = text.rfind("\n", 0, start) + 1
        indent = re.match(r"[ \t]*", text[line_start:start]).group()
        rendered = json.dumps(replacement, ensure_ascii=False, indent=2).replace("\n", "\n" + indent)
        edits.append((start, end, rendered))
    for start, end, replacement in reversed(edits):
        text = text[:start] + replacement + text[end:]
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    files = {path.relative_to(ROOT).as_posix(): json.loads(path.read_text(encoding="utf-8"))
             for path in sorted(ROOT.rglob("*.json"))}
    by_address = {path + "#" + record["id"]: record
                  for path, document in files.items() for record in records(document)}
    referenced = {record["ref"] for record in by_address.values() if "ref" in record}
    before = {address: signature(by_address.get(record.get("ref"), record))
              for address, record in by_address.items()}
    groups = defaultdict(list)
    for address, record in by_address.items():
        if "ref" in record or not record.get("zh") or record.get("continuation") or record.get("translate") is False:
            continue
        path = address.split("#")[0]
        parts = path.split("/")
        surface = SURFACES.get(parts[-2] if len(parts) >= 3 else parts[0])
        # Fragment files are ref targets and may be reused by any surface, but never register alone.
        if surface is not None or path.startswith("_shared/"):
            groups[signature(record)].append((surface, address, record))
    common = files.get(SHARED, {"note": "完全相同的运行时文案仅维护一份；各来源保留稳定 id、语境及颜色证据。", "entries": []})
    changed = set()
    replacements = []
    for key, members in groups.items():
        shared = next((member for member in members if member[1].startswith("_shared/")), None)
        by_surface = defaultdict(list)
        for member in members:
            if member[0] is not None:
                by_surface[member[0]].append(member)
        # Establish a shared definition before visiting singleton occurrences on other surfaces.
        for surface, occurrences in sorted(by_surface.items(), key=lambda group: -len(group[1])):
            if len(occurrences) < 2 and shared is None:
                continue
            replace = [member for member in occurrences if member[1] not in referenced
                       and not member[1].startswith("_shared/")]
            if not replace:
                continue
            target = shared or next((member for member in occurrences if member[1] in referenced), None)
            if target is None:
                record = deepcopy(occurrences[0][2])
                record["id"] = "shared_" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]
                record["source_records"] = [member[1] for member in occurrences]
                common["entries"].append(record)
                target = (None, SHARED + "#" + record["id"], record)
                by_address[target[1]] = record
                changed.add(SHARED)
                shared = target
            for _, address, record in replace:
                original = deepcopy(record)
                # Preserve source evidence and translator notes; runtime text lives in the target.
                for field in RUNTIME:
                    record.pop(field, None)
                record["ref"] = target[1]
                changed.add(address.split("#")[0])
                replacements.append({"record": address, "ref": target[1],
                                     "text": original.get("text"), "zh": original.get("zh")})
    files[SHARED] = common
    for record in common["entries"]:
        address = SHARED + "#" + record["id"]
        sources = sorted(source for source, wrapper in by_address.items() if wrapper.get("ref") == address)
        if sources and record.get("source_records") != sources:
            record["source_records"] = sources
            changed.add(SHARED)
    for address, expected in before.items():
        record = by_address[address]
        resolved = by_address.get(record.get("ref"), record)
        if signature(resolved) != expected:
            raise AssertionError("Runtime fields changed: " + address)
    for replacement in replacements:
        if "ref" in by_address[replacement["ref"]]:
            raise AssertionError("New reference chain: " + replacement["record"])
    print(f"Equivalent references: {len(replacements)}; changed files: {len(changed)}")
    if args.apply:
        for relative in sorted(changed):
            path = ROOT / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            rendered = (rewrite_records(path.read_text(encoding="utf-8"), files[relative])
                        if path.exists() and relative != SHARED
                        else json.dumps(files[relative], ensure_ascii=False, indent=2) + "\n")
            path.write_text(rendered, encoding="utf-8", newline="\n")
        report = ROOT.parent / "build" / "deduplicated-translations.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(replacements, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
