#!/bin/bash
# The rider's real flow on a test phone (debug build, test mode): dash first -> idle screen; navigation started in Maps
# -> automatic take-over; call mid-ride; power button; navigation ends -> Maps back to the phone.
set -u
ADB="${ADB_BIN:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
PKG=${PKG:-nav.dash.debug}; S="am start-foreground-service -n $PKG/nav.dash.DashService"
UI="python3 $HERE/ui.py"; rel() { [ "$PKG" = nav.dash ]; }
DEV=/data/local/tmp/vdash
ROUTE=${1:-$HERE/route4.json}; DEST=${2:-$(cat $HERE/dest4.txt)}
pgrep -af "[m]ockride.py" >/dev/null && { echo "stray mockride running"; exit 1; }
rel || $ADB shell $S --ez stop true >/dev/null 2>&1; $ADB shell am force-stop com.google.android.apps.maps; sleep 2
timeout 30 "$V/run-phone.sh" up 600 >/dev/null || exit 1
RUN=$(readlink -f "$V/runs/current")
L="$RUN/flow.log"; p() { echo "$(date +%H:%M:%S) $*" | tee -a "$L"; }
panel() { $ADB shell "dumpsys SurfaceFlinger | grep -m1 powerMode" 2>/dev/null | tr -d ' ' | cut -d= -f2; }
mapsd() { $ADB shell "am stack list" | grep -B2 -m1 'com.google.android.apps.maps/' | grep -o 'displayId=[0-9]*' | cut -d= -f2; }
st() { $ADB shell cat $DEV/run/state.json 2>/dev/null; }
js() { st | python3 -c "import json,sys;s=json.load(sys.stdin);print(s.get('$1'))" 2>/dev/null; }
snap() { cp "$RUN/live.png" "$RUN/flow-$1.png" 2>/dev/null; }
$ADB logcat -c
nohup $ADB logcat -v time -s DashNav DashNavLink DashNavFeed > "$RUN/logcat.txt" 2>&1 < /dev/null & LC=$!
$ADB shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; cmd media_session dispatch play"
python3 "$HERE/mockride.py" "$ROUTE" "$RUN" --step 100 --every 8 --lead 70 > "$RUN/mockride.out" 2>&1 & MR=$!
sleep 3
p "== 1. start Dash Nav with Maps NOT navigating"
if rel; then PKG=$PKG $UI open >/dev/null; $UI switch "Show wallpaper" off >/dev/null; $UI switch "Test mode (virtual dash)" on >/dev/null; $ADB shell "input swipe 540 500 540 2100 150; input swipe 540 500 540 2100 150; input swipe 540 500 540 2100 150"; $UI tap "Start on dash" >/dev/null; else $ADB shell $S --ez test_mode true --ez wallpaper false >/dev/null; fi; sleep 15; snap 1-idle
p "   dash picture: $(js projecting) projecting, frames $(js frames) · music: $(js now_playing)"
p "   Maps display: $(mapsd) (expect 0 = phone) · phone panel: $(panel) (expect On)"
p "== 2. start navigation in Google Maps on the phone"
$ADB shell "am start -a android.intent.action.VIEW -d 'google.navigation:q=$DEST&mode=l' com.google.android.apps.maps" >/dev/null
T0=$(date +%s); for i in $(seq 1 30); do [ "$(mapsd)" != "0" ] && [ -n "$(mapsd)" ] && break; sleep 1; done
p "   Maps moved to the dash after $(( $(date +%s) - T0 )) s (display $(mapsd)) · phone panel: $(panel) (expect On: never turned off by the app)"
sleep 12; snap 2-nav
p "== 3. call mid-ride (music playing)"
if rel; then p "   (simulated call is debug-only: skipped)"; sleep 34; else $ADB shell $S --es fake_call "${CALLER:-Alex}" >/dev/null; sleep 4; snap 3-ring
p "   ringing: panel $(panel) (expect On) · band: $(js now_playing) · caller card: $(js caller)"
sleep 14; snap 4-incall
p "   in call: band: $(js now_playing)"
sleep 16
p "   after call: band: $(js now_playing) · panel $(panel) (expect On)"; fi
p "== 4. power button"
$ADB shell input keyevent KEYCODE_POWER; sleep 2
p "   press 1 (rider locks): panel $(panel) (expect Off) · $($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*') (expect Awake) · dash frames moving: $(a=$(js frames); sleep 3; b=$(js frames); echo $((b-a)) in 3 s)"
$ADB shell input keyevent KEYCODE_POWER; sleep 2
p "   press 2 (rider wakes): panel $(panel) (expect On) · $($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')"
p "   35 s without touches:"; sleep 35
p "   panel $(panel) (expect On: no automatic screen-off)"
p "== 4b. joystick menu"
$ADB shell "echo 15 > $DEV/run/press"; sleep 1; $ADB shell "echo 15 > $DEV/run/press"; sleep 2.5; snap 6-menu
$ADB shell "echo 15 > $DEV/run/press"; sleep 1; $ADB shell "echo 13 > $DEV/run/press"; sleep 1.5; snap 7-select
p "   menu: down, down, down, right (= next track) · band now: $(js now_playing)"
p "== 5. finish the ride"
wait $MR
p "   mock ride done; waiting for navigation to end + 20 s grace"
for i in $(seq 1 60); do [ "$(mapsd)" = "0" ] && break; sleep 2; done
sleep 3; snap 5-after
p "   Maps display: $(mapsd) (expect 0) · panel $(panel) (expect On) · dash: $(js projecting) projecting"
grep -E "fps to the dash" "$RUN/logcat.txt" | tail -1 >> "$L"
grep -oE "frame: .*fps=[0-9]+" "$RUN/logcat.txt" | grep -oE "fps=[0-9]+" | sort | uniq -c | tr '\n' ' ' >> "$L"; echo >> "$L"
if rel; then PKG=$PKG $UI open >/dev/null; $UI tap "Stop" >/dev/null; $UI switch "Test mode (virtual dash)" off >/dev/null; else $ADB shell $S --ez stop true >/dev/null; fi; sleep 3; kill $LC 2>/dev/null
$ADB shell "pkill -f '[V]Dash --out'" >/dev/null 2>&1; sleep 1
$ADB pull -q $DEV/run "$RUN/phone-vdash"; $ADB shell am force-stop com.google.android.apps.maps
p "done: $RUN"
