#!/system/bin/sh
# NEMESIS postinstall for Digital Control+ (standalone):
# Installs or upgrades Gen5WDigitalControl.apk in place (keeping PIN & paired phones) and enables it.
umask 077
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

MOD_DIR=/data/nemesis/apps/gen5w.digitalcontrol
if [ ! -d "$MOD_DIR" ]; then
    MOD_DIR=${0%/*}/..
fi
DATA_DIR=/data/nemesis/digital-control
APK="$MOD_DIR/app/Gen5WDigitalControl.apk"
PKG=com.gen5w.digitalcontrol

mkdir -p "$DATA_DIR/dalvik-cache"
chmod 700 "$DATA_DIR" "$DATA_DIR/dalvik-cache" 2>/dev/null || true

toast() {
    [ "$(getprop sys.boot_completed)" = "1" ] || return 0
    /system/bin/am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

if [ -z "${ROOT:-}" ] && [ "$(getprop sys.boot_completed)" = "1" ] && [ -f "$APK" ]; then
    TMP_APK=/data/local/tmp/Gen5WDigitalControl.apk
    cp "$APK" "$TMP_APK" || { echo "Digital Control+: failed to stage APK"; exit 1; }
    chmod 644 "$TMP_APK"

    setprop sys.boot_completed 0
    out=$(/system/bin/pm install -r "$TMP_APK" 2>&1)
    setprop sys.boot_completed 1

    case "$out" in
        *Success*) ;;
        *INCONSISTENT_CERTIFICATES*)
            case "$(/system/bin/pm path "$PKG" 2>/dev/null)" in
                package:*)
                    /system/bin/pm uninstall "$PKG" >/dev/null 2>&1 || true
                    setprop sys.boot_completed 0
                    out=$(/system/bin/pm install -r "$TMP_APK" 2>&1)
                    setprop sys.boot_completed 1
                    ;;
            esac
            ;;
    esac
    rm -f "$TMP_APK"

    case "$out" in
        *Success*) ;;
        *)
            echo "Digital Control+: APK install failed: $out"
            exit 1
            ;;
    esac

    /system/bin/pm enable "$PKG" >/dev/null 2>&1 || true
    /system/bin/am startservice -n "$PKG/$PKG.CarLinkService" >/dev/null 2>&1 || true
    toast "Digital Control+ installed"
fi

echo "Digital Control+ installed"
exit 0
