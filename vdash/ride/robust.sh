#!/bin/bash
# Robustness on the test phone (test mode):
#  A) dash power-cycle mid-ride: virtual dash killed, app must notice, keep retrying, and resume when it's back
#  B) Stop: Maps back on the phone screen, panel on, helper gone
#  C) app crash (force-stop) while streaming: helper must restore the phone by itself
set -u
ADB="${ADB_BIN:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
DEST=$(cat "$HERE/dest4.txt")
RUN="$V/runs/robust-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$RUN"
DEV=/data/local/tmp/vdash
vdash() { nohup $ADB shell "CLASSPATH=$DEV/classes.dex app_process / VDash --out $DEV/$1 --seconds $2" > "$RUN/$1.out" 2>&1 < /dev/null & }
phone() {
  echo "  panel: $($ADB shell "dumpsys SurfaceFlinger | grep -m1 powerMode" | tr -d ' ')"
  echo "  maps:  $($ADB shell "am stack list" | grep -B2 -m1 'com.google.android.apps.maps/' | grep -o 'displayId=[0-9]*' | head -1)  (0 = phone screen)"
  echo "  maps PiP setting: $($ADB shell appops get com.google.android.apps.maps PICTURE_IN_PICTURE)"
  echo "  helper: $($ADB shell "ps -A -o ARGS | grep -c '[n]av.dash:helper'") process(es)"
}
start_dash() { python3 "$HERE/ui.py" open >/dev/null; python3 "$HERE/ui.py" tap "Start on dash" >/dev/null; }
stop_dash()  { python3 "$HERE/ui.py" open >/dev/null; python3 "$HERE/ui.py" tap "Stop" >/dev/null; }
since() { $ADB logcat -d -v time -s DashNav DashNavLink | grep -v "frame:" | awk -v t="$1" '$2 >= t'; }

$ADB shell "pkill -f '[V]Dash --out'; rm -rf $DEV/a $DEV/b $DEV/c; mkdir -p $DEV/a $DEV/b $DEV/c"
$ADB push -q "$V/build/classes.dex" $DEV/classes.dex
python3 "$HERE/mockride.py" "$HERE/route4.json" "$RUN" --step 100 --every 600 --lead 400 > "$RUN/mock.out" 2>&1 &
MR=$!
sleep 4
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am start -a android.intent.action.VIEW -d "'google.navigation:q=$DEST&mode=l'" com.google.android.apps.maps >/dev/null
sleep 12
echo "== A: dash power cycle"
vdash a 40
sleep 2
$ADB logcat -c
start_dash
sleep 30
T=$(date +%H:%M:%S)
$ADB shell "pkill -f '[V]Dash --out'"; echo "  $T virtual dash killed (ignition off)"
sleep 20
T2=$(date +%H:%M:%S); vdash b 60; echo "  $T2 virtual dash back (ignition on)"
sleep 25
since "$T" | sed 's/^/  /'
echo "  dash b state: $($ADB shell cat $DEV/b/state.json | python3 -c 'import json,sys;s=json.load(sys.stdin);print({k:s[k] for k in ["authed","projecting","frames","idr","rtp_lost","acks_key_sent"]})')"
echo "== B: Stop"
stop_dash
sleep 5
phone
echo "== C: app crash while streaming"
vdash c 60
sleep 2
start_dash
sleep 20
echo " before crash:"; phone
$ADB shell am force-stop nav.dash; echo "  app force-stopped"
sleep 5
echo " after crash:"; phone
kill $MR; wait $MR 2>/dev/null
$ADB shell "pkill -f '[V]Dash --out'"; $ADB shell am force-stop com.google.android.apps.maps
tail -1 "$RUN/mock.out"
echo "done: $RUN"
