#!/usr/bin/env bash
# Re-export the store/README screenshots from their SVG sources with Inkscape.
#   ./export.sh            -> all *.svg in this folder
#   ./export.sh 2_overlay  -> only the named slide(s)
# Output: png/<name>.png (1080x1920). Uses the bundled Inter font via fonts/fonts.conf.
set -euo pipefail
cd "$(dirname "$0")"
command -v inkscape >/dev/null || { echo "inkscape not found" >&2; exit 1; }
export FONTCONFIG_FILE="$PWD/fonts/fonts.conf"
mkdir -p png
for a in "$@"; do
  [[ "$a" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]*$ ]] || { echo "invalid slide name '$a' (allowed: A-Za-z0-9._-, not starting with . or -)" >&2; exit 1; }
done
if [ $# -gt 0 ]; then files=("${@/%/.svg}"); else files=(*.svg); fi
for f in "${files[@]}"; do
  inkscape "$f" --export-type=png --export-filename="png/${f%.svg}.png" -w 1080 -h 1920 \
    || { echo "inkscape failed on $f" >&2; exit 1; }
  echo "png/${f%.svg}.png"
done
