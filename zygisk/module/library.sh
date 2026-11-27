#!/system/bin/sh
set -eu
d="${0%/*}"
f="$d/library.json"
max=16777216
fail() { printf '%s\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "WebUI root execution unavailable"
[ -f "$d/module.prop" ] || fail "Module directory missing"
[ ! -L "$f" ] || fail "Unsafe library path"
# Android shell PATH and applet output differ among root managers.
tool() {
  if [ -x /system/bin/toybox ]; then /system/bin/toybox "$@"; else "$@"; fi
}
size() {
  count="$(tool wc -c < "$1")" || fail "Cannot count library bytes"
  set -- $count
  [ "$#" = 1 ] || fail "Invalid byte count"
  case "$1" in ''|*[!0-9]*) fail "Invalid byte count";; esac
  printf '%s' "$1"
}
hash() {
  result="$(tool sha256sum "$f")" || fail "SHA256 failed"
  set -- $result
  digest="${1:-}"
  case "$digest" in ''|*[!a-f0-9]*) fail "Invalid SHA256 output";; esac
  [ "${#digest}" = 64 ] || fail "Invalid SHA256 length"
  printf '%s' "$digest"
}
# Repair missing or zero-byte initialization only. Never overwrite a nonempty library.
if [ ! -e "$f" ] || { [ -f "$f" ] && [ ! -s "$f" ]; }; then
  [ "${1:-}" = info ] || fail "Open script library first"
  [ ! -d /data/adb/modules_update/chromext_zygisk ] || fail "Module update pending; reboot first"
  umask 077
  t="$(mktemp "$d/.library-init.XXXXXX")" || fail "Cannot create empty library"
  printf '%s' '{"format":"ChromeXt-library-v1","scripts":[]}' > "$t"
  chown 0:0 "$t"; chmod 600 "$t"
  # Avoid clobbering a library that appeared while initialization was in progress.
  if [ -s "$f" ]; then rm -f "$t"; else mv -f "$t" "$f"; fi
fi
[ -f "$f" ] && [ -s "$f" ] || fail "Library missing or unreadable"
number() { case "$1" in ''|*[!0-9]*) exit 1;; esac; [ "${#1}" -le 8 ] || exit 1; }
validtoken() { case "$1" in ''|*[!a-f0-9]*) exit 1;; esac; [ "${#1}" = 32 ] || exit 1; }
active() { [ ! -d /data/adb/modules_update/chromext_zygisk ] && [ ! -e "$d/disable" ] && [ ! -e "$d/remove" ] || { echo "Module update pending or disabled; reboot first" >&2; exit 1; }; }
case "${1:-}" in
 info)
   revision="$(hash)" || exit 1
   bytes="$(size "$f")" || exit 1
   [ "$bytes" -gt 0 ] && [ "$bytes" -le "$max" ] || fail "Library exceeds allowed size: $bytes"
   printf 'CX_LIBRARY_V2:%s:%s\n' "$revision" "$bytes"
   ;;
 read)
   number "$2"; [ "$2" -le 1024 ] || exit 1
   dd if="$f" bs=16384 skip="$2" count=1 2>/dev/null | base64 | tr -d '\r\n'
   ;;
 begin)
   active; validtoken "$2"; umask 077
   mkdir "$d/.library-$2"; : > "$d/.library-$2/data"
   ;;
 chunk)
   active; validtoken "$2"; number "$3"
   t="$d/.library-$2/data"; [ -f "$t" ] && [ ! -L "$t" ] || exit 1
   [ "$(size "$t")" = "$3" ] || exit 1
   [ "${#4}" -le 22000 ] || exit 1
   case "$4" in *[!A-Za-z0-9+/=]*) exit 1;; esac
   printf '%s' "$4" | base64 -d >> "$t"
   [ "$(size "$t")" -le "$max" ] || exit 1
   ;;
 commit)
   active; validtoken "$2"; number "$4"
   t="$d/.library-$2/data"; [ -f "$t" ] && [ ! -L "$t" ] || exit 1
   [ "$4" -gt 0 ] && [ "$4" -le "$max" ] && [ "$(size "$t")" = "$4" ] || exit 1
   [ "$(hash)" = "$3" ] || { echo "Library changed; reload before saving" >&2; exit 1; }
   chown 0:0 "$t"; chmod 600 "$t"; mv -f "$t" "$f"; rmdir "$d/.library-$2"
   revision="$(hash)" || exit 1
   printf 'CX_SAVED:%s\n' "$revision"
   ;;
 abort) validtoken "$2"; rm -f "$d/.library-$2/data"; rmdir "$d/.library-$2" 2>/dev/null || true ;;
 local-begin)
   active; validtoken "$2"
   pkg="$3"
   case "$pkg" in ''|*[!A-Za-z0-9_.]*) exit 1;; esac
   grep -Fxq "$pkg" "$d/supported.list" && grep -Fxq "$pkg" "$d/injection.list" || exit 1
   umask 077; mkdir "$d/.snapshot-$2"
   t="$d/.snapshot-$2/data"
   if ! "$d/cx-snapshot" "$pkg" > "$t"; then
     rm -f "$t"; rmdir "$d/.snapshot-$2"
     echo "No local snapshot; restart the selected browser and open a web page" >&2; exit 1
   fi
   size "$t"; printf '\n'
   ;;
 local-read)
   validtoken "$2"; number "$3"; [ "$3" -le 1024 ] || exit 1
   t="$d/.snapshot-$2/data"; [ -f "$t" ] && [ ! -L "$t" ] || exit 1
   dd if="$t" bs=16384 skip="$3" count=1 2>/dev/null | base64 | tr -d '\r\n'
   ;;
 local-end) validtoken "$2"; rm -f "$d/.snapshot-$2/data"; rmdir "$d/.snapshot-$2" 2>/dev/null || true ;;
 logs) logcat -d -t 2500 -v threadtime -s CXDiagnostics:V ChromeXt-Zygisk:V CXStandalone:V ;;
 *) exit 1;;
esac
