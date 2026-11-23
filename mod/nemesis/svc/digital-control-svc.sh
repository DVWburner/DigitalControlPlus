#!/system/bin/sh
# Standalone NEMESIS daemon launcher for Digital Control+ (root, mode=restart).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
unset LD_PRELOAD

MOD_DIR=/data/nemesis/apps/gen5w.digitalcontrol
if [ ! -f "$MOD_DIR/svc/digital-control-svc.jar" ]; then
    MOD_DIR=${0%/*}/..
fi
DATA_DIR=/data/nemesis/digital-control

mkdir -p "$DATA_DIR/dalvik-cache"
chmod 700 "$DATA_DIR" "$DATA_DIR/dalvik-cache" 2>/dev/null || true

# Wait for Android boot completion so Binder services and Dalvik start cleanly without hitting restart limits.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

export ANDROID_ROOT=/system
export ANDROID_DATA=$DATA_DIR
export DIGITALCTL_DATA=$DATA_DIR
export DIGITALCTL_MOD_DIR=$MOD_DIR
export CLASSPATH=$MOD_DIR/svc/digital-control-svc.jar

cd "$DATA_DIR" || exit 1
exec /system/bin/app_process /system/bin --nice-name=gen5w.digitalcontrol.digital-control-svc com.gen5w.digitalcontrol.svc.ControlDaemon
