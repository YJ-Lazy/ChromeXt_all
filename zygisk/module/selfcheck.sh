#!/system/bin/sh
# User-initiated, bounded cold-start capture; never clears logcat or app data.
d="${0%/*}"
last="$d/selfcheck-last.log"
fail() { printf '%s\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "Root WebUI required"
umask 077
case "${1:-}" in
  read)
    [ -f "$last" ] && [ ! -L "$last" ] || fail "No completed self-check yet"
    cat "$last"; exit ;;
  run) ;;
  *) fail "Unknown self-check command";;
esac
pkg="${2:-}"
case "$pkg" in ''|*[!A-Za-z0-9_.]*) fail "Invalid package";; esac
[ ! -e "$d/disable" ] && [ ! -e "$d/remove" ] && [ ! -d /data/adb/modules_update/chromext_zygisk ] || fail "Module disabled or update pending; reboot first"
grep -Fxq "$pkg" "$d/supported.list" && grep -Fxq "$pkg" "$d/injection.list" || fail "Browser is not in saved injection scope"
component="$(cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" 2>/dev/null | tail -n 1)"
case "$component" in "$pkg/"*) ;; *) fail "No launcher activity for selected browser";; esac
case "$component" in *[!A-Za-z0-9_./\$]*) fail "Invalid launcher activity";; esac
tmp="$(mktemp -d "$d/.selfcheck.XXXXXX")" || fail "Cannot create capture directory"
logger=''
cleanup() {
  if [ -n "$logger" ]; then kill "$logger" 2>/dev/null; wait "$logger" 2>/dev/null; fi
  rm -f "$tmp/events" "$tmp/pids" "$tmp/main" "$tmp/report"
  rmdir "$tmp" 2>/dev/null
}
trap cleanup 0
trap 'exit 1' HUP INT TERM
# Keep a private, replaceable result even if the WebUI is backgrounded by launch.
{
  echo '=== ChromeXt cold-start self-check ==='
  printf 'Build: '; cat "$d/build-revision" 2>/dev/null
  printf 'Target: %s\n' "$pkg"
  printf 'Start: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
  printf 'Launcher: %s\n' "$component"
  printf 'Previous main PIDs: '; pidof "$pkg" 2>/dev/null || echo none
  echo 'Capture running; reopen WebUI and read previous self-check after launch.'
} > "$tmp/report"
cp "$tmp/report" "$tmp/main" && mv -f "$tmp/main" "$last" || fail "Cannot store self-check status"
lc=/system/bin/logcat
[ -x "$lc" ] || lc=logcat
# Start before force-stop so even early native callbacks or crashes are captured.
"$lc" -b main -b system -v threadtime -T 1 'ChromeXt-Zygisk:V' 'CXDiagnostics:V' 'CXStandalone:V' 'AndroidRuntime:E' '*:S' > "$tmp/events" 2>&1 &
logger=$!
sleep 1
if ! kill -0 "$logger" 2>/dev/null; then
  echo 'Live logcat failed to start:' >> "$tmp/report"
  cat "$tmp/events" >> "$tmp/report"
else
  echo 'Live logcat started' >> "$tmp/report"
  if am force-stop --user current "$pkg" >> "$tmp/report" 2>&1; then
    echo 'Force-stop returned success' >> "$tmp/report"
    sleep 1
    printf 'PIDs after force-stop: ' >> "$tmp/report"; pidof "$pkg" >> "$tmp/report" 2>/dev/null || echo none >> "$tmp/report"
    am start --user current -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" >> "$tmp/report" 2>&1
    printf 'Launch command exit: %s\n' "$?" >> "$tmp/report"
    : > "$tmp/pids"
    for i in 1 2 3 4 5 6 7 8; do
      pidof "$pkg" >> "$tmp/pids" 2>/dev/null || true
      sleep 1
    done
    pidof "$pkg" >> "$tmp/pids" 2>/dev/null || true
  else
    echo 'Force-stop failed; browser not relaunched' >> "$tmp/report"
  fi
fi
kill "$logger" 2>/dev/null; wait "$logger" 2>/dev/null; logger=''
# Include candidate PIDs even if the process crashed before polling saw it.
touch "$tmp/pids"
awk -v p="$pkg" 'index($0,"candidate process=" p " uid=") {print $3}' "$tmp/events" >> "$tmp/pids"
pids="$(tr ' ' '\n' < "$tmp/pids" | grep -E '^[0-9]+$' | sort -u | tr '\n' ' ')"
{
  printf '\n=== Observed main PIDs ===\n%s\n' "${pids:-none}"
  printf 'Current main PIDs: '; pidof "$pkg" 2>/dev/null || echo none
  for pid in $pids; do
    [ -d "/proc/$pid" ] || continue
    printf 'PID %s executable: ' "$pid"; readlink "/proc/$pid/exe" 2>/dev/null || echo unavailable
    if grep -Eiq 'chromext_zygisk|libchromext|memfd:.*chromext' "/proc/$pid/maps" 2>/dev/null; then
      echo '  ChromeXt mapping hint: present'
    else echo '  ChromeXt mapping hint: not visible (not conclusive)'; fi
  done
} >> "$tmp/report"
awk -v ids="$pids" 'BEGIN{n=split(ids,a," "); for(i=1;i<=n;i++) if(a[i]!="") wanted[a[i]]=1} $3 in wanted {print}' "$tmp/events" > "$tmp/main"
{
  printf '\n=== Main-process stages (log evidence, not proof of script execution) ===\n'
  for stage in 'onLoad' 'candidate process=' 'payload ready' 'postAppSpecialize' 'bootstrap start' 'DEX loader and JNI registration ready' 'Java entry reached' 'LSPlant init=1' 'Application.attach hook installed' 'Application.attach reached' 'BrowserRuntime load starting' 'BrowserRuntime load returned'; do
    if grep -Fq "$stage" "$tmp/main"; then printf '[observed] %s\n' "$stage"
    else printf '[not observed] %s\n' "$stage"; fi
  done
  printf '\n=== Main-process captured logs ===\n'
  if [ -s "$tmp/main" ]; then tail -n 500 "$tmp/main"; else echo 'No matching main-process entries'; fi
  printf '\n=== Other target-specific entries ===\n'
  grep -F "$pkg" "$tmp/events" | tail -n 100
  printf '\nCapture end: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
  echo 'Startup checks only. Open a web page to test script execution.'
} >> "$tmp/report"
mv -f "$tmp/report" "$last" || fail "Cannot save self-check"
cat "$last"
