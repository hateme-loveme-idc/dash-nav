#!/bin/bash
# Joystick events during a ride (virtual dash presses). Logs to $1/events-side.log
ADB="${ADB_BIN:-adb}"; L="$1/events-side.log"; DEV=/data/local/tmp/vdash/run
p() { echo "$(date +%H:%M:%S) $*" >> "$L"; }
press() { $ADB shell "echo $1 > $DEV/press"; p "press $1 $2"; }
sleep 55; press 14 "LEFT  (zoom in)";  sleep 6; press 14 "LEFT (zoom in)"; sleep 8
press 13 "RIGHT (zoom out)"; sleep 4; press 13 "RIGHT"; sleep 4; press 13 "RIGHT"; sleep 8
press 15 "DOWN"; sleep 1.2; press 15 "DOWN (menu)"; sleep 4; press 15 "DOWN (next: play/pause)"; sleep 3; press 13 "RIGHT (select)"; sleep 4
p "music: $($ADB shell dumpsys media_session | grep -A12 'package=com.example.music' | grep -m1 -o 'state=[A-Z]*([0-9])')"
press 13 "RIGHT then ghost 15 at +150ms"; sleep 0.15; press 15 "ghost DOWN"
sleep 6; press 15 "DOWN"; sleep 1; press 15 "DOWN (menu)"; sleep 3; press 13 "RIGHT (select: zoom normal)"
