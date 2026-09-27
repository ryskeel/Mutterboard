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
made, undone, rows = collections.Counter(), collections.Counter(), []
for line in sys.stdin:
    f = line.rstrip("\n").split("\t")
    if len(f) < 7: continue
    _, event, source, prev, typed, fixed, nxt = f[:7]
    if event == "corrected": made[source] += 1
    else: undone[source] += 1; rows.append((source, typed, fixed))
print("kind          made  undone")
for s in sorted(made, key=lambda s: -made[s]):
    print(f"{s:12} {made[s]:5} {undone[s]:7}  {100*undone[s]//max(made[s],1)}%")
print("\nundone (typed -> what the keyboard made it):")
for s, t, f in rows[-60:]: print(f"  {s:12} {t} -> {f}")
'
