#!/system/bin/sh
# NEMESIS preuninstall for Digital Control+ (standalone):
# Preserves APK & trust_vault.json on in-place updates; performs full wipe + pm uninstall on removal.
umask 077
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

PKG=com.gen5w.digitalcontrol
ID=gen5w.digitalcontrol
STAGE=${ROOT:-}/data/local/tmp/nemesis/stage/$ID
caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case "$caller" in
    *uninstall*) ;;
    *)
        if grep -q "^id=$ID\$" "$STAGE/NEMESIS.data" 2>/dev/null; then
            echo "Digital Control+: updating in place (keeping PIN & paired phones)"
            exit 0
        fi
        ;;
esac

if [ -z "${ROOT:-}" ] && [ "$(getprop sys.boot_completed)" = "1" ]; then
    case "$(/system/bin/pm path "$PKG" 2>/dev/null)" in
        package:*)
            out=$(/system/bin/pm uninstall "$PKG" 2>&1)
            case "$out" in
                *Success*|*Unknown*) ;;
                *) echo "Digital Control+: warning uninstalling APK: $out" ;;
            esac
            ;;
    esac
fi

rm -rf "${ROOT:-}/data/nemesis/digital-control"
echo "Digital Control+ removed"
exit 0
