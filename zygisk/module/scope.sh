#!/system/bin/sh
set -eu
d="${0%/*}"
[ "$(id -u)" = 0 ] || { echo "WebUI requires root manager" >&2; exit 1; }
[ -f "$d/module.prop" ] && [ -f "$d/supported.list" ] || { echo "Module incomplete" >&2; exit 1; }
[ ! -L "$d/injection.list" ] || exit 1
case "${1:-}" in
  read)
    # Keep stdout as a strict, line-oriented protocol. No installed-app names or shell text.
    installed="$(pm list packages --user 0)" || exit 1
    printf 'CX_SCOPE_V1\n'
    while IFS= read -r pkg; do
      [ -n "$pkg" ] || continue
      present=0; enabled=0
      if printf '%s\n' "$installed" | grep -Fxq "package:$pkg"; then present=1; fi
      if [ -f "$d/injection.list" ] && grep -Fxq "$pkg" "$d/injection.list"; then enabled=1; fi
      printf '%s\t%s\t%s\n' "$pkg" "$present" "$enabled"
    done < "$d/supported.list"
    ;;
  save)
    shift
    [ ! -d /data/adb/modules_update/chromext_zygisk ] || { echo "Module update pending, reboot first" >&2; exit 1; }
    [ ! -e "$d/disable" ] && [ ! -e "$d/remove" ] || { echo "Module disabled or pending removal" >&2; exit 1; }
    umask 077
    t="$(mktemp "$d/.injection.XXXXXX")"
    trap 'rm -f "$t"' EXIT
    for pkg in "$@"; do
      case "$pkg" in ''|*[!A-Za-z0-9_.]*) exit 1;; esac
      grep -Fxq "$pkg" "$d/supported.list" || { echo "Unsupported package" >&2; exit 1; }
      printf '%s\n' "$pkg" >> "$t"
    done
    chown 0:0 "$t"
    chmod 600 "$t"
    mv -f "$t" "$d/injection.list"
    printf 'CX_SAVED\n'
    ;;
  *) exit 1;;
esac
