#!/usr/bin/env python3
"""Regenerate app/src/main/res/values*/keyboard_rebrand.xml.

The physical keyboard is Pastiera, and its strings say so in every language it
ships. An app resource overrides a library resource of the same name and
qualifier, so rather than editing Pastiera's strings.xml (which would conflict
with every nightly commit that touches them) this copies each string that
mentions Pastiera into the app, renamed, one file per locale.

Per locale, not just the default: the app's values/app_name does not beat the
library's values-de/app_name on a German phone.

Run it again after picking nightly changes across, and commit the result.
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
KEYBOARD_RES = ROOT / "keyboard/app/src/main/res"
APP_RES = ROOT / "app/src/main/res"
OUT_NAME = "keyboard_rebrand.xml"

# Longest first, so "Pastiera Physical Keyboard" does not become
# "Mutterboard Physical Keyboard".
# The domain is left alone: those links go to Pastiera's real guides, and
# "Mutterboard.eu" would be a link to nowhere.
NOT_A_LINK = r"(?<![/.\w])(?!\S*\.eu\b)"
REPLACEMENTS = [
    (re.compile(NOT_A_LINK + r"Pastiera Physical Keyboard", re.I), "Mutterboard"),
    (re.compile(NOT_A_LINK + r"Pastiera Keyboard", re.I), "Mutterboard"),
    (re.compile(NOT_A_LINK + r"Pastiera(?!\.eu)", re.I), "Mutterboard"),
]

# Where Pastiera put its name on a feature, the feature gets no name at all.
# "Mutterboard QuickLauncher" stamps our name on someone else's work; it is
# just the QuickLauncher. The app still names itself where a sentence is about
# what the app does ("Mutterboard inserts newlines"), since that is its name.
# Run before REPLACEMENTS. The hyphenated QuickLauncher is the same in every
# language; the rest is English wording, so it only runs on values/.
GENERIC_ALL = [
    (re.compile(r"Pastiera[- ](QuickLauncher)"), r"\1"),
]
GENERIC_EN = [
    (re.compile(r"(^|\. )Pastiera[’']s "), r"\1The keyboard’s "),
    (re.compile(r"\bPastiera([’'])s "), r"the keyboard\1s "),
    (re.compile(r"\bPastiera (Nav Mode|nav mode|Clicks button)"), r"\1"),
    (re.compile(r"\bPastiera-side\b"), "Keyboard-side"),
    (re.compile(r"^Pastiera (function|shortcut)"), r"Keyboard \1"),
    (re.compile(r"\b(the|required) Pastiera (emoji picker|settings|shortcut)"), r"\1 \2"),
    (re.compile(r"\bPastiera search\b"), "QuickLauncher search"),
    (re.compile(r"\bfalls back to Pastiera\b"), "falls back to QuickLauncher"),
    (re.compile(r"\bOpen Pastiera docs\b"), "Open the docs"),
    (re.compile(r"\bPastiera (function|statusbar|layout|backups?|actions|overrides)\b"), r"keyboard \1"),
]
# Left in Pastiera's own words: an Italian recipe dictionary named after the
# cake, which "Ricette Mutterboard" would turn into nonsense.
KEEP = {"auto_correct_ricette_pastiera_name"}
# Also left alone: anything about Pastiera's releases, its successor or its
# security updates. "Mutterboard continues as Plektra" is not true.
ABOUT_PASTIERA = re.compile(r"Plektra|Pastiera \d|Nightl|security update", re.I)

# The one place Pastiera keeps its name: the credit GPL asks for, on the About
# screen, where a rename would claim Palsoftware wrote Mutterboard.
CREDIT = {"about_build_info": "Physical keyboard based on Pastiera by Palsoftware (GPL-3.0)"}


def app_string_names(qualifier_dir: str) -> set[str]:
    names = set()
    for f in (APP_RES / qualifier_dir).glob("*.xml"):
        if f.name == OUT_NAME:
            continue
        names.update(re.findall(r'<string name="([^"]+)"', f.read_text()))
    return names


def main() -> None:
    for strings in sorted(KEYBOARD_RES.glob("values*/strings.xml")):
        qualifier = strings.parent.name
        own = app_string_names(qualifier)
        raw = strings.read_text()
        entries = []
        # Raw text, not the parsed tree: the values carry escapes and markup
        # that must come across byte-for-byte.
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', raw, re.S):
            name, attrs, value = m.groups()
            if (
                name in own
                or name in KEEP
                or ABOUT_PASTIERA.search(value)
                or not re.search("pastiera", value, re.I)
            ):
                continue
            renamed = value
            generic = GENERIC_ALL + (GENERIC_EN if qualifier == "values" else [])
            for pattern, repl in generic + REPLACEMENTS:
                renamed = pattern.sub(repl, renamed)
            renamed = CREDIT.get(name, renamed)
            if renamed == value:
                continue
            value = renamed
            entries.append(f'    <string name="{name}"{attrs}>{value}</string>')
        out = APP_RES / qualifier / OUT_NAME
        if not entries:
            out.unlink(missing_ok=True)
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by scripts/rebrand-keyboard-strings.py. Do not edit. -->\n"
            "<resources>\n" + "\n".join(entries) + "\n</resources>\n"
        )
        ET.parse(out)  # fail loudly on anything malformed
        print(f"{qualifier}: {len(entries)}")


if __name__ == "__main__":
    main()
