#!/bin/bash
# Side events during a ride (debug build): call, power button, joystick. Logs to $1/events-side.log
ADB="${ADB_BIN:-adb}"; L="$1/events-side.log"; DEV=/data/local/tmp/vdash/run
p() { echo "$(date +%H:%M:%S) $*" >> "$L"; }
panel() { $ADB shell "dumpsys SurfaceFlinger | grep -m1 powerMode" 2>/dev/null | tr -d ' '; }
sleep 60;  p "fake call from Alex"; $ADB shell am start-foreground-service -n nav.dash.debug/nav.dash.DashService --es fake_call Alex >/dev/null
sleep 5;   p "during ring: $(panel)"
sleep 3;   p "joystick UP (answer)"; $ADB shell "echo 06 > $DEV/press"
sleep 20;  p "after call: $(panel)"
sleep 10;  p "before power press: $(panel)"; $ADB shell input keyevent KEYCODE_POWER
sleep 3;   p "after 1st power press (expect phone screen ON): $(panel) wake=$($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')"
sleep 12;  $ADB shell input keyevent KEYCODE_POWER
sleep 3;   p "after 2nd power press (expect OFF, still awake): $(panel) wake=$($ADB shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')"
sleep 12;  p "joystick RIGHT (next track)"; $ADB shell "echo 09 > $DEV/press"
sleep 5;   p "now playing: $($ADB shell dumpsys media_session | grep -A12 'package=com.example.music' | grep -m1 -o 'description=[^,]*')"
