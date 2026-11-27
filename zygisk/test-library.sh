#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"
trap 'sudo rm -rf "$tmp"' EXIT
cp zygisk/module/library.sh "$tmp/library.sh"
touch "$tmp/module.prop"
# Exercise the same shell entry point; optionally run with Android-like mksh.
run() { sudo "${CX_TEST_SHELL:-sh}" "$tmp/library.sh" "$@"; }
info="$(run info)"
[[ "$info" =~ ^CX_LIBRARY_V2:([0-9a-f]{64}):([0-9]+)$ ]]
rev="${BASH_REMATCH[1]}"
size="${BASH_REMATCH[2]}"
test "$size" -gt 0
expected="$(printf '%s' '{"format":"ChromeXt-library-v1","scripts":[]}' | sha256sum | cut -d ' ' -f 1)"
test "$rev" = "$expected"
test "$(run read 0 | base64 -d)" = '{"format":"ChromeXt-library-v1","scripts":[]}'
test "$(run info)" = "$info"
token=0123456789abcdef0123456789abcdef
data='{"format":"ChromeXt-library-v1","scripts":[],"test":true}'
run begin "$token"
run chunk "$token" 0 "$(printf '%s' "$data" | base64 -w0)"
ack="$(run commit "$token" "$rev" "${#data}")"
[[ "$ack" =~ ^CX_SAVED:[0-9a-f]{64}$ ]]
expected_new="$(printf '%s' "$data" | sha256sum | cut -d ' ' -f 1)"
test "$ack" = "CX_SAVED:$expected_new"
test "$(run read 0 | base64 -d)" = "$data"
test "$(run info)" = "CX_LIBRARY_V2:$expected_new:${#data}"
# An obsolete revision must never replace the current library.
run begin "$token"
run chunk "$token" 0 "$(printf '%s' "$data" | base64 -w0)"
if run commit "$token" "$rev" "${#data}"; then
    echo "Stale revision was accepted" >&2
    exit 1
fi
run abort "$token"
test "$(run info)" = "CX_LIBRARY_V2:$expected_new:${#data}"
sudo truncate -s 0 "$tmp/library.json"
test "$(run info)" = "$info"
# Large, non-aligned file: verify all supported batch widths and exact tail.
python3 -c 'import sys; sys.stdout.buffer.write(bytes(range(251))*1300)' | sudo tee "$tmp/library.json" >/dev/null
for blocks in 1 4 8; do
    chunk_bytes=$((16384 * blocks))
    for ((offset=0;offset<326300;offset+=chunk_bytes)); do
        run read "$((offset / 16384))" "$blocks" | base64 -d > "$tmp/actual"
        sudo dd if="$tmp/library.json" bs=16384 skip="$((offset / 16384))" count="$blocks" status=none > "$tmp/expected"
        cmp "$tmp/actual" "$tmp/expected"
    done
done
if run read 0 9; then echo "Oversized batch accepted" >&2; exit 1; fi
echo "Library initialization, read, save, empty recovery and batched reads passed."
