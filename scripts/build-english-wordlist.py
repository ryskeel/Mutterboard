#!/usr/bin/env python3
"""Build the keyboard's list of English words the dictionary lacks.

The bundled English dictionary has about 50,000 words, so autocorrect took
real words it had never seen for typos: "sprig" became spring, "swaddle"
saddle, "glisten" listen. This writes every word SCOWL knows up to a size
level that the dictionary does not, one per line, lowercase:

    keyboard/app/src/main/assets/common/dictionaries/en_known_words.txt

TypoModel leaves those words alone. They are not added to the dictionary:
they would then become things to correct *to* and to suggest, and a word list
without frequencies cannot say how likely they are.

SCOWL is Kevin Atkinson's, under a permissive licence, credited in
about_credits.md and third_party_notices.md.

Usage:
    scripts/build-english-wordlist.py path/to/scowl-2020.12.07 [level] [out]

Get SCOWL from https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz
Level 60 is what the scorecards were measured with; see CLAUDE.md.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DICTS = ROOT / "keyboard/app/src/main/assets/common/dictionaries"
# American spelling first; British variants are words too, so a Brit's
# "colour" is not "corrected", but they are not offered either way.
SPELLINGS = ("english", "american", "british")
KINDS = ("words", "upper", "proper-names", "contractions")
WORD = re.compile(r"^[a-z]+(?:'[a-z]+)*$")


def main():
    scowl = Path(sys.argv[1])
    level = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else DICTS / "en_known_words.txt"

    have = set()
    for name in ("en_base.json", "en_extra.json"):
        for entry in json.loads((DICTS / name).read_text(encoding="utf-8")):
            have.add(entry["w"].lower())

    words = set()
    for path in (scowl / "final").iterdir():
        spelling, _, rest = path.name.partition("-")
        kind, _, size = rest.rpartition(".")
        if spelling not in SPELLINGS or kind not in KINDS or not size.isdigit() or int(size) > level:
            continue
        for line in path.read_text(encoding="latin-1").splitlines():
            word = line.strip().lower()
            if len(word) >= 3 and WORD.match(word) and word not in have:
                words.add(word)

    out.write_text("\n".join(sorted(words)) + "\n", encoding="utf-8")
    print(f"{len(words)} words at level {level} -> {out}")


if __name__ == "__main__":
    main()
