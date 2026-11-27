#!/system/bin/sh
[ "$ARCH" = "arm64" ] || abort "! This experimental build requires arm64-v8a"
[ "$API" -ge 27 ] || abort "! Android 8.1 or later required"
ui_print "- Install the matching ChromeXt Zygisk manager APK separately."
ui_print "- Disable other ChromeXt/Xposed injection for the same browser."
ui_print "- Reboot after installing or removing this module."
