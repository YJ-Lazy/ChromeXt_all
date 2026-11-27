#!/system/bin/sh
# Invoked only by module installation or the one-shot boot fallback.
MODDIR="$1"
APK="$MODDIR/manager/ChromeXt-Zygisk-Manager-debug.apk"
if [ ! -s "$APK" ]; then
    echo "! Bundled manager APK is missing"
    exit 1
fi
SIZE="$(wc -c < "$APK" | tr -d '[:space:]')"
case "$SIZE" in
    ''|*[!0-9]*) echo "! Cannot determine APK size"; exit 1 ;;
esac
# Stream the APK to Package Manager rather than making module files
# accessible to the package installer. Keep all existing application data.
RESULT="$(pm install -r -t --user 0 -S "$SIZE" < "$APK" 2>&1)"
STATUS=$?
printf '%s\n' "$RESULT"
if [ "$STATUS" -ne 0 ] || ! printf '%s\n' "$RESULT" | grep -qx 'Success'; then
    echo "! Manager install failed. Check signing key and installed version."
    echo "! Existing app and data have not been uninstalled or cleared."
    exit 1
fi
exit 0
