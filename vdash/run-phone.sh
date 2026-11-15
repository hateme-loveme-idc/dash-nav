#!/bin/bash
# End-to-end test on the test phone: app (test mode) -> virtual dash on the phone -> live mirror -> cluster on the server.
#   run-phone.sh up   SECONDS          start the cluster + virtual dash (runs SECONDS, then stops by itself)
#   (test mode: am start-foreground-service ... --ez test_mode true --ez record true, debug builds)
#   run-phone.sh press HEX             simulate a joystick button on the virtual dash
#   run-phone.sh pull                  copy the phone-side logs/stream into the current run folder
set -u
ADB="${ADB_BIN:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEV=/data/local/tmp/vdash
CUR="$HERE/runs/current"

case "${1:-}" in
  up)
    SECS=${2:-120}
    RUN="$HERE/runs/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$RUN"; ln -sfn "$RUN" "$CUR"
    $ADB shell "pkill -f '[V]Dash --out'" >/dev/null 2>&1; $ADB shell "rm -rf $DEV/run; mkdir -p $DEV/run"
    $ADB push -q "$HERE/build/classes.dex" $DEV/classes.dex
    $ADB reverse tcp:7000 tcp:7000 >/dev/null
    pkill -f "[v]dash/cluster.py" 2>/dev/null; sleep 0.5
    nohup python3 "$HERE/cluster.py" "$RUN" 7000 > "$RUN/cluster.out" 2>&1 &
    for i in $(seq 1 20); do grep -q listening "$RUN/cluster.out" 2>/dev/null && break; sleep 0.25; done
    nohup $ADB shell "CLASSPATH=$DEV/classes.dex app_process / VDash --out $DEV/run --mirror 127.0.0.1:7000 --seconds $SECS" > "$RUN/vdash.out" 2>&1 < /dev/null &
    for i in $(seq 1 40); do grep -q "virtual dash connected" "$RUN/cluster.out" 2>/dev/null && break; sleep 0.25; done
    cat "$RUN/cluster.out"; echo "run: $RUN"
    ;;
  press) $ADB shell "echo ${2:-22} > $DEV/run/press" ;;
  pull)
    $ADB pull -q $DEV/run "$CUR/phone-vdash" && ls -la "$CUR/phone-vdash"
    ;;
  *) sed -n 2,6p "$0" ;;
esac
