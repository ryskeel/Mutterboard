#!/usr/bin/env bash
# Pulls the keyboard's autocorrect log off the phone (CorrectionAudit) and sums
# it: how often each kind of correction gets taken back, and the ones that were.
# The undone list is the scorecard for Ry's own typing. Stays on this machine;
# the lines are his words.
#   scripts/autocorrect-audit.sh           summary
#   scripts/autocorrect-audit.sh --raw     the whole log
set -euo pipefail
PKG=com.example.mutterboard
log=$(adb exec-out run-as "$PKG" cat files/autocorrect-audit.tsv)
if [[ "${1:-}" == "--raw" ]]; then printf '%s\n' "$log"; exit; fi
printf '%s\n' "$log" | python3 -c '
import sys, collections
made, undone = collections.Counter(), collections.Counter()
rows, added, context = [], [], {}
for line in sys.stdin:
    f = line.rstrip("\n").split("\t")
    if len(f) < 7: continue
    _, event, source, prev, typed, fixed, nxt = f[:7]
    if event == "corrected":
        made[source] += 1; context[(typed, fixed)] = (prev, nxt)
    elif event == "undone":
        undone[source] += 1
        # Lines written before undo carried context have none; borrow the correction'"'"'s.
        if not prev and not nxt: prev, nxt = context.get((typed, fixed), ("", ""))
        rows.append([source, prev, typed, fixed, nxt, None])
    elif event == "after-undo" and rows and rows[-1][2] == typed and rows[-1][5] is None:
        rows[-1][5] = (fixed, nxt)
    elif event == "period" and rows and rows[-1][5]:
        rows[-1][5] = (rows[-1][5][0], ".")
    elif event == "added": added.append(typed)

def verdict(after, typed, fixed):
    # What followed the undo says why it happened.
    if after is None: return "?"
    word, key = after
    punct = key not in ("space", "enter", "")
    if word == fixed: return "redid fix" + (" then punctuation" if punct else "")
    if word == typed and key not in ("space", "enter", ""): return "punctuation"
    if word == typed: return "kept"
    return "-> " + word

print("kind          made  undone")
for s in sorted(made, key=lambda s: -made[s]):
    print(f"{s:12} {made[s]:5} {undone[s]:7}  {100*undone[s]//max(made[s],1)}%")
def bucket(r):
    v = verdict(r[5], r[2], r[3])
    return "->" if v.startswith("->") else v.split(" then")[0]
v = collections.Counter(bucket(r) for r in rows)
print("\nafter the undo: " + ", ".join(f"{k} {n}" for k, n in v.most_common()))
print("  punctuation = backspaced to type punctuation, not a wrong fix")
print("  redid fix = typed the correction back by hand: it was right, the undo was in the way")
print("  kept = left the typed word; -> = typed something else; ? = not logged")
print("\nundone ([before] typed -> keyboard made it [after], then what happened):")
for s, p, t, f, n, a in rows[-60:]:
    print(f"  {s:12} [{p}] {t} -> {f} [{n}]  {verdict(a, t, f)}")
if added: print("\nadded to dictionary: " + ", ".join(added[-60:]))
'
