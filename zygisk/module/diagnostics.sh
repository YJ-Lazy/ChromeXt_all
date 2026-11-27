#!/system/bin/sh
# Read-only module diagnostics. Independent of the script library.
d="${0%/*}"
printf '=== ChromeXt Zygisk diagnostics ===\n'
printf 'Build: '; cat "$d/build-revision" 2>/dev/null || printf 'unknown\n'
printf 'Android SDK: '; getprop ro.build.version.sdk
printf 'ABI: '; getprop ro.product.cpu.abilist
printf 'Boot completed: '; getprop sys.boot_completed
printf 'WebUI shell UID: '; id -u
printf 'SELinux: '; getenforce 2>/dev/null || true
if [ -e "$d/disable" ]; then echo 'Module disabled'; elif [ -e "$d/remove" ]; then echo 'Module pending removal'; else echo 'Module disable/remove flags absent'; fi
[ ! -d /data/adb/modules_update/chromext_zygisk ] || echo 'Module update pending: reboot required'
if [ -r "$d/boot-status" ]; then
  printf 'Boot service: '; cat "$d/boot-status"
else
  echo 'Boot service: no marker (not proof of Zygisk availability)'
fi
printf '\n=== Runtime files ===\n'
for f in zygisk/arm64-v8a.so payload/classes.dex payload/resources.bin injection.list library.json; do
  if [ -f "$d/$f" ]; then
    printf '%s: ' "$f"
    ls -lnZ "$d/$f" 2>/dev/null || ls -ln "$d/$f"
  else
    printf '%s: MISSING\n' "$f"
  fi
done
printf '\n=== Selected browsers ===\n'
if [ -s "$d/injection.list" ] && [ -f "$d/supported.list" ]; then
  while IFS= read -r pkg; do
    case "$pkg" in ''|*[!A-Za-z0-9_.]*) continue;; esac
    grep -Fxq "$pkg" "$d/supported.list" || continue
    pids="$(pidof "$pkg" 2>/dev/null || true)"
    printf '%s: PID=%s\n' "$pkg" "${pids:-not running}"
  done < "$d/injection.list"
else
  echo 'No selected browser'
fi
printf '\n=== ChromeXt logcat (main/system, filtered) ===\n'
# Read the retained buffers with tag filters, then limit the filtered result.
# -t would limit the input before filtering and could hide all startup entries.
if [ -x /system/bin/logcat ]; then lc=/system/bin/logcat; else lc=logcat; fi
out="$("$lc" -d -b main -b system -v threadtime 'ChromeXt-Zygisk:V' 'CXDiagnostics:V' 'CXStandalone:V' '*:S' 2>&1)"
rc=$?
if [ "$rc" -ne 0 ]; then
  printf 'logcat main/system failed (%s): %s\n' "$rc" "$out"
  out="$("$lc" -d -b main -v threadtime 'ChromeXt-Zygisk:V' 'CXDiagnostics:V' 'CXStandalone:V' '*:S' 2>&1)"
  rc=$?
fi
if [ "$rc" -ne 0 ]; then
  printf 'logcat failed (%s): %s\n' "$rc" "$out"
elif [ -z "$(printf '%s\n' "$out" | grep -v '^--------- beginning of ' | grep -v '^$')" ]; then
  echo 'No retained ChromeXt runtime log entries. Check Zygisk provider/module loading and cold-start a selected browser.'
else
  printf '%s\n' "$out" | tail -n 500
fi
