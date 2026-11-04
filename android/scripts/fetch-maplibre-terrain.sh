#!/usr/bin/env bash
# MapLibre Native with 3D terrain: a community build of maplibre-native's unfinished feature/terrain-3d branch
# (robertopinotti/maplibre-native, pre-release). No official MapLibre Native release has terrain yet; when one
# does, drop this script and go back to mavenCentral. Unpacks a Maven repository into third_party/ (gitignored).
set -euo pipefail
TAG="android-terrain3d-f63b12f"
SHA256="75ab1a95101c8f1d276d62c12f0b82bb3672051dde31f335ee9f4e7d7d97f888"
URL="https://github.com/robertopinotti/maplibre-native/releases/download/${TAG}/maplibre-android-terrain3d.zip"
here="$(cd "$(dirname "$0")/.." && pwd)"
dest="$here/third_party/maplibre-terrain3d"
if [ -f "$dest/.tag" ] && [ "$(cat "$dest/.tag")" = "$TAG" ]; then exit 0; fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/m.zip" "$URL"
echo "$SHA256  $tmp/m.zip" | sha256sum -c - >/dev/null
rm -rf "$dest" && mkdir -p "$dest"
unzip -q "$tmp/m.zip" -d "$dest"
echo "$TAG" > "$dest/.tag"
echo "MapLibre terrain3d build $TAG ready in $dest"
