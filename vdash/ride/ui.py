#!/usr/bin/env python3
"""
Drive the Dash Nav UI on the test phone like a rider (needed for release builds, whose service isn't adb-startable).
  ui.py tap "Start on dash"        tap the first element whose text/description contains the string
  ui.py switch "Test mode" on|off  set a settings switch (by its row label)
  ui.py text                       print all visible texts
  ui.py open                       open the app
"""
import os
import re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = [os.environ.get("ADB_BIN", "adb")]

def sh(*a): return subprocess.run(ADB + ["shell", *a], capture_output=True, text=True, timeout=30).stdout

def dump():
    for _ in range(3):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        x = sh("cat", "/sdcard/ui.xml")
        if x.strip().startswith("<?xml"): return ET.fromstring(x)
        time.sleep(1)
    raise SystemExit("ui dump failed")

def centre(n):
    a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
    return (a + c) // 2, (b + d) // 2

def find(root, s):
    for n in root.iter("node"):                                    # exact text wins ("Stop" vs "add stops")
        if s.lower() in (n.get("text", "").strip().lower(), n.get("content-desc", "").strip().lower()): return n
    for n in root.iter("node"):
        if s.lower() in (n.get("text", "") + " " + n.get("content-desc", "")).lower(): return n
    return None

def scroll_find(s, tries=9):
    for _ in range(tries):
        r = dump(); n = find(r, s)
        if n is not None: return r, n
        sh("input", "swipe", "540", "1700", "540", "700", "300"); time.sleep(0.8)
    raise SystemExit(f"not found: {s}")

cmd = sys.argv[1]
if cmd == "open":
    import os; pkg = os.environ.get("PKG", "nav.dash"); sh("am", "start", "-n", pkg + "/nav.dash.MainActivity"); time.sleep(2)
elif cmd == "tap":
    r, n = scroll_find(sys.argv[2]); x, y = centre(n); sh("input", "tap", str(x), str(y)); print("tapped", sys.argv[2])
elif cmd == "switch":
    r, n = scroll_find(sys.argv[2])
    _, y = centre(n)
    sw = [m for m in r.iter("node") if m.get("class") == "android.widget.Switch"]
    sw = min(sw, key=lambda m: abs(centre(m)[1] - y))      # the switch on that row
    want = sys.argv[3] == "on"
    if (sw.get("checked") == "true") != want:
        x, y2 = centre(sw); sh("input", "tap", str(x), str(y2)); print("switched", sys.argv[2], sys.argv[3])
    else: print(sys.argv[2], "already", sys.argv[3])
elif cmd == "type":                      # ui.py type "Destination (optional)" "some text"
    r, n = scroll_find(sys.argv[2]); x, y = centre(n); sh("input", "tap", str(x), str(y)); time.sleep(0.5)
    sh("input", "keyevent", "KEYCODE_MOVE_END"); sh("input", "keyevent", "--longpress", "KEYCODE_DEL")
    sh("input", "text", sys.argv[3].replace(" ", "%s")); sh("input", "keyevent", "KEYCODE_BACK"); print("typed", sys.argv[3])
elif cmd == "text":
    for n in dump().iter("node"):
        t = n.get("text") or n.get("content-desc")
        if t: print(t)
