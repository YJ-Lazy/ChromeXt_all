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
printf '\n=== Zygisk provider evidence (read-only) ===\n'
printf 'Zygote configuration: '; getprop ro.zygote
printf 'Zygote PIDs: '; pidof zygote64 zygote 2>/dev/null || echo unavailable
for m in /data/adb/modules/*; do
  [ -f "$m/module.prop" ] || continue
  case "${m##*/}" in
    *zygisk*|*Zygisk*|*rezygisk*|*riru*)
      printf '%s: ' "${m##*/}"
      if [ -e "$m/disable" ]; then echo disabled
      elif [ -e "$m/remove" ]; then echo pending-removal
      else echo installed-not-proof-of-loading; fi
      grep -E '^(name|version|versionCode)=' "$m/module.prop" 2>/dev/null
      ;;
  esac
done
printf 'Zygote/provider process hints:\n'
ps -A -o PID,NAME 2>/dev/null | grep -Ei 'zygote|zygisk|rezygisk' | head -n 30 || true
printf '\n=== Selected browsers ===\n'
if [ -s "$d/injection.list" ] && [ -f "$d/supported.list" ]; then
  while IFS= read -r pkg; do
    case "$pkg" in ''|*[!A-Za-z0-9_.]*) continue;; esac
    grep -Fxq "$pkg" "$d/supported.list" || continue
    pids="$(pidof "$pkg" 2>/dev/null || true)"
    printf '%s: PID=%s\n' "$pkg" "${pids:-not running}"
    if pm path "$pkg" >/dev/null 2>&1; then
      dumpsys package "$pkg" 2>/dev/null | grep -E '^[[:space:]]*(primaryCpuAbi|secondaryCpuAbi)=' | head -n 2
    else
      echo '  Package unavailable to current user'
    fi
    for pid in $pids; do
      case "$pid" in ''|*[!0-9]*) continue;; esac
      printf '  Process %s executable: ' "$pid"
      readlink "/proc/$pid/exe" 2>/dev/null || echo unavailable
      if [ -r "/proc/$pid/maps" ]; then
        if grep -Eiq 'chromext_zygisk|libchromext|memfd:.*chromext' "/proc/$pid/maps" 2>/dev/null; then
          echo '  ChromeXt mapping hint: present'
        else
          echo '  ChromeXt mapping hint: not visible (not conclusive)'
        fi
        stage="$(grep -oE 'cx_stage_[a-z_]+' "/proc/$pid/maps" 2>/dev/null | sort -u)"
        printf '  ChromeXt process stage: %s\n' "${stage:-unavailable (not conclusive)}"
      else
        echo '  Process mappings unavailable'
      fi
    done
    printf '  Related running processes:\n'
    ps -A -o PID,NAME 2>/dev/null | awk -v pkg="$pkg" '$2 == pkg || index($2, pkg ":") == 1 {print "    " $0}' | head -n 20
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
  printf '%s\n' "$out" | grep -F '[CX-ZYGISK] onLoad' | tail -n 20
  printf '%s\n' "$out" | grep -vF '[CX-ZYGISK] onLoad' | tail -n 480
fi

printf '\n=== Provider loader logs (filtered) ===\n'
"$lc" -d -b main -b system -v threadtime 2>/dev/null |
  grep -Ei 'zygisk|rezygisk' |
  grep -Ei 'chromext|module.*(load|fail|error|reject)|dlopen|api version|unsupported' |
  grep -v 'ChromeXt-Zygisk' | tail -n 100
printf '\n=== Relevant SELinux denials (filtered) ===\n'
denials="$({ "$lc" -d -b all -v brief 2>/dev/null; dmesg 2>/dev/null; } |
  grep -F 'avc: denied' |
  grep -Ei 'chromext|injection\.list|library\.json|resources\.bin|classes[0-9]*\.dex|zygisk' | tail -n 80)"
if [ -n "$denials" ]; then printf '%s\n' "$denials"
else echo 'No matching retained denials (not proof of permission)'; fi
