#!/bin/sh
# Re-vendor error_codes.yaml from a Pelican checkout and regenerate the Java enum.
#   codegen/refresh.sh [path-to-pelican-checkout]
set -e
here=$(cd "$(dirname "$0")" && pwd)
src=${1:-$here/../../pelican}
if [ ! -f "$src/docs/error_codes.yaml" ]; then
  echo "no docs/error_codes.yaml under $src; pass a Pelican checkout path" >&2
  exit 1
fi
cp "$src/docs/error_codes.yaml" "$here/error_codes.yaml"
python3 "$here/generate_error_codes.py"
