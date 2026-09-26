#!/usr/bin/env python3
"""Build the keyboard's English word-pair table from Tatoeba.

Pastiera's next-word prediction only knew pairs the user had typed, so a fresh
install predicted nothing, and autocorrect could not use the previous word to
choose between "team" and "them". This builds the table both read:

    keyboard/app/src/main/assets/common/dictionaries/en_bigrams.tsv

from Tatoeba's English sentences (CC BY 2.0 FR, credited in about_credits.md),
chosen because they are everyday speech rather than the written prose the
bundled dictionary comes from.

One sentence in twenty is held out and never counted; a sample of those goes
to the unit tests (tatoeba_en_heldout.txt) so the scorecards measure on text
the table has never seen.

Usage:
    scripts/build-english-bigrams.py path/to/eng_sentences.tsv.bz2

Get the input from
    https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
"""
import bz2
import collections
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSET = ROOT / "keyboard/app/src/main/assets/common/dictionaries/en_bigrams.tsv"
HELDOUT = ROOT / "keyboard/app/src/test/resources/tatoeba_en_heldout.txt"

START = "<s>"
MIN_CONTEXT_TOTAL = 20   # a previous word seen fewer times predicts nothing useful
MAX_CONTINUATIONS = 256   # per previous word; the bar shows three
MIN_PAIR_COUNT = 3
MAX_CONTEXTS = 12000
HELDOUT_SAMPLE = 20000

TOKEN = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")

# Tatoeba's sentence writers reuse a handful of names in a large share of the
# corpus. Left in, the keyboard would offer "Tom" after half the verbs in
# English.
def is_name(display: str) -> bool:
    return display[0].isupper() and display != "I" and not display.startswith("I'")


def main() -> None:
    src = Path(sys.argv[1])
    pairs = collections.Counter()
    totals = collections.Counter()
    unigrams = collections.Counter()
    casing = collections.defaultdict(collections.Counter)
    heldout = []

    with bz2.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            sid, text = int(parts[0]), parts[2]
            if sid % 20 == 0:
                heldout.append(text)
                continue
            tokens = TOKEN.findall(text.replace("’", "'"))
            if not tokens:
                continue
            prev = START
            for i, tok in enumerate(tokens):
                low = tok.lower()
                if i > 0:  # sentence-initial capitals say nothing about the word
                    casing[low][tok] += 1
                unigrams[low] += 1
                pairs[(prev, low)] += 1
                totals[prev] += 1
                prev = low

    def display(low: str) -> str:
        forms = casing.get(low)
        return forms.most_common(1)[0][0] if forms else low

    contexts = [c for c, t in totals.most_common() if t >= MIN_CONTEXT_TOTAL][:MAX_CONTEXTS]
    by_context = collections.defaultdict(list)
    for (prev, nxt), n in pairs.items():
        if n >= MIN_PAIR_COUNT:
            by_context[prev].append((n, nxt))

    ASSET.parent.mkdir(parents=True, exist_ok=True)
    with ASSET.open("w", encoding="utf-8") as out:
        out.write("# Word pairs from Tatoeba (tatoeba.org, CC BY 2.0 FR). "
                  "Built by scripts/build-english-bigrams.py; do not edit.\n")
        out.write("# Line 3: total tokens. Then: previous word, times seen, "
                  "then next:count pairs.\n")
        out.write(f"{sum(unigrams.values())}\n")
        # Unigram counts ride along under the empty context, so the model can
        # tell a pair that is common from a word that is just common.
        common = [(n, w) for w, n in unigrams.most_common(40000) if n >= MIN_PAIR_COUNT]
        out.write("\t" + str(sum(unigrams.values())) + "\t" +
                  " ".join(f"{display(w)}:{n}" for n, w in common) + "\n")
        for prev in contexts:
            nexts = sorted(by_context.get(prev, []), reverse=True)
            kept = [(n, w) for n, w in nexts if not is_name(display(w))][:MAX_CONTINUATIONS]
            if not kept:
                continue
            out.write(prev + "\t" + str(totals[prev]) + "\t" +
                      " ".join(f"{display(w)}:{n}" for n, w in kept) + "\n")

    random.Random(20260925).shuffle(heldout)
    HELDOUT.parent.mkdir(parents=True, exist_ok=True)
    HELDOUT.write_text("\n".join(heldout[:HELDOUT_SAMPLE]) + "\n", encoding="utf-8")
    print(f"{ASSET.name}: {len(contexts)} contexts, {ASSET.stat().st_size // 1024} KB; "
          f"held out {len(heldout)} sentences, sampled {HELDOUT_SAMPLE}")


if __name__ == "__main__":
    main()
