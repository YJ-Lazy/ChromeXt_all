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
# Preserve the WebUI library verbatim for recovery; the APP uses its own database.
OLD_LIBRARY="/data/adb/modules/chromext_zygisk/library.json"
if [ "$MODPATH/library.json" != "$OLD_LIBRARY" ] && [ -f "$OLD_LIBRARY" ] && [ ! -L "$OLD_LIBRARY" ]; then
    cp "$OLD_LIBRARY" "$MODPATH/library.json" || abort "! Cannot preserve previous WebUI library"
    chown 0:0 "$MODPATH/library.json" && chmod 600 "$MODPATH/library.json" || abort "! Cannot protect previous library"
    ui_print "- Previous WebUI library preserved (not automatically imported)."
fi
# Preserve existing module directory/payload labeling for the Zygisk loader.
if command -v chcon >/dev/null 2>&1; then
    chcon u:object_r:system_file:s0 "$MODPATH" "$MODPATH/payload" "$MODPATH/injection.list" "$MODPATH"/payload/classes*.dex || ui_print "! Cannot set module payload context"
fi
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
