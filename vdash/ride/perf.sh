#!/bin/bash
# A/B load measurement during navigation on the dash: Maps' render rate + CPU per process over 60 s.
#   perf.sh debug|release
ADB="${ADB_BIN:-adb}"; HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
WHICH=$1; DEV=/data/local/tmp/vdash
if [ "$WHICH" = debug ]; then PKG=nav.dash.debug; else PKG=nav.dash; fi
$ADB shell am force-stop com.google.android.apps.maps; sleep 1
timeout 30 "$V/run-phone.sh" up 200 >/dev/null
python3 "$HERE/mockride.py" "$HERE/route4.json" /tmp --step 5 --every 2 --lead 10 > /tmp/bd-perf-mock.out 2>&1 & MR=$!
sleep 3; $ADB shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -a android.intent.action.VIEW -d 'google.navigation:q=$(cat $HERE/dest4.txt)&mode=l' com.google.android.apps.maps" >/dev/null
sleep 12
if [ "$WHICH" = debug ]; then $ADB shell am start-foreground-service -n $PKG/nav.dash.DashService --ez test_mode true >/dev/null
else PKG=$PKG python3 "$HERE/ui.py" open >/dev/null; python3 "$HERE/ui.py" switch "Test mode (virtual dash)" on >/dev/null
     $ADB shell "input swipe 540 500 540 2100 150; input swipe 540 500 540 2100 150; input swipe 540 500 540 2100 150"; python3 "$HERE/ui.py" tap "Start on dash" >/dev/null; fi
sleep 20
$ADB shell dumpsys gfxinfo com.google.android.apps.maps reset >/dev/null
a=$($ADB shell cat $DEV/run/state.json | python3 -c 'import json,sys;print(json.load(sys.stdin)["frames"])')
T=$($ADB shell "top -b -d 60 -n 2 -o %CPU,ARGS" | awk '/^Tasks/{n++} n==2' | grep -E "apps.maps|example|surfaceflinger|system_server|VDash" | head -8)
b=$($ADB shell cat $DEV/run/state.json | python3 -c 'import json,sys;print(json.load(sys.stdin)["frames"])')
F=$($ADB shell dumpsys gfxinfo com.google.android.apps.maps | grep -m1 "Total frames rendered" | grep -oE "[0-9]+")
echo "== $WHICH: Maps rendered $F frames in 60 s ($((F/60)) fps) · dash got $(( (b-a)/60 )) fps"
echo "$T" | awk '{printf "   %5s%%  %s\n", $1, $2}'
echo "   battery temp: $($ADB shell dumpsys battery | awk '/temperature/{print $2/10}') °C"
if [ "$WHICH" = debug ]; then $ADB shell am start-foreground-service -n $PKG/nav.dash.DashService --ez stop true >/dev/null
else python3 "$HERE/ui.py" open >/dev/null; python3 "$HERE/ui.py" tap "Stop" >/dev/null; python3 "$HERE/ui.py" switch "Test mode (virtual dash)" off >/dev/null; fi
kill $MR; wait $MR 2>/dev/null; $ADB shell "pkill -f '[V]Dash --out'"; $ADB shell am force-stop com.google.android.apps.maps
