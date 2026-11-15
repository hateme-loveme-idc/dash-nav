#!/bin/bash
# Power button with a PIN-locked phone: does the dash keep its live map? (temporary PIN, cleared at the end)
ADB="${ADB_BIN:-adb}"; HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."
S="am start-foreground-service -n nav.dash.debug/nav.dash.DashService"; DEV=/data/local/tmp/vdash
st() { $ADB shell cat $DEV/run/state.json | python3 -c 'import json,sys;s=json.load(sys.stdin);print(s["acks_key_sent"]+s["acks_frame_sent"])'; }
panel() { $ADB shell "dumpsys SurfaceFlinger | grep -m1 powerMode" 2>/dev/null | tr -d ' '; }
wake() { $ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'; }
lock() { $ADB shell "dumpsys window | grep -m1 -oE 'mKeyguardShowing=(true|false)|isKeyguardShowing=(true|false)'" 2>/dev/null; }
F=/sdcard/Android/data/nav.dash.debug/files
snaphash() { $ADB shell md5sum $F/full.png | cut -c1-8; }
$ADB shell $S --ez stop true >/dev/null 2>&1; sleep 3
timeout 30 "$V/run-phone.sh" up 300 >/dev/null
python3 "$HERE/mockride.py" "$HERE/route4.json" /tmp --step 25 --every 4 --lead 20 > /tmp/bd-pin-mock.out 2>&1 &
MR=$!; sleep 4
$ADB shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -a android.intent.action.VIEW -d 'google.navigation:q=$(cat $HERE/dest4.txt)&mode=l' com.google.android.apps.maps" >/dev/null
sleep 12; $ADB shell $S --ez test_mode true >/dev/null; sleep 15
$ADB shell locksettings set-pin 1357 >/dev/null; echo "PIN set (temporary)"
a=$(st); sleep 8; b=$(st); h1=$(snaphash); echo "streaming: $((b-a)) frames/8s  $(panel) $(wake)"
$ADB shell input keyevent KEYCODE_POWER; sleep 3
a=$(st); sleep 8; b=$(st); h2=$(snaphash); echo "after power #1: $((b-a)) frames/8s  $(panel) $(wake) $(lock)  map changed: $([ "$h1" != "$h2" ] && echo yes || echo NO)"
$ADB shell input keyevent KEYCODE_POWER; sleep 3
a=$(st); sleep 8; b=$(st); h3=$(snaphash); echo "after power #2: $((b-a)) frames/8s  $(panel) $(wake) $(lock)  map changed: $([ "$h2" != "$h3" ] && echo yes || echo NO)"
$ADB shell locksettings clear --old 1357 >/dev/null; echo "PIN cleared: $($ADB shell locksettings get-disabled 2>/dev/null)"
$ADB shell $S --ez stop true >/dev/null; kill $MR; wait $MR 2>/dev/null; $ADB shell am force-stop com.google.android.apps.maps
