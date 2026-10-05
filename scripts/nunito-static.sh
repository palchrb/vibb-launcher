#!/bin/sh
# Builds the bundled Nunito weights (design docs/design/08-ui-polish.md in the handy workspace).
# Source: the variable Nunito (v3.602, OFL 1.1, no Reserved Font Name) from google/fonts,
# pinned to one commit and checked by SHA-256. Needs curl and fonttools (pip install fonttools).
# Run from the repository root; writes app/src/main/res/font/nunito_*.ttf and the OFL.
set -eu
COMMIT=604936664fd62c14271209b51f98e7f495dd1a3e
BASE="https://raw.githubusercontent.com/google/fonts/$COMMIT/ofl/nunito"
TTF_SHA256=bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207
OFL_SHA256=580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -sfL -o "$work/Nunito.ttf" "$BASE/Nunito%5Bwght%5D.ttf"
curl -sfL -o "$work/OFL.txt" "$BASE/OFL.txt"
echo "$TTF_SHA256  $work/Nunito.ttf" | sha256sum -c -
echo "$OFL_SHA256  $work/OFL.txt" | sha256sum -c -

for pair in 600:semibold 700:bold 800:extrabold; do
    weight=${pair%%:*}
    name=${pair#*:}
    fonttools varLib.instancer "$work/Nunito.ttf" "wght=$weight" --static \
        -o "app/src/main/res/font/nunito_$name.ttf"
done
cp "$work/OFL.txt" app/src/main/assets/licenses/OFL.txt
