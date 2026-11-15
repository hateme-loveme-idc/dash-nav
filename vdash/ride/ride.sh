#!/bin/bash
# Full end-to-end mock ride on the test phone:
#   virtual dash (phone) + cluster (server) + Dash Nav app in test mode + Google Maps navigation
#   driven by a mock GPS that moves 100 m every 10 s.
#   ride.sh ROUTE.json "LAT,LNG" [extra am args...]
set -u
ADB="${ADB_BIN:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
ROUTE=$1; DEST=$2; shift 2
$ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --ez stop true >/dev/null 2>&1
sleep 3
timeout 30 "$V/run-phone.sh" up 400 || exit 1
RUN=$(readlink -f "$V/runs/current")
$ADB logcat -c
nohup $ADB logcat -v time -s DashNav DashNavLink DashNavFeed AndroidRuntime > "$RUN/logcat.txt" 2>&1 < /dev/null &
LC=$!
python3 "$HERE/mockride.py" "$ROUTE" "$RUN" --step 100 --every 10 --lead ${LEAD:-30} > "$RUN/mockride.out" 2>&1 &
MR=$!
sleep 6
$ADB shell input keyevent KEYCODE_WAKEUP
if [[ "$DEST" == takeover:* ]]; then
  # the rider's flow: navigation started in Google Maps on the phone first, then "Start" with no destination
  $ADB shell am start -a android.intent.action.VIEW -d "'google.navigation:q=${DEST#takeover:}&mode=l'" com.google.android.apps.maps
  sleep 15
  if [ "${START:-adb}" = ui ]; then python3 "$HERE/ui.py" open; python3 "$HERE/ui.py" tap "Start on dash"
  else $ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --ez test_mode true --ez record true "$@"; fi
elif [ "${START:-adb}" = ui ]; then
  python3 "$HERE/ui.py" open; python3 "$HERE/ui.py" type "Destination" "$DEST"; python3 "$HERE/ui.py" tap "Start on dash"
else
  $ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --es dest "$DEST" --ez test_mode true --ez record true "$@"
fi
echo "ride running: $RUN"
wait $MR
echo "mock ride finished"; tail -3 "$RUN/mockride.out"
sleep 3
if [ "${START:-adb}" = ui ]; then python3 "$HERE/ui.py" open; python3 "$HERE/ui.py" tap "Stop"
else $ADB shell am start-foreground-service -n ${PKG:-nav.dash.debug}/nav.dash.DashService --ez stop true >/dev/null; fi
sleep 4
kill $LC 2>/dev/null
$ADB shell am force-stop com.google.android.apps.maps   # end the test navigation
$ADB shell "pkill -f '[V]Dash --out'" >/dev/null 2>&1
sleep 2
$ADB pull -q /data/local/tmp/vdash/run "$RUN/phone-vdash"
$ADB pull -q /sdcard/Android/data/${PKG:-nav.dash.debug}/files/dash.h264 "$RUN/app-dash.h264" 2>/dev/null
echo "done: $RUN"
