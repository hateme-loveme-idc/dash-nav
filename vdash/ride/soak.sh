#!/bin/bash
# Soak: stream for MIN minutes during navigation; sample app memory, battery temperature and dash counters every minute.
set -u
ADB="${ADB_BIN:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
MIN=${1:-15}; DEV=/data/local/tmp/vdash
RUN="$V/runs/soak-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$RUN"
$ADB shell "pkill -f '[V]Dash --out'; rm -rf $DEV/soak; mkdir -p $DEV/soak"
$ADB push -q "$V/build/classes.dex" $DEV/classes.dex
python3 "$HERE/mockride.py" "$HERE/route4.json" "$RUN" --step 1 --every 3 --lead 10 > "$RUN/mock.out" 2>&1 &   # creeps 1 m / 3 s
MR=$!
sleep 4; $ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am start -a android.intent.action.VIEW -d "'google.navigation:q=$(cat $HERE/dest4.txt)&mode=l'" com.google.android.apps.maps >/dev/null
sleep 12
nohup $ADB shell "CLASSPATH=$DEV/classes.dex app_process / VDash --out $DEV/soak --seconds $((MIN*60+60))" > "$RUN/vdash.out" 2>&1 < /dev/null &
sleep 2
$ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --ez test_mode true >/dev/null
echo "min pss_kb helper_pss_kb batt_temp_c frames acks lost drops" | tee "$RUN/soak.txt"
for m in $(seq 1 $MIN); do
  sleep 60
  P=$($ADB shell dumpsys meminfo nav.dash | awk '/TOTAL PSS:/{print $3; exit}')
  H=$($ADB shell "dumpsys meminfo nav.dash:helper 2>/dev/null" | awk '/TOTAL PSS:/{print $3; exit}')
  T=$($ADB shell dumpsys battery | awk '/temperature/{print $2/10}')
  S=$($ADB shell cat $DEV/soak/state.json | python3 -c 'import json,sys;s=json.load(sys.stdin);print(s["frames"],s["acks_key_sent"]+s["acks_frame_sent"],s["rtp_lost"],s["projection_drops"])')
  echo "$m $P ${H:-?} $T $S" | tee -a "$RUN/soak.txt"
done
$ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --ez stop true >/dev/null
sleep 4; kill $MR; wait $MR 2>/dev/null
$ADB shell "pkill -f '[V]Dash --out'"; $ADB shell am force-stop com.google.android.apps.maps
echo "done: $RUN"
