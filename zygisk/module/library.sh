#!/system/bin/sh
set -eu
d="${0%/*}"
f="$d/library.json"
max=16777216
[ "$(id -u)" = 0 ] && [ -f "$d/module.prop" ] && [ -f "$f" ] && [ ! -L "$f" ] || exit 1
hash() { sha256sum "$f" | cut -d ' ' -f 1; }
number() { case "$1" in ''|*[!0-9]*) exit 1;; esac; [ "${#1}" -le 8 ] || exit 1; }
validtoken() { case "$1" in ''|*[!a-f0-9]*) exit 1;; esac; [ "${#1}" = 32 ] || exit 1; }
active() { [ ! -d /data/adb/modules_update/chromext_zygisk ] && [ ! -e "$d/disable" ] && [ ! -e "$d/remove" ] || { echo "Module update pending or disabled; reboot first" >&2; exit 1; }; }
case "${1:-}" in
 info) printf '%s %s\n' "$(hash)" "$(wc -c < "$f" | tr -d '[:space:]')" ;;
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
   [ "$(wc -c < "$t" | tr -d '[:space:]')" = "$3" ] || exit 1
   [ "${#4}" -le 22000 ] || exit 1
   case "$4" in *[!A-Za-z0-9+/=]*) exit 1;; esac
   printf '%s' "$4" | base64 -d >> "$t"
   [ "$(wc -c < "$t")" -le "$max" ] || exit 1
   ;;
 commit)
   active; validtoken "$2"; number "$4"
   t="$d/.library-$2/data"; [ -f "$t" ] && [ ! -L "$t" ] || exit 1
   [ "$4" -gt 0 ] && [ "$4" -le "$max" ] && [ "$(wc -c < "$t" | tr -d '[:space:]')" = "$4" ] || exit 1
   [ "$(hash)" = "$3" ] || { echo "Library changed; reload before saving" >&2; exit 1; }
   chown 0:0 "$t"; chmod 600 "$t"; mv -f "$t" "$f"; rmdir "$d/.library-$2"
   printf '%s\n' "$(hash)"
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
   wc -c < "$t" | tr -d '[:space:]'
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
