#!/bin/bash
# Steady-state CPU of Maps / the app / SurfaceFlinger during navigation on the dash (debug build, test mode), 45 s average.
ADB="${ADB_BIN:-adb}"; HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
S="am start-foreground-service -n nav.dash.debug/nav.dash.DashService"
$ADB shell $S --ez stop true >/dev/null 2>&1; $ADB shell am force-stop com.google.android.apps.maps; sleep 2
timeout 30 "$V/run-phone.sh" up 200 >/dev/null
python3 "$HERE/mockride.py" "$HERE/route4.json" /tmp --step 5 --every 2 --lead 10 > /tmp/bd-cpu-mock.out 2>&1 & MR=$!
sleep 3; $ADB shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -a android.intent.action.VIEW -d 'google.navigation:q=$(cat $HERE/dest4.txt)&mode=l' com.google.android.apps.maps" >/dev/null
sleep 10; $ADB shell $S --ez test_mode true >/dev/null; sleep 25
$ADB shell "top -b -d 45 -n 2 -o %CPU,ARGS" | awk '/^Tasks/{n++} n==2' | grep -E "apps.maps$|example.dash.debug|surfaceflinger" | awk '{printf "   %5s%%  %s\n",$1,$2}'
echo "   temp $($ADB shell dumpsys battery | awk '/temperature/{print $2/10}') °C"
$ADB shell $S --ez stop true >/dev/null; kill $MR; wait $MR 2>/dev/null; $ADB shell "pkill -f '[V]Dash --out'"; $ADB shell am force-stop com.google.android.apps.maps
