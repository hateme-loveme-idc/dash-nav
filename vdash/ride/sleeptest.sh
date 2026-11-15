#!/bin/bash
ADB="${ADB_BIN:-adb}"; HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
S="am start-foreground-service -n nav.dash.debug/nav.dash.DashService"; DEV=/data/local/tmp/vdash
F=/sdcard/Android/data/nav.dash.debug/files
st() { $ADB shell cat $DEV/run/state.json | python3 -c 'import json,sys;s=json.load(sys.stdin);print(s["acks_key_sent"]+s["acks_frame_sent"])'; }
timeout 30 "$V/run-phone.sh" up 200 >/dev/null
python3 "$HERE/mockride.py" "$HERE/route4.json" /tmp --step 20 --every 3 --lead 20 > /tmp/bd-sleep-mock.out 2>&1 & MR=$!; sleep 4
$ADB shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -a android.intent.action.VIEW -d 'google.navigation:q=$(cat $HERE/dest4.txt)&mode=l' com.google.android.apps.maps" >/dev/null
sleep 12; $ADB shell $S --ez test_mode true >/dev/null; sleep 15
a=$(st); h1=$($ADB shell md5sum $F/full.png | cut -c1-8); sleep 6; b=$(st); echo "awake, panel trick: $((b-a)) frames/6s"
$ADB shell $S --ez sleep_test true >/dev/null; sleep 4
echo "phone: $($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')  $($ADB shell 'dumpsys SurfaceFlinger | grep -m1 powerMode' | tr -d ' ')"
for i in 1 2 3; do a=$(st); sleep 10; b=$(st); h=$($ADB shell md5sum $F/full.png | cut -c1-8); echo "asleep ${i}0s: $((b-a)) frames/10s  map changed: $([ "$h" != "$h1" ] && echo yes || echo NO)  $($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')"; h1=$h; done
$ADB shell dumpsys display | grep -A3 '"dash-nav"' | grep -oE 'state [A-Z]+|mState=[A-Z]+' | head -2
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell $S --ez stop true >/dev/null; kill $MR; wait $MR 2>/dev/null; $ADB shell am force-stop com.google.android.apps.maps
