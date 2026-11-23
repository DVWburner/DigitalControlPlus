#!/system/bin/sh
# NEMESIS Manager Launch entry for Digital Control+.
[ "$(getprop sys.boot_completed)" = 1 ] || { echo "Android is still starting; wait a moment"; exit 1; }
case "$(pm path com.gen5w.digitalcontrol 2>/dev/null)" in
    package:*) ;;
    *)
        echo "Digital Control+ APK is not installed yet; wait a few seconds or apply mods in HMG-API"
        exit 1 ;;
esac
out="$(am start -a android.intent.action.MAIN -n com.gen5w.digitalcontrol/com.gen5w.digitalcontrol.MainActivity 2>&1)" || {
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
