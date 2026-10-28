#!/usr/bin/env bash
# One theme: every colour, text style, corner radius and recurring size lives in composeApp's ui/theme/, so the app is
# rethemed by editing one place. This script fails CI when a UI file outside ui/theme/ spells one out again:
#  1. a colour literal `Color(0x…)`, a `TextStyle(…)`, a `fontSize =` or any `.sp`, a `RoundedCornerShape(…)`;
#  2. a semantic size by its number: 44 / 48 / 52 / 64 / 68 dp (icon button, secondary button, field, row and screen
#     top, big button) and the 1.5 dp outline stroke. Name it from `Dimens` instead. Other one-off gaps stay literal.
set -euo pipefail
cd "$(dirname "$0")/.."

ui=composeApp/src/commonMain/kotlin/com/jakubfronczyk/northform/ui
files=$(find "$ui" -name '*.kt' -not -path "$ui/theme/*")

forbidden='Color\(0x|TextStyle\(|fontSize *=|\.sp\b|RoundedCornerShape\(|\b(44|48|52|64|68)\.dp\b|\b1\.5\.dp\b'

# The one allowance, stripped from a line before it is checked (so the rest of that line still is):
#  - `<n>.dp.toPx()`: a size converted to pixels inside a Canvas draw is the drawing's own geometry (the Sensor hero's
#    52 dp disc), artwork rather than layout; a theme change does not move it.
allowed='[0-9.]+\.dp\.toPx\(\)'

# grep exits 1 for "no match", which is clean; anything else (2: a read error) must fail, not pass as "ok".
matches=$(grep -nE "$forbidden" $files || [ $? -eq 1 ])
leaks=$(printf '%s\n' "$matches" | sed -E "s/$allowed//g" | grep -E "$forbidden" || [ $? -eq 1 ])
if [ -n "$leaks" ]; then
    printf '%s\n' "$leaks"
    echo "check-style: styling spelled out outside ui/theme/: use a Theme / Type / Shapes / Dimens token" >&2
    exit 1
fi
echo "check-style: ok ($(echo "$files" | wc -l | tr -d ' ') UI files)"
