#!/usr/bin/env python3
"""
Mock GPS ride on a test phone: steps the phone's location along a route every STEP metres.

  mockride.py ROUTE.json OUTDIR [--step 100] [--every 10] [--lead 20]

ROUTE.json is an OSRM route (geojson geometry). The position is re-sent every second (so the fix stays fresh)
and jumps STEP metres every EVERY seconds. Before the first step it holds the start for LEAD seconds so
navigation can start. Writes OUTDIR/steps.jsonl. Always removes the test providers at the end, so the
phone's real GPS comes back.
"""
import os
import json, math, subprocess, sys, time, signal

ADB = [os.environ.get("ADB_BIN", "adb"), "shell"]
PROVIDERS = ["gps", "network", "fused"]

def sh(cmd, check=False):
    r = subprocess.run(ADB + [cmd], capture_output=True, text=True, timeout=20)
    if check and r.returncode: raise RuntimeError(cmd + ": " + r.stderr + r.stdout)
    return r.stdout.strip()

def hav(a, b):
    R = 6371000; la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))

def resample(coords, step):
    """Points every `step` metres along the polyline (lng,lat), plus the exact end."""
    out = [coords[0]]; carry = 0.0
    for a, b in zip(coords, coords[1:]):
        seg = hav(a, b); pos = step - carry
        while pos <= seg:
            t = pos / seg; out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)); pos += step
        carry = (carry + seg) % step
    if hav(out[-1], coords[-1]) > 5: out.append(coords[-1])
    return out

def setup():
    sh("appops set com.android.shell android:mock_location allow", True)
    for p in PROVIDERS:
        sh(f"cmd location providers remove-test-provider {p}")
        sh(f"cmd location providers add-test-provider {p} --supportsSpeed --supportsBearing --supportsAltitude", True)
        sh(f"cmd location providers set-test-provider-enabled {p} true", True)

def put(lng, lat):
    for p in PROVIDERS:
        sh(f"cmd location providers set-test-provider-location {p} --location {lat:.6f},{lng:.6f} --accuracy 4")

def teardown():
    for p in PROVIDERS: sh(f"cmd location providers remove-test-provider {p}")
    sh("appops set com.android.shell android:mock_location default")
    print("test providers removed; real GPS restored", flush=True)

def main():
    route, out = sys.argv[1], sys.argv[2]
    opt = dict(zip(sys.argv[3::2], sys.argv[4::2]))
    step = float(opt.get("--step", 100)); every = float(opt.get("--every", 10)); lead = float(opt.get("--lead", 20))
    coords = json.load(open(route))["routes"][0]["geometry"]["coordinates"]
    pts = resample(coords, step)
    total = sum(hav(a, b) for a, b in zip(coords, coords[1:]))
    print(f"route {total:.0f} m, {len(pts)} points every {step:.0f} m", flush=True)
    log = open(f"{out}/steps.jsonl", "w")
    signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
    setup()
    try:
        t0 = time.time()
        for i, (lng, lat) in enumerate(pts):
            done = min(total, i * step)
            hold = lead if i == 0 else every
            rec = {"step": i, "t": round(time.time(), 2), "done_m": round(done), "left_m": round(total - done)}
            log.write(json.dumps(rec) + "\n"); log.flush()
            print(f"step {i:2d}  {done:5.0f} m done  {total - done:5.0f} m left", flush=True)
            end = time.time() + hold
            while time.time() < end:
                put(lng, lat); time.sleep(1)
        # stay at the destination so Maps can announce arrival
        end = time.time() + 20
        while time.time() < end: put(*pts[-1]); time.sleep(1)
        log.write(json.dumps({"step": "end", "t": round(time.time(), 2)}) + "\n")
    finally:
        teardown()

main()
