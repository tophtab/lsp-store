#!/usr/bin/env python3
"""Translate the English strings.xml into the languages komi-store ships.

Uses the public Google endpoint the app itself uses. Placeholders such as
%1$s and %1$d are masked before translation so they survive intact.
Results are cached in /tmp/lspstore_i18n_cache.json so reruns are cheap.
"""
import concurrent.futures
import html
import json
import os
import re
import sys
import threading
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
CACHE = "/tmp/lspstore_i18n_cache.json"

LANGS = ["ar", "bn", "es", "fr", "hi", "it", "ja", "ko", "pl", "ru", "tr"]
WORKERS = 6

# Kept in English: brand names and app title.
SKIP = {"app_name", "label_beta", "proxy_type_http", "proxy_type_socks",
        "translation_google", "translation_libre", "translation_deepl",
        "translation_microsoft"}

ENTRY = re.compile(r'<string\s+name="([^"]+)"\s*>(.*?)</string>', re.S)
PLACEHOLDER = re.compile(r'%(\d+)\$[sdf]')
TOKEN = "[{i}]"


def load_english():
    text = open(EN, encoding="utf-8").read()
    out = []
    for name, raw in ENTRY.findall(text):
        out.append((name, html.unescape(raw)))
    return out


def mask(text):
    mapping = []

    def repl(m):
        mapping.append(m.group(0))
        return f"[{len(mapping) - 1}]"

    return PLACEHOLDER.sub(repl, text), mapping


def unmask(text, mapping):
    for i, original in enumerate(mapping):
        # Translators may render the ASCII digits as locale digits (e.g. ০ = 0
        # in Bengali), so accept any Unicode decimal digit here.
        text = re.sub(r"\[\s*\d+?\s*\]", lambda m: _maybe(m.group(0), i, original), text)
    return text


def _maybe(token, i, original):
    inner = token.strip("[] \t")
    if not inner.isdigit():
        return token
    return original if int(inner) == i else token


def translate(text, lang, cache, lock):
    key = f"{lang}|{text}"
    with lock:
        if key in cache:
            return cache[key]
    masked, mapping = mask(text)
    params = urllib.parse.urlencode({
        "client": "dict-chrome-ex", "sl": "en", "tl": lang, "dt": "t", "q": masked,
    })
    url = "https://translate.googleapis.com/translate_a/single?" + params
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=25) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            parts = data[0] or []
            result = "".join(p[0] for p in parts if p and p[0])
            result = unmask(result, mapping)
            if not result.strip():
                raise ValueError("empty")
            with lock:
                cache[key] = result
            return result
        except Exception as exc:  # noqa: BLE001
            if attempt == 2:
                print(f"  ! {lang} failed for {text[:40]!r}: {exc}", file=sys.stderr)
                return None
            time.sleep(2.0 * (attempt + 1))
    return None


def escape(value):
    value = value.replace("\\", "\\\\")
    value = value.replace("'", "\\'")
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return value


def main():
    entries = load_english()
    langs = sys.argv[1:] or LANGS
    print(f"{len(entries)} strings, {len(langs)} languages, {WORKERS} workers", flush=True)
    cache = {}
    if os.path.exists(CACHE):
        cache = json.load(open(CACHE, encoding="utf-8"))
    lock = threading.Lock()

    for lang in langs:
        todo = [(n, t) for n, t in entries if n not in SKIP]
        results = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = {
                pool.submit(translate, text, lang, cache, lock): name
                for name, text in todo
            }
            for future in concurrent.futures.as_completed(futures):
                name = futures[future]
                value = future.result()
                if value is not None:
                    results[name] = value

        lines = ["<resources>"]
        for name, _ in todo:
            if name in results:
                lines.append(f'    <string name="{name}">{escape(results[name])}</string>')
        lines.append("</resources>")
        out_dir = os.path.join(ROOT, f"app/src/main/res/values-{lang}")
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "strings.xml"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        print(f"{lang}: {len(results)}/{len(todo)}", flush=True)
        with lock:
            json.dump(cache, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main()
