#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
target="$script_dir/../android/app/src/main/assets/vosk-model-small-en-us-0.15.zip"
tmp="${target}.download"
trap 'rm -f "$tmp"' EXIT
curl -fL --retry 2 -o "$tmp" 'https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip'
expected='30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
actual=$(shasum -a 256 "$tmp" | cut -d ' ' -f 1)
if [ "$actual" != "$expected" ]; then echo 'Wake model checksum mismatch' >&2; exit 1; fi
mv "$tmp" "$target"
echo 'Offline wake model ready.'
