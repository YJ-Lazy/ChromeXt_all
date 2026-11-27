#!/system/bin/sh
[ "$ARCH" = "arm64" ] || abort "! ARM64 required"
[ "$API" -ge 27 ] || abort "! Android 8.1 or later required"
[ -s "$MODPATH/payload/resources.bin" ] || abort "! Runtime resources missing"
[ -f "$MODPATH/webroot/index.html" ] || abort "! WebUI missing"
[ -f "$MODPATH/cx-snapshot" ] || abort "! Snapshot reader missing"
chmod 755 "$MODPATH/cx-snapshot" "$MODPATH/scope.sh" "$MODPATH/library.sh" || abort "! Cannot set executable permissions"
OLD=/data/adb/modules/chromext_zygisk
for name in injection.list library.json; do
    if [ "$MODPATH" != "$OLD" ] && [ -f "$OLD/$name" ] && [ ! -L "$OLD/$name" ]; then
        cp "$OLD/$name" "$MODPATH/$name" || abort "! Cannot preserve $name"
    fi
done
[ -f "$MODPATH/injection.list" ] || : > "$MODPATH/injection.list"
[ ! -L "$MODPATH/library.json" ] || abort "! Invalid library path"
if [ ! -s "$MODPATH/library.json" ]; then
    printf '%s' '{"format":"ChromeXt-library-v1","scripts":[]}' > "$MODPATH/library.json" || abort "! Cannot initialize script library"
fi
[ -s "$MODPATH/library.json" ] || abort "! Empty library after initialization"
chown 0:0 "$MODPATH/injection.list" "$MODPATH/library.json" "$MODPATH/payload/resources.bin" || abort "! Cannot set ownership"
chmod 600 "$MODPATH/injection.list" "$MODPATH/library.json" "$MODPATH/payload/resources.bin" || abort "! Cannot set permissions"
ui_print "- WebUI-only module. No manager APK will be installed."
ui_print "- Reboot and open ChromeXt Zygisk from the modules page."
ui_print "- Export scripts from an old manager before manually uninstalling it."
