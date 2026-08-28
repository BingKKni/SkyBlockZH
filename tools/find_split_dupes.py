#!/usr/bin/env python3
"""Find lore that renders one sentence twice.

Hypixel wraps a lore sentence across two lines, so the corpus holds two records. The rule
(original_text/README.md, "跨行整句的标记 continuation") is that the head record carries the
whole Chinese sentence and the tail is marked `"continuation": true` with an empty `zh`, so
the runtime deletes it.

The defect: a head whose `zh` was rewritten into a *complete* sentence while its tail kept
an independent translation. The player then reads the same thought twice — e.g.
"寻找「钻头技师」将可用的配件安装到这个钻头上！" followed by "「钻头技师」聊聊吧！".

Adjacency is taken from the per-item `lore` arrays, which are in the order the game draws
them, with `ref` entries resolved into the shared library. That is real evidence rather than
a guess, so the pairs printed here are worth acting on. Files that are flat alphabetical
libraries (_shared/Item_Lore.json read on its own) carry no order and are not scanned.
"""
import json
import glob
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ENDERS = "。！？!?."
# A head that ends on one of these in English is already a finished sentence; the line after
# it is a new sentence, not a tail. Only an *unfinished* English head can have a tail.
CLOSED = "。！？!?.:：,，、;；"


def load_corpus(root):
    docs = {}
    for path in sorted(glob.glob("**/*.json", recursive=True, root_dir=root)):
        rel = path.replace("\\", "/")
        with open(os.path.join(root, path), encoding="utf-8") as handle:
            docs[rel] = json.load(handle)
    return docs


def index_by_ref(docs):
    """Every record addressable as "<path>#<id>", the way TranslationLoader does it."""
    table = {}
    for rel, doc in docs.items():
        for key, value in doc.items():
            if isinstance(value, list):
                for element in value:
                    if isinstance(element, dict) and "id" in element:
                        table[f"{rel}#{element['id']}"] = element
    return table


def resolve(record, refs):
    """A `ref` entry stands for the record it points at, which is where the text lives."""
    if "ref" in record:
        return refs.get(record["ref"], record)
    return record


def effective_zh(record):
    segments = record.get("segments")
    if isinstance(segments, list) and segments:
        return "".join(s.get("zh", "") or "" for s in segments if not s.get("omit"))
    return record.get("zh", "") or ""


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "original_text"))
    docs = load_corpus(root)
    refs = index_by_ref(docs)

    findings = []

    for rel, doc in docs.items():
        for key, value in doc.items():
            if key != "lore" or not isinstance(value, list):
                continue

            # Walk consecutive pairs in draw order.
            for i in range(len(value) - 1):
                head_entry, tail_entry = value[i], value[i + 1]
                if not isinstance(head_entry, dict) or not isinstance(tail_entry, dict):
                    continue

                head = resolve(head_entry, refs)
                tail = resolve(tail_entry, refs)

                head_en = (head.get("text") or "").strip()
                tail_en = (tail.get("text") or "").strip()
                if not head_en or not tail_en:
                    continue

                # The English head must be unfinished for a tail to exist at all.
                if head_en[-1] in CLOSED:
                    continue
                # A tail begins mid-sentence: lowercase first word.
                if not tail_en[:1].islower():
                    continue
                if tail.get("continuation") or tail.get("translate") is False:
                    continue

                head_zh = effective_zh(head).strip()
                tail_zh = effective_zh(tail).strip()
                if not head_zh or not tail_zh:
                    continue
                # The signature of the bug: the head already says the whole thing.
                if head_zh[-1] not in ENDERS:
                    continue

                findings.append({
                    "file": rel,
                    "head_id": head.get("id"),
                    "tail_id": tail.get("id"),
                    "head_en": head_en,
                    "tail_en": tail_en,
                    "head_zh": head_zh,
                    "tail_zh": tail_zh,
                })

    # The same shared pair is referenced from many item files; report each pair once, with
    # the item files that draw it, because the fix is one edit in the shared library.
    unique = {}
    for f in findings:
        pair = (f["head_id"], f["tail_id"])
        unique.setdefault(pair, {"info": f, "files": []})["files"].append(f["file"])

    for (head_id, tail_id), group in sorted(unique.items(), key=lambda kv: -len(kv[1]["files"])):
        info = group["info"]
        print(f"\n### {head_id}  +  {tail_id}   ({len(group['files'])} item file(s))")
        print(f"  head en: {info['head_en']!r}")
        print(f"  head zh: {info['head_zh']!r}")
        print(f"  tail en: {info['tail_en']!r}")
        print(f"  tail zh: {info['tail_zh']!r}    <-- renders as a duplicate second line")
        for f in group["files"][:6]:
            print(f"    drawn by {f}")
        if len(group["files"]) > 6:
            print(f"    ... and {len(group['files']) - 6} more")

    print(f"\n{len(unique)} duplicated sentence pair(s) across {len(findings)} draw site(s).")


if __name__ == "__main__":
    main()
