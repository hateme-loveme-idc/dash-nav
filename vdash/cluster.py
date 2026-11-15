#!/usr/bin/env python3
"""
Virtual instrument cluster (server side).

Receives the live mirror from the virtual dash (VDash --mirror 127.0.0.1:7000, reached through
`adb reverse tcp:7000 tcp:7000`), decodes the H.264 stream with ffmpeg and draws what the Himalayan 450's
round TFT would show: the video in the top dome, the gold band (street on the left, flag + km left on
the right), and the dash clock. Writes:
  live.png     - refreshed every second
  frames/NNNN.png - one per second (for review)
  cluster.mp4  - the whole ride at 4 fps
  events.log   - mirror of the dash's event log

Usage: cluster.py OUTDIR [port]
"""
import json, os, socket, struct, subprocess, sys, threading, time
from PIL import Image, ImageDraw, ImageFont

W, H = 526, 300
OUT = sys.argv[1]; PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
os.makedirs(os.path.join(OUT, "frames"), exist_ok=True)
F = "/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf"
FM = "/usr/share/fonts/google-noto-vf/NotoSansMono[wght].ttf"
font = lambda size, mono=False: ImageFont.truetype(FM if mono else F, size)

state = {}
latest = [None]
lock = threading.Lock()
log = open(os.path.join(OUT, "events.log"), "w")

dec = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-err_detect", "ignore_err", "-flags", "low_delay", "-probesize", "32",
                        "-f", "h264", "-i", "-", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                       stdin=subprocess.PIPE, stdout=subprocess.PIPE)
mp4 = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                        "-s", "640x640", "-r", "4", "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                        os.path.join(OUT, "cluster.mp4")], stdin=subprocess.PIPE)

def read_frames():
    n = W * H * 3
    while True:
        b = dec.stdout.read(n)
        if len(b) < n: return
        with lock: latest[0] = Image.frombytes("RGB", (W, H), b)

def render():
    """Replica of the Himalayan 450 Tripper Dash TFT, laid out from photos of the real dash."""
    S = 1000; cx = S // 2; R = 470
    img = Image.new("RGB", (S, S), (24, 22, 20))
    d = ImageDraw.Draw(img)
    d.ellipse((cx - R - 26, cx - R - 26, cx + R + 26, cx + R + 26), fill=(150, 150, 152))     # chrome bezel
    d.ellipse((cx - R - 12, cx - R - 12, cx + R + 12, cx + R + 12), fill=(30, 30, 32))
    scr = Image.new("RGB", (S, S), (0, 0, 0)); sd = ImageDraw.Draw(scr)
    # our stream: full dial width, from the top of the circle down to the gold band
    vw = 2 * R; vh = round(vw * H / W); top = cx - R
    with lock: fr = latest[0]
    if fr is not None and state.get("projecting"): scr.paste(fr.resize((vw, vh), Image.LANCZOS), (cx - R, top))
    else: sd.text((cx, top + vh // 2), "waiting for the phone", font=font(30), fill=(110, 110, 110), anchor="mm")
    band = top + vh
    gold, ink = (143, 133, 80), (250, 250, 245)
    music = state.get("music_on") and state.get("now_playing")
    if music:
        parts = (state.get("now_playing") or "").split(" | ")
        sd.rectangle((0, band, S, band + 104), fill=gold)
        sd.text((cx - 340, band + 30), parts[0][:19], font=font(40), fill=ink, anchor="lm")
        sd.text((cx + 330, band + 32), (parts[2] if len(parts) > 2 else "")[:19], font=font(40), fill=ink, anchor="rm")
        y = band + 78; x0 = cx - 330
        sd.polygon([(x0, y), (x0 + 26, y - 14), (x0 + 26, y + 14)], fill=ink); sd.rectangle((x0 - 6, y - 14, x0 - 2, y + 14), fill=ink)   # previous
        if state.get("music_playing"): sd.rectangle((x0 + 120, y - 16, x0 + 128, y + 16), fill=ink); sd.rectangle((x0 + 140, y - 16, x0 + 148, y + 16), fill=ink)
        else: sd.polygon([(x0 + 122, y - 16), (x0 + 150, y), (x0 + 122, y + 16)], fill=ink)
        sd.polygon([(x0 + 270, y), (x0 + 244, y - 14), (x0 + 244, y + 14)], fill=ink); sd.rectangle((x0 + 272, y - 14, x0 + 276, y + 14), fill=ink)  # next
        low = band + 104
    else:
        sd.rectangle((0, band, S, band + 70), fill=gold)
        sd.rectangle((cx + 120, band + 22, cx + 166, band + 50), fill=ink)                 # chequered flag
        for i in range(4):
            for j in range(2):
                if (i + j) % 2: sd.rectangle((cx + 120 + i * 11.5, band + 22 + j * 14, cx + 131 + i * 11.5, band + 36 + j * 14), fill=(30, 30, 30))
        sd.text((cx + 300, band + 36), state.get("total", "0.0 km").replace(" km", ""), font=font(46), fill=ink, anchor="rm")
        sd.text((cx + 306, band + 46), "km", font=font(24), fill=ink, anchor="lm")
        sd.text((cx - 300, band + 104), (state.get("clock", "")[:5] or "--:--") + " | 22°", font=font(40), fill=ink, anchor="lm")
        low = band + 70
    # the dash's own instruments (fixed, not ours)
    sd.text((cx - 360, low + 70), "ODO", font=font(24), fill=ink, anchor="lm")
    sd.text((cx - 220, low + 70), "12345", font=font(44), fill=ink, anchor="lm"); sd.text((cx - 80, low + 78), "km", font=font(24), fill=ink, anchor="lm")
    sd.ellipse((cx - 320, low + 112, cx - 280, low + 140), fill=(230, 170, 30)); sd.polygon([(cx - 210, low + 142), (cx - 186, low + 104), (cx - 162, low + 142)], outline=(220, 40, 40), width=4)
    sd.ellipse((cx - 110, low + 104, cx - 70, low + 144), outline=(230, 170, 30), width=4)
    sd.rectangle((cx - 150, low + 196, cx + 200, low + 206), fill=(70, 70, 70)); sd.rectangle((cx - 150, low + 196, cx - 40, low + 206), fill=(235, 235, 235))
    sd.rounded_rectangle((cx - 40, low + 6, cx + 360, low + 150), 70, outline=gold, width=6, fill=(8, 8, 8))
    sd.text((cx + 25, low + 78), "N", font=font(80), fill=(60, 200, 80), anchor="mm")
    sd.text((cx + 130, low + 78), "Side\nStand", font=font(30), fill=ink, anchor="mm", align="center")
    sd.text((cx + 250, low + 76), "0", font=font(110), fill=ink, anchor="mm"); sd.text((cx + 318, low + 108), "km/h", font=font(22), fill=ink, anchor="mm")
    mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).ellipse((cx - R, cx - R, cx + R, cx + R), fill=255)
    img.paste(scr, (0, 0), mask)
    return img.resize((640, 640), Image.LANCZOS)

stopping = threading.Event()

def painter():
    i = 0
    while not stopping.is_set():
        time.sleep(0.25)
        img = render()
        mp4.stdin.write(img.tobytes())
        if i % 4 == 0:
            img.save(os.path.join(OUT, "live.tmp.png")); os.replace(os.path.join(OUT, "live.tmp.png"), os.path.join(OUT, "live.png"))
            img.save(os.path.join(OUT, "frames", f"{i // 4:04d}.png"))
        i += 1

def recv_exact(c, n):
    b = b""
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

def main():
    threading.Thread(target=read_frames, daemon=True).start()
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT)); srv.listen(1)
    print("cluster listening on 127.0.0.1:%d" % PORT, flush=True)
    c, _ = srv.accept()
    print("virtual dash connected", flush=True)
    threading.Thread(target=painter, daemon=True).start()
    try:
        while True:
            kind = recv_exact(c, 1); n = struct.unpack(">I", recv_exact(c, 4))[0]; data = recv_exact(c, n)
            if kind == b"S": state.update(json.loads(data))
            elif kind == b"E": log.write(data.decode() + "\n"); log.flush()
            elif kind == b"V": dec.stdin.write(data); dec.stdin.flush()
    except EOFError:
        print("virtual dash disconnected", flush=True)
    time.sleep(1.5)
    stopping.set(); time.sleep(0.5)
    dec.stdin.close(); mp4.stdin.close(); mp4.wait()
    json.dump(state, open(os.path.join(OUT, "final-state.json"), "w"), indent=1)

main()
