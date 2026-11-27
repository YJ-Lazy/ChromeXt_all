#!/system/bin/sh
[ "$ARCH" = "arm64" ] || abort "! This experimental build requires arm64-v8a"
[ "$API" -ge 27 ] || abort "! Android 8.1 or later required"
[ -s "$MODPATH/manager/ChromeXt-Zygisk-Manager-debug.apk" ] || abort "! Bundled manager APK missing"
[ -f "$MODPATH/install-manager.sh" ] || abort "! Manager installer missing"
# Keep scope on module upgrades. A fresh installation is opt-in.
OLD_SCOPE="/data/adb/modules/chromext_zygisk/injection.list"
if [ "$MODPATH/injection.list" != "$OLD_SCOPE" ] && [ -f "$OLD_SCOPE" ] && [ ! -L "$OLD_SCOPE" ]; then
    cp "$OLD_SCOPE" "$MODPATH/injection.list" || abort "! Cannot preserve browser injection scope"
elif [ ! -f "$MODPATH/injection.list" ]; then
    : > "$MODPATH/injection.list" || abort "! Cannot initialize browser injection scope"
fi
chown 0:0 "$MODPATH/injection.list" || abort "! Cannot set scope owner"
chmod 600 "$MODPATH/injection.list" || abort "! Cannot set scope permissions"
ui_print "- Installing bundled ChromeXt Zygisk manager..."
if [ "$BOOTMODE" = true ] && pm path android >/dev/null 2>&1; then
    RESULT="$(sh "$MODPATH/install-manager.sh" "$MODPATH" 2>&1)"
    STATUS=$?
    ui_print "$RESULT"
    [ "$STATUS" -eq 0 ] || abort "! Manager installation failed. Back up scripts and resolve APK signature/version mismatch before retrying."
    rm -f "$MODPATH/.manager-install-pending"
else
    touch "$MODPATH/.manager-install-pending" || abort "! Cannot schedule manager installation"
    ui_print "- Android Package Manager unavailable; install manager automatically after boot."
fi
ui_print "- Disable other ChromeXt/Xposed injection for the same browser."
ui_print "- Reboot, then choose browsers in ScriptHub > Injection."
