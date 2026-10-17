#!/usr/bin/env python3
"""Apply tools/manual_overrides.py to existing locale files.

Only rewrites the strings listed in the table, leaving machine output for
everything else untouched. Use it after the translator has run (or on its own
if you just want to correct a few labels without any network access).

    python3 tools/apply_overrides.py           # all languages
    python3 tools/apply_overrides.py ko ru     # selected languages
"""
import html
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from manual_overrides import OVERRIDES  # noqa: E402
from translate_strings import ENTRY, ROOT, escape, load_english  # noqa: E402


def main():
    wanted = sys.argv[1:] or sorted(OVERRIDES)
    base = dict(load_english())
    for lang in wanted:
        if lang not in OVERRIDES:
            print(f"{lang}: no overrides, skipped")
            continue
        path = os.path.join(ROOT, f"app/src/main/res/values-{lang}/strings.xml")
        if not os.path.exists(path):
            print(f"{lang}: {path} missing, skipped")
            continue
        text = open(path, encoding="utf-8").read()
        current = {n: html.unescape(v) for n, v in ENTRY.findall(text)}

        applied = 0
        for name, value in OVERRIDES[lang].items():
            if name not in base:
                print(f"  ! {lang}: unknown string {name}", file=sys.stderr)
                continue
            if name not in current:
                print(f"  ! {lang}: {name} not in file", file=sys.stderr)
                continue
            if current[name] == value:
                continue
            text = re.sub(
                r'(<string\s+name="%s"\s*>)(.*?)(</string>)' % re.escape(name),
                lambda m: m.group(1) + escape(value) + m.group(3),
                text,
                count=1,
                flags=re.S,
            )
            applied += 1

        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"{lang}: {applied} override(s) applied")


if __name__ == "__main__":
    main()
