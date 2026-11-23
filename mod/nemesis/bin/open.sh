#!/system/bin/sh
# NEMESIS Manager Launch entry for Digital Control+ (standalone).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
[ "$(getprop sys.boot_completed)" = "1" ] || { echo "Android is still starting; wait a moment"; exit 1; }

MOD_DIR=/data/nemesis/apps/gen5w.digitalcontrol
if [ ! -d "$MOD_DIR" ]; then
    MOD_DIR=${0%/*}/..
fi
PKG=com.gen5w.digitalcontrol

case "$(/system/bin/pm path "$PKG" 2>/dev/null)" in
    package:*) ;;
    *)
        if [ -f "$MOD_DIR/app/Gen5WDigitalControl.apk" ] && [ -f "$MOD_DIR/scripts/postinstall.sh" ]; then
            /system/bin/sh "$MOD_DIR/scripts/postinstall.sh" || exit 1
        else
            echo "Digital Control+ APK is not installed yet; wait a few seconds or reinstall via NEMESIS"
            exit 1
        fi
        ;;
esac

/system/bin/pm enable "$PKG" >/dev/null 2>&1 || true
out=$(/system/bin/am start -a android.intent.action.MAIN -n "$PKG/$PKG.MainActivity" 2>&1) || {
    echo "Failed to launch Digital Control+: $out"
    exit 1
}
case "$out" in
    *Error:*|*Exception*)
        echo "Failed to launch Digital Control+: $out"
        exit 1 ;;
esac
echo "Digital Control+ opened"
exit 0
