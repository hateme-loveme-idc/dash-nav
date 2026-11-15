#!/usr/bin/env python3
"""
Correlate a mock ride: for every 100 m step, what Google Maps reported (notification), what the virtual dash
received (route card: distance left / street / ETA) and what the cluster showed. Writes RUN/report.md and
RUN/steps.png (one cluster frame per step).   Usage: analyze.py RUN_DIR
"""
import json, os, re, sys, time, glob
from datetime import datetime
from PIL import Image, ImageDraw, ImageFont

RUN = sys.argv[1]
YEAR = datetime.now().year
steps = [json.loads(l) for l in open(f"{RUN}/steps.jsonl")]
steps = [s for s in steps if s["step"] != "end"]

def lc_time(line):
    m = re.match(r"(\d\d)-(\d\d) (\d\d:\d\d:\d\d\.\d+)", line)
    return datetime.strptime(f"{YEAR}-{m.group(1)}-{m.group(2)} {m.group(3)}", "%Y-%m-%d %H:%M:%S.%f").timestamp() if m else None

nav, link = [], []
for l in open(f"{RUN}/logcat.txt", errors="replace"):
    t = lc_time(l)
    if t is None: continue
    if "DashNavFeed" in l:
        m = re.search(r"nav: (.*?)\|(.*?)\|(.*?)\|(-?\d+)/(-?\d+) -> kind=(\S+) leftM=(-?\d+) turnM=(-?\d+)", l)
        if m: nav.append(dict(t=t, title=m.group(1), arrive=m.group(3), kind=m.group(6), left=int(m.group(7)), turn=int(m.group(8))))
    if "STREAMING" in l or "ERROR" in l or "handshake" in l: link.append((t, l.strip()))

ev = []
for l in open(f"{RUN}/phone-vdash/events.log", errors="replace"):
    m = re.match(r"\s*([\d.]+) (.*)", l)
    if m: ev.append((float(m.group(1)), m.group(2)))
# vdash clock -> epoch: "up epoch=" if present, else align "projection ON" with the app's STREAMING line
off = None
for r, s in ev:
    m = re.match(r"up epoch=(\d+)", s)
    if m: off = int(m.group(1)) / 1000 - r
if off is None:
    pon = next(r for r, s in ev if s.startswith("projection ON"))
    st = next(t for t, s in link if "STREAMING" in s)
    off = st - pon
cards = []
for r, s in ev:
    m = re.match(r"card title='(.*)' total=([\d.]+) km eta=(\d{4})", s)
    if m: cards.append(dict(t=r + off, title=m.group(1), km=float(m.group(2)), eta=m.group(3)))

frames = sorted(glob.glob(f"{RUN}/frames/*.png"), key=os.path.getmtime)
ftimes = [os.path.getmtime(f) for f in frames]

def last_before(lst, t):
    c = [x for x in lst if x["t"] <= t]
    return c[-1] if c else None

rows, pics = [], []
for i, s in enumerate(steps):
    t_end = steps[i + 1]["t"] - 0.5 if i + 1 < len(steps) else s["t"] + 9
    n = last_before(nav, t_end); c = last_before(cards, t_end)
    # first notification and card change after this step (reaction time)
    n1 = next((x for x in nav if x["t"] > s["t"] and x["left"] >= 0), None)
    c1 = next((x for x in cards if x["t"] > s["t"]), None)
    react_n = f"{n1['t'] - s['t']:.1f}s" if n1 and n1["t"] < t_end else "-"
    rows.append([s["step"], s["done_m"], s["left_m"],
                 n["left"] if n else "", n["turn"] if n else "", n["kind"] if n else "", (n["title"][:60] if n else ""),
                 f"{c['km']:.1f}" if c else "", c["title"][:34] if c else "", c["eta"] if c else "", react_n])
    k = max([j for j, ft in enumerate(ftimes) if ft <= t_end] or [0])
    if frames: pics.append((s["step"], frames[k]))

with open(f"{RUN}/report.md", "w") as f:
    f.write("| step | mock done m | mock left m | Maps left m | Maps turn m | kind | Maps instruction | dash km | dash title | dash ETA | Maps reacted |\n")
    f.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
    for r in rows: f.write("| " + " | ".join(str(x) for x in r) + " |\n")
    st = json.load(open(f"{RUN}/phone-vdash/state.json"))
    f.write("\nvirtual dash totals: " + ", ".join(f"{k}={st.get(k)}" for k in
            ["frames", "idr", "rtp_lost", "bad_frames", "sps_bad", "acks_key_sent", "acks_frame_sent", "phone_ack_key", "phone_ack_frame",
             "projection_drops", "non_key_starts", "auth_rejects", "route_cards", "heartbeats", "titles_seen"]) + "\n")
print(open(f"{RUN}/report.md").read())

if pics:
    w = 320; cols = 5; rws = (len(pics) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * w, rws * (w + 24)), (12, 14, 19)); d = ImageDraw.Draw(sheet)
    fnt = ImageFont.truetype("/usr/share/fonts/google-noto-vf/NotoSansMono[wght].ttf", 16)
    for j, (stp, p) in enumerate(pics):
        im = Image.open(p).resize((w, w)); x, y = (j % cols) * w, (j // cols) * (w + 24)
        sheet.paste(im, (x, y + 24)); d.text((x + 8, y + 3), f"step {stp}: {rows[j][1]} m", font=fnt, fill=(230, 230, 230))
    sheet.save(f"{RUN}/steps.png")
