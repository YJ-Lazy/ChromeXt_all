#!/system/bin/sh
MODDIR="${0%/*}"
PENDING="$MODDIR/.manager-install-pending"
[ -f "$PENDING" ] || exit 0
# Recovery flash fallback: wait for Android and its package service.
i=0
while [ "$i" -lt 120 ]; do
    if [ "$(getprop sys.boot_completed)" = 1 ] && pm path android >/dev/null 2>&1; then
        sh "$MODDIR/install-manager.sh" "$MODDIR" > "$MODDIR/manager-install.log" 2>&1
        STATUS=$?
        rm -f "$PENDING"
        if [ "$STATUS" -ne 0 ]; then
            touch "$MODDIR/disable"
            log -t ChromeXt-Zygisk "Manager install failed; module disabled for next reboot. See manager-install.log"
        else
            log -t ChromeXt-Zygisk "Bundled manager installed successfully"
        fi
        exit "$STATUS"
    fi
    sleep 5
    i=$((i+1))
done
echo "Package Manager unavailable after boot; reflash while Android is running." > "$MODDIR/manager-install.log"
rm -f "$PENDING"
touch "$MODDIR/disable"
log -t ChromeXt-Zygisk "Manager install timeout; module disabled for next reboot."
exit 1
