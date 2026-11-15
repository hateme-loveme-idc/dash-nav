package nav.dashd;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/**
 * dashd: runs Google Maps on a hidden display and turns it into the dash video stream.
 *
 *   hidden display (VW x VH) --Maps--> ImageReader --crop map + draw overlay--> encoder (OW x OH, H.264, FPS)
 *
 * Runs as a privileged process (root via su, or shell via Shizuku) started with app_process.
 *   usage: dashd [nav "<destination>"] [seconds]
 * Output (prototype): /data/local/tmp/dashd/out.h264 (Annex-B), snap-N.png snapshots, dashd.log
 */
public final class Dashd {
    // hidden display: big enough that Google's own buttons are small, so a clean map can be cropped
    static final int VW = 1052, VH = 600, VDPI = 140;
    // dash stream format (Tripper Dash: 526x300, ~4 fps)
    static final int OW = 526, OH = 300, FPS = 4, BITRATE = 400_000;
    // the clean map region inside Maps' navigation screen at VWxVH/VDPI (no turn card, ETA bar or buttons)
    static final Rect CROP = new Rect(462, 296, 462 + 520, 296 + 300);
    static final String OUT = "/data/local/tmp/dashd";
    static final String MAPS = "com.google.android.apps.maps";

    static PrintStream log;

    public static void main(String[] args) throws Exception {
        new File(OUT).mkdirs();
        log = new PrintStream(new FileOutputStream(OUT + "/dashd.log"), true);
        String dest = args.length >= 2 && args[0].equals("nav") ? args[1] : null;
        int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : (args.length == 1 ? Integer.parseInt(args[0]) : 60);
        L("dashd start: dest=" + dest + " seconds=" + seconds + " uid=" + android.os.Process.myUid());

        Looper.prepareMainLooper();
        Context ctx = shellContext();
        DisplayManager dm = newDisplayManager(ctx);

        ImageReader reader = ImageReader.newInstance(VW, VH, PixelFormat.RGBA_8888, 3);
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 10);   // VIRTUAL_DISPLAY_FLAG_TRUSTED (hidden): lets other apps' activities run here
        VirtualDisplay vd = dm.createVirtualDisplay("dash-nav", VW, VH, VDPI, reader.getSurface(), flags);
        int displayId = vd.getDisplay().getDisplayId();
        L("hidden display id=" + displayId);

        Panel panel = new Panel();
        Runtime.getRuntime().addShutdownHook(new Thread(panel::on));            // normal exit / SIGINT / SIGTERM
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { L("FATAL " + t.getName() + ": " + android.util.Log.getStackTraceString(e)); panel.on(); System.exit(1); });
        panel.off();                                   // phone panel dark, phone stays awake
        sh("am start --display " + displayId + " -n " + MAPS + "/com.google.android.maps.MapsActivity");
        if (dest != null) {
            Thread.sleep(15000);                       // let Maps settle on the hidden display
            sh("am start --display " + displayId + " -a android.intent.action.VIEW -d 'google.navigation:q="
                    + dest.replace(' ', '+') + "&mode=l' " + MAPS);
            L("navigation requested: " + dest);
        }

        NavState nav = new NavState();
        Thread poller = new Thread(() -> { while (true) { nav.poll(); sleepQuiet(1000); } });
        poller.setDaemon(true); poller.start();

        Encoder enc = new Encoder(OUT + "/out.h264");
        Overlay overlay = new Overlay();
        Bitmap full = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        long end = System.currentTimeMillis() + seconds * 1000L, frame = 0, lastSnap = 0;
        try {
            while (System.currentTimeMillis() < end) {
                long t0 = System.currentTimeMillis();
                Image img = reader.acquireLatestImage();
                if (img != null) { copy(img, full); img.close(); }
                Canvas c = enc.surface.lockHardwareCanvas();
                try {
                    c.drawColor(Color.BLACK);
                    c.drawBitmap(full, CROP, new Rect(0, 0, OW, OH), new Paint(Paint.FILTER_BITMAP_FLAG));
                    overlay.draw(c, nav.snapshot());
                } finally { enc.surface.unlockCanvasAndPost(c); }
                enc.drain(false);
                frame++;
                if (t0 - lastSnap > 10_000) { snapshot(full, overlay, nav.snapshot(), frame); lastSnap = t0; }
                long dt = System.currentTimeMillis() - t0;
                sleepQuiet(Math.max(0, 1000 / FPS - dt));
            }
        } finally {
            enc.finish();
            panel.on();
            vd.release();
            L("done: " + frame + " frames, " + enc.bytes + " bytes");
            System.exit(0);
        }
    }

    // ---------------- navigation data from Google Maps' notification ----------------
    static final class NavState {
        static final class Snap { String instruction = "", street = "", turnDist = "", arrive = ""; long leftM = -1, totalM = -1; String kind = "straight"; }
        private volatile Snap cur = new Snap();
        Snap snapshot() { return cur; }

        void poll() {
            try {
                String dump = shOut("dumpsys notification --noredact");
                // only look at Maps' navigation record; nothing else from the dump is kept
                int i = dump.indexOf("pkg=" + MAPS);
                Snap s = new Snap();
                while (i >= 0) {
                    int j = dump.indexOf("NotificationRecord(", i + 1); if (j < 0) j = dump.length();
                    String rec = dump.substring(i, j);
                    if (rec.contains("channel=1_foreground_1") || rec.contains("ProgressStyle")) {
                        s.instruction = field(rec, "android.title");
                        String sub = field(rec, "android.subText");
                        Matcher m = Pattern.compile("(\\d{1,2}:\\d{2}\\s*[ap]m)", Pattern.CASE_INSENSITIVE).matcher(sub);
                        if (m.find()) s.arrive = m.group(1).toLowerCase();
                        long p = num(field(rec, "android.progress")), pm = num(field(rec, "android.progressMax"));
                        if (pm > 0) { s.totalM = pm; s.leftM = Math.max(0, pm - p); }
                        parseInstruction(s);
                        cur = s; return;
                    }
                    i = dump.indexOf("pkg=" + MAPS, j);
                }
                cur = s;
            } catch (Exception e) { L("poll: " + e); }
        }

        static String field(String rec, String key) {
            Matcher m = Pattern.compile(Pattern.quote(key) + "=\\w+ \\((.*?)\\)\\s*$", Pattern.MULTILINE).matcher(rec);
            return m.find() ? m.group(1).trim() : "";
        }
        static long num(String v) { try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1; } }

        static void parseInstruction(Snap s) {
            String t = s.instruction;
            Matcher d = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s?(m|km)\\b", Pattern.CASE_INSENSITIVE).matcher(t);
            if (d.find()) s.turnDist = d.group(1) + " " + d.group(2).toLowerCase();
            Matcher st = Pattern.compile("(?:onto|toward|towards|on|to)\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(t);
            s.street = st.find() ? st.group(1) : t;
            String l = t.toLowerCase(Locale.ROOT);
            s.kind = l.contains("u-turn") ? "uturn" : l.contains("roundabout") ? "roundabout"
                    : l.contains("slight right") || l.contains("keep right") ? "slight_right"
                    : l.contains("slight left") || l.contains("keep left") ? "slight_left"
                    : l.contains("right") ? "right" : l.contains("left") ? "left" : "straight";
        }
    }

    // ---------------- Option B overlay ----------------
    static final class Overlay {
        final Paint card = p(0xF00F5F46), chip = p(0xE60C0E13), white = p(Color.WHITE), mint = p(0xFF7EE0B5);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface bold = font(700), med = font(500);
        Overlay() { stroke.setColor(Color.WHITE); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND); }
        /** System fonts aren't loaded in a bare app_process: build the typeface from the font file. */
        static boolean fontsInit;
        static Typeface font(int weight) {
            if (!fontsInit) { fontsInit = true;
                // Android 17 initialises Typeface lazily inside app processes; a bare app_process has to do it by hand
                for (String n : new String[]{"staticInitializer", "init", "loadPreinstalledSystemFontMap"}) {
                    try { Method m = Typeface.class.getDeclaredMethod(n); m.setAccessible(true); m.invoke(null); L("Typeface." + n + "() ok"); }
                    catch (Throwable t) { L("Typeface." + n + "(): " + (t instanceof InvocationTargetException ? ((InvocationTargetException) t).getTargetException() : t)); }
                }
            }
            for (String f : new String[]{"/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoFlex-Regular.ttf", "/system/fonts/DroidSans.ttf"}) {
                try {
                    if (!new File(f).exists()) continue;
                    android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(new File(f)).setWeight(weight)
                            .setFontVariationSettings("'wght' " + weight).build();
                    return new Typeface.CustomFallbackBuilder(new android.graphics.fonts.FontFamily.Builder(font).build()).build();
                } catch (Throwable t) { L("font " + f + ": " + t); }
            }
            return null;
        }
        static Paint p(int c) { Paint x = new Paint(Paint.ANTI_ALIAS_FLAG); x.setColor(c); return x; }

        void draw(Canvas c, NavState.Snap s) {
            if (s.instruction.isEmpty()) return;
            // turn card, top centre (inside the dome's narrow top)
            String big = !s.turnDist.isEmpty() ? s.turnDist : shorten(s.instruction, 18);
            String small = !s.turnDist.isEmpty() ? "onto " + s.street : (s.street.equals(s.instruction) ? "" : s.street);
            white.setTypeface(bold); white.setTextSize(26); float wBig = white.measureText(big);
            white.setTypeface(med); white.setTextSize(13); float wSmall = white.measureText(small);
            float w = 10 + 38 + 10 + Math.max(wBig, wSmall) + 16, h = 52, x = (OW - w) / 2f, y = 26;
            c.drawRoundRect(new RectF(x, y, x + w, y + h), 16, 16, card);
            arrow(c, s.kind, x + 10, y + 7, 38);
            white.setTypeface(bold); white.setTextSize(26); c.drawText(big, x + 58, y + (small.isEmpty() ? 35 : 27), white);
            if (!small.isEmpty()) { white.setTypeface(med); white.setTextSize(13); c.drawText(small, x + 58, y + 44, white); }

            // ETA chip, bottom left: time left · km left · arrival
            String mins = minutesLeft(s.arrive), km = s.leftM >= 0 ? String.format(Locale.US, s.leftM >= 10000 ? "%.0f km" : "%.1f km", s.leftM / 1000.0) : "";
            mint.setTypeface(bold); mint.setTextSize(21); white.setTypeface(bold); white.setTextSize(14);
            float cx = 30, cy = OH - 12 - 34, pad = 10, gap = 10;
            float cw = pad + mint.measureText(mins) + gap + white.measureText(km) + gap + white.measureText(s.arrive) + pad;
            c.drawRoundRect(new RectF(cx, cy, cx + cw, cy + 34), 12, 12, chip);
            float tx = cx + pad; c.drawText(mins, tx, cy + 24, mint); tx += mint.measureText(mins) + gap;
            c.drawText(km, tx, cy + 23, white); tx += white.measureText(km) + gap;
            white.setAlpha(190); c.drawText(s.arrive, tx, cy + 23, white); white.setAlpha(255);
        }

        static String shorten(String t, int n) { return t.length() <= n ? t : t.substring(0, n - 1) + "…"; }

        static String minutesLeft(String arrive) {
            try {
                Date a = new SimpleDateFormat("h:mm a", Locale.US).parse(arrive.toUpperCase(Locale.US));
                Calendar now = Calendar.getInstance(), at = Calendar.getInstance(); at.setTime(a);
                at.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
                if (at.before(now)) at.add(Calendar.DAY_OF_MONTH, 1);
                long m = Math.round((at.getTimeInMillis() - now.getTimeInMillis()) / 60000.0);
                return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
            } catch (Exception e) { return ""; }
        }

        void arrow(Canvas c, String kind, float x, float y, float s) {
            stroke.setStrokeWidth(s * 0.15f);
            Path p = new Path(); float k = s / 48f;
            switch (kind) {
                case "right": case "slight_right":
                    p.moveTo(14 * k, 40 * k); p.lineTo(14 * k, 22 * k); p.quadTo(14 * k, 16 * k, 20 * k, 16 * k); p.lineTo(36 * k, 16 * k);
                    p.moveTo(30 * k, 8 * k); p.lineTo(38 * k, 16 * k); p.lineTo(30 * k, 24 * k); break;
                case "left": case "slight_left":
                    p.moveTo(34 * k, 40 * k); p.lineTo(34 * k, 22 * k); p.quadTo(34 * k, 16 * k, 28 * k, 16 * k); p.lineTo(12 * k, 16 * k);
                    p.moveTo(18 * k, 8 * k); p.lineTo(10 * k, 16 * k); p.lineTo(18 * k, 24 * k); break;
                case "uturn":
                    p.moveTo(30 * k, 40 * k); p.lineTo(30 * k, 18 * k); p.quadTo(30 * k, 8 * k, 21 * k, 8 * k); p.quadTo(12 * k, 8 * k, 12 * k, 18 * k); p.lineTo(12 * k, 34 * k);
                    p.moveTo(6 * k, 28 * k); p.lineTo(12 * k, 36 * k); p.lineTo(18 * k, 28 * k); break;
                default:
                    p.moveTo(24 * k, 42 * k); p.lineTo(24 * k, 8 * k); p.moveTo(14 * k, 18 * k); p.lineTo(24 * k, 8 * k); p.lineTo(34 * k, 18 * k);
            }
            c.save(); c.translate(x, y); c.drawPath(p, stroke); c.restore();
        }
    }

    // ---------------- H.264 encoder (dash format) ----------------
    static final class Encoder {
        final MediaCodec codec; final Surface surface; final OutputStream out; long bytes;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Encoder(String path) throws IOException {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OW, OH);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            out = new BufferedOutputStream(new FileOutputStream(path));
        }
        void drain(boolean eos) throws IOException {
            while (true) {
                int i = codec.dequeueOutputBuffer(info, eos ? 10_000 : 0);
                if (i < 0) { if (!eos || i == MediaCodec.INFO_TRY_AGAIN_LATER) return; continue; }
                ByteBuffer b = codec.getOutputBuffer(i);
                byte[] data = new byte[info.size]; b.position(info.offset); b.get(data);
                out.write(data); bytes += data.length;
                codec.releaseOutputBuffer(i, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
        void finish() throws IOException { codec.signalEndOfInputStream(); drain(true); codec.stop(); codec.release(); out.close(); }
    }

    // ---------------- phone panel on/off (device stays awake) ----------------
    static final class Panel {
        Method setMode; Object token;
        Panel() {
            try {
                Class<?> sc = Class.forName("android.view.SurfaceControl");
                setMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
                // Android 14+: physical display tokens live in services.jar (DisplayControl)
                Class<?> clf = Class.forName("com.android.internal.os.ClassLoaderFactory");
                Method create = clf.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                        ClassLoader.class, int.class, boolean.class, String.class);
                ClassLoader cl = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null,
                        ClassLoader.getSystemClassLoader(), 0, true, null);
                Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
                Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                load.setAccessible(true); load.invoke(Runtime.getRuntime(), dc, "android_servers");
                long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
                token = dc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
                L("panel control ready (" + ids.length + " physical display(s))");
            } catch (Throwable t) { L("panel control unavailable: " + t); }
        }
        void off() { set(0); }   // POWER_MODE_OFF
        void on() { set(2); }    // POWER_MODE_NORMAL
        void set(int mode) { try { if (token != null) setMode.invoke(null, token, mode); L("panel mode " + mode); } catch (Throwable t) { L("panel: " + t); } }
    }

    // ---------------- privileged-process plumbing ----------------
    /** A Context good enough for system services, in a bare app_process (no app installed). */
    static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor(); ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        Field cur = at.getDeclaredField("sCurrentActivityThread"); cur.setAccessible(true); cur.set(null, thread);
        Context sys = (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(sys) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
        };
    }

    static DisplayManager newDisplayManager(Context ctx) throws Exception {
        Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(ctx);
    }

    static void copy(Image img, Bitmap dst) {
        Image.Plane pl = img.getPlanes()[0];
        int rowPx = pl.getRowStride() / pl.getPixelStride();
        if (rowPx == VW) { dst.copyPixelsFromBuffer(pl.getBuffer()); return; }
        Bitmap wide = Bitmap.createBitmap(rowPx, VH, Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(pl.getBuffer());
        new Canvas(dst).drawBitmap(wide, 0, 0, null); wide.recycle();
    }

    static void snapshot(Bitmap full, Overlay ov, NavState.Snap s, long frame) {
        try {
            Bitmap b = Bitmap.createBitmap(OW, OH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawBitmap(full, CROP, new Rect(0, 0, OW, OH), new Paint(Paint.FILTER_BITMAP_FLAG));
            ov.draw(c, s);
            try (FileOutputStream o = new FileOutputStream(OUT + "/snap-" + frame + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            try (FileOutputStream o = new FileOutputStream(OUT + "/full-" + frame + ".png")) { full.compress(Bitmap.CompressFormat.PNG, 100, o); }
            L("snapshot " + frame + " nav='" + s.instruction + "' arrive=" + s.arrive + " left=" + s.leftM + "m kind=" + s.kind);
        } catch (Exception e) { L("snapshot: " + e); }
    }

    static void sh(String cmd) { try { Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}).waitFor(); } catch (Exception e) { L("sh: " + e); } }
    static String shOut(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); InputStream in = p.getInputStream();
        byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        p.waitFor(); return bo.toString("UTF-8");
    }
    static void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    static void L(String s) { String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s; log.println(line); System.out.println(line); }
}
