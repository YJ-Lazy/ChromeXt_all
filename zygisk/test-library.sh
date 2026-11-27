#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"
trap 'sudo rm -rf "$tmp"' EXIT
cp zygisk/module/library.sh "$tmp/library.sh"
touch "$tmp/module.prop"
run() { sudo sh "$tmp/library.sh" "$@"; }
info="$(run info)"
[[ "$info" =~ ^CX_LIBRARY_V2:([0-9a-f]{64}):([0-9]+)$ ]]
rev="${BASH_REMATCH[1]}"
size="${BASH_REMATCH[2]}"
test "$size" -gt 0
test "$(run read 0 | base64 -d)" = '{"format":"ChromeXt-library-v1","scripts":[]}'
test "$(run info)" = "$info"
token=0123456789abcdef0123456789abcdef
data='{"format":"ChromeXt-library-v1","scripts":[],"test":true}'
run begin "$token"
run chunk "$token" 0 "$(printf '%s' "$data" | base64 -w0)"
ack="$(run commit "$token" "$rev" "${#data}")"
[[ "$ack" =~ ^CX_SAVED:[0-9a-f]{64}$ ]]
test "$(run read 0 | base64 -d)" = "$data"
test "$(run info)" != "$info"
sudo truncate -s 0 "$tmp/library.json"
test "$(run info)" = "$info"
echo "Library initialization, read, save and empty recovery passed."
