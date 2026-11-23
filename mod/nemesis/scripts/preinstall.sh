#!/system/bin/sh
# NEMESIS preinstall for Digital Control+ (standalone):
# Stops and unlists any prior HMG-API-wrapped install without uninstalling the APK (preserves PIN & paired phones).
umask 077
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

if [ -d "${ROOT:-}/data/hmgapi/mods/gen5w-digital-control" ] && [ -x "${ROOT:-}/system/bin/hmgapid" ]; then
    "${ROOT:-}/system/bin/hmgapid" ping >/dev/null 2>&1 || "${ROOT:-}/system/bin/hmgapid" daemon >/dev/null 2>&1 || true
    "${ROOT:-}/system/bin/hmgapid" disable gen5w-digital-control >/dev/null 2>&1 || true
    "${ROOT:-}/system/bin/hmgapid" service-stop gen5w-digital-control digital-control-svc >/dev/null 2>&1 || true
fi

if [ -z "${ROOT:-}" ] && [ -d "/data/nemesis/apps/hmgapi.gen5w-digital-control" ] && [ -x "/system/xbin/nemesis" ]; then
    HMGAPI_NEMESIS_SYNC=1 /system/xbin/nemesis uninstall hmgapi.gen5w-digital-control >/dev/null 2>&1 || true
fi

exit 0
