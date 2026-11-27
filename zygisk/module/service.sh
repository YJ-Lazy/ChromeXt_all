#!/system/bin/sh
d="${0%/*}"
umask 077
# Record module service invocation without touching browser or user data.
boot="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)"
printf 'service invoked; boot=%s; time=%s\n' "$boot" "$(date '+%Y-%m-%d %H:%M:%S')" > "$d/boot-status"
