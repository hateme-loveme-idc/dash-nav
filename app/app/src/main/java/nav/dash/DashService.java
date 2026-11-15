package nav.dash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import nav.dash.link.DashLink;
import nav.dash.link.DashProtocol;
import nav.dash.link.VideoRtp;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * The engine.
 *
 *  Idle  (Google Maps not navigating): Maps stays on the phone; the dash shows the wallpaper or a clock screen.
 *  Ride  (navigation running): Maps moves to a hidden display; its map, cropped around the rider arrow, plus our
 *        turn card and ETA chip go to the dash as H.264. Starting navigation in Maps at any time switches the
 *        dash to the map by itself; when navigation ends Maps comes back to the phone.
 *  Data  (once a second, from the Shizuku helper): Maps' directions, the call state + caller, now playing.
 *
 * Phone screen: dark while the map is on the dash. The power button brings it back at once (and dark again on
 * the next press); it also goes dark by itself after 30 s without a touch. Calls light it up.
 *
 *   am start-foreground-service -n nav.dash/.DashService [--es dest "<place or lat,lng>"] [--ei seconds N]
 *   am start-foreground-service -n nav.dash/.DashService --ez stop true
 */
public class DashService extends Service {
    static final String TAG = "DashNav";

    // Hidden display. Maps' landscape navigation layout is proportional: its panel takes the left ~44.5 %, its
    // buttons a narrow right column, and the rider arrow sits at 72.25 % across, 93 % down (measured on a test phone).
    // At 1600x760 @ 140 dpi the 520x297 window around the arrow is clear of both and maps ~1:1 onto 526x300.
    static final int VW = 1600, VH = 760, VDPI = 140;
    static final float ARROW_X = 0.7225f, ARROW_Y = 0.93f;
    static final int CW = 520, CH = 297;
    static final Rect CROP;
    static {
        int left = Math.round(VW * ARROW_X - CW / 2f), top = Math.round(VH * ARROW_Y - CH * 0.85f);
        left = Math.max(0, Math.min(VW - CW, left)); top = Math.max(0, Math.min(VH - CH, top));
        CROP = new Rect(left, top, left + CW, top + CH);
    }
    static final int OW = Overlay.W, OH = Overlay.H;
    /** Frame rate adapts to what the dash confirms it decoded: start here, climb while it keeps up. */
    static final int FPS_MIN = 4, FPS_START = 6, FPS_MAX = 12;
    static final String MAPS = NavState.MAPS;

    static volatile String status = "Ready", linkStatus = "", detail = "", lastJoystick = "";
    static volatile boolean active;
    private volatile boolean running, panelOff, riderDark, mapsOnDash, movingMaps;
    private volatile int callPhase, fps = FPS_START, displayId = -1;   // callPhase: 0 none, 1 ringing, 2 in a call
    private volatile long callStart, navLostAt, lastPowerAt, arrivedAt;
    private final long[] perf = new long[4];
    private volatile String caller = "";
    private volatile DashProtocol.Media music;
    private Thread feeder;
    private String wifiPrefix;                 // debug: test the Wi-Fi prompt against another network
    private volatile long fakeRingUntil, fakeCallUntil;
    private BroadcastReceiver screenOff;
    private DisplayManager.DisplayListener phoneDisplay;
    private IDashHelper helper;
    private DashLink link;
    private Encoder enc;
    private PowerManager.WakeLock wake;
    private String dest; private int seconds;
    private Settings settings;

    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, DashHelper.class.getName()))
            .daemon(false).processNameSuffix("helper").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            helper = IDashHelper.Stub.asInterface(b);
            try { helper.setClient(new Binder()); } catch (RemoteException e) { Log.e(TAG, "setClient", e); }
            new Thread(DashService.this::run, "dash-engine").start();
        }
        @Override public void onServiceDisconnected(ComponentName n) { helper = null; if (running) { running = false; set("Helper stopped"); } }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    /** If Android tears the service down without a normal stop, still close the video and the log. */
    @Override public void onDestroy() {
        try { Encoder e = enc; if (e != null && e.current() != null) e.current().finish(); } catch (Exception ignored) {}
        RideLog.stop();
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int fgs = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fgs |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;   // scan for the dash Wi-Fi + GPS with the screen off
        startForeground(1, note("Starting…"), fgs);
        if (intent != null && intent.getBooleanExtra("stop", false)) { stopAll(); return START_NOT_STICKY; }
        if (BuildConfig.DEBUG && intent != null && intent.hasExtra("fake_call")) { fakeCall(intent.getStringExtra("fake_call")); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        settings = new Settings(this);
        if (BuildConfig.DEBUG && intent != null) {          // test hooks (debug builds only)
            if (intent.hasExtra("test_mode")) settings.testMode(intent.getBooleanExtra("test_mode", false));
            if (intent.hasExtra("record")) settings.record(intent.getBooleanExtra("record", false));
            if (intent.hasExtra("dash_turn_circle")) settings.dashTurnCircle(intent.getBooleanExtra("dash_turn_circle", false));
            wifiPrefix = intent.getStringExtra("wifi_prefix");
            if (intent.hasExtra("wallpaper")) settings.wallpaper(intent.getBooleanExtra("wallpaper", false));
        }
        dest = intent == null ? null : intent.getStringExtra("dest");
        if (dest != null && dest.trim().isEmpty()) dest = null;
        seconds = intent == null ? 0 : intent.getIntExtra("seconds", 0);
        if (!Shizuku.pingBinder()) { fail("Shizuku isn't running: open Shizuku and tap Start"); return START_NOT_STICKY; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { fail("Allow Dash Nav in Shizuku first"); return START_NOT_STICKY; }
        running = true; active = true; panelOff = false; riderDark = false; mapsOnDash = false; fps = FPS_START;
        linkStatus = ""; detail = ""; set("Starting…");
        // The phone must stay awake for Maps to keep drawing (a hidden display sleeps with the phone).
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "dashnav:ride");
        wake.acquire(12 * 60 * 60 * 1000L);
        RideLog.start(this);
        RideLog.add("start · test mode " + settings.testMode() + " · destination " + (dest == null ? "none (follow Google Maps)" : "typed"));
        Shizuku.bindUserService(args, conn);
        watchPowerButton();
        startGps();
        return START_NOT_STICKY;
    }

    // ================================ engine ================================
    private void run() {
        // GPU-sampled buffers: each frame is drawn straight from Maps' buffer into the encoder (no CPU copy).
        ImageReader reader = ImageReader.newInstance(VW, VH, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        long lastSnap = 0, lastAdapt = 0, start = SystemClock.elapsedRealtime(), sentAtAdapt = 0, ackedAtAdapt = 0;
        long videoAt = SystemClock.elapsedRealtime();
        long lastStat = 0, sentAtStat = 0, bytesAtStat = 0, keysAtStat = 0, ackFAtStat = 0, ackKAtStat = 0, lastBlackCheck = 0;
        RideVideo video = null;
        Image held = null;
        try {
            displayId = helper.createDisplay(reader.getSurface(), VW, VH, VDPI);
            if (displayId < 0) { set("Couldn't create the hidden display"); return; }
            feedOnce();                              // fresh Maps state before deciding take-over vs idle
            startMaps();
            feeder = new Thread(this::feedLoop, "dash-feed"); feeder.start();

            final VideoRtp v = new VideoRtp(p -> { DashLink l = link; if (l != null) l.sendRtp(p); });
            if (settings.record()) video = new RideVideo(this);
            enc = new Encoder(v, video);
            DashLink.Config lc = settings.linkConfig();
            if (BuildConfig.DEBUG && wifiPrefix != null) lc.ssidPrefix = wifiPrefix;
            link = new DashLink(this, lc, this::navFields, new DashLink.Listener() {
                @Override public void onState(DashLink.State s, String d) {
                    linkStatus = d; RideLog.add("dash link: " + s + " · " + d);
                    if (running) set(s == DashLink.State.STREAMING ? (mapsOnDash ? "On the dash" : "On the dash (waiting for navigation)") : "Waiting for the dash");
                    if (s == DashLink.State.STREAMING) {
                        v.waitForKeyframe();               // start the dash on a clean keyframe…
                        if (enc != null) enc.keyframe();   // …requested right now
                    }
                    updatePhoneScreen();
                }
                @Override public void onButton(int code) { button(code); }
                @Override public void onDashLearned(String ssid, String bssid) { settings.dash(ssid, bssid); }
                @Override public void onUserCancelled() { set("Cancelled"); running = false; }
            });
            link.start();

            Overlay overlay = new Overlay();
            Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect dst = new Rect(0, 0, OW, OH);
            Bitmap frame = null;
            while (running) {
                long t0 = SystemClock.elapsedRealtime();
                if (seconds > 0 && t0 - start > seconds * 1000L) break;
                long tA = SystemClock.elapsedRealtimeNanos();
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    HardwareBuffer hb = img.getHardwareBuffer();
                    Bitmap b = hb == null ? null : Bitmap.wrapHardwareBuffer(hb, null);
                    if (hb != null) hb.close();
                    if (b != null) { if (held != null) held.close(); held = img; frame = b; } else img.close();
                }
                NavState.Nav nav = NavState.current;
                if (menuOpen && t0 - menuAt > 10_000) menuOpen = false;
                long tB = SystemClock.elapsedRealtimeNanos();
                Canvas c = enc.surface.lockHardwareCanvas();
                long tC = SystemClock.elapsedRealtimeNanos();
                try { paintFrame(c, frame, nav, overlay, filter, dst); }
                finally { enc.surface.unlockCanvasAndPost(c); }
                long tD = SystemClock.elapsedRealtimeNanos();
                perf[0] += tB - tA; perf[1] += tC - tB; perf[2] += tD - tC; perf[3]++;
                if (perf[3] == 24) { Log.i(TAG, String.format(Locale.US, "perf ms/frame: acquire %.1f lock %.1f draw+post %.1f · target %d fps", perf[0] / 24e6, perf[1] / 24e6, perf[2] / 24e6, fps)); perf[0] = perf[1] = perf[2] = perf[3] = 0; }
                if (MainActivity.visible && t0 - lastSnap > 3_000) { snapshot(frame, overlay, nav); lastSnap = t0; }
                if (video != null && t0 - videoAt > (BuildConfig.DEBUG ? 60_000 : 5 * 60_000)) { enc.rotate(new RideVideo(this)); videoAt = t0; }
                if (mapsOnDash && t0 - lastBlackCheck >= 2_000) { lastBlackCheck = t0; checkMapsDrawing(frame, t0); }
                DashLink l = link;
                if (l != null && l.streaming() && t0 - lastAdapt > 3_000) {
                    // 1.2.0's control (proven on the real dash): the encoder makes its own keyframe every 12 frames
                    long sent = v.frames, acked = l.acksKey + l.acksFrame;
                    long ds = sent - sentAtAdapt, da = acked - ackedAtAdapt;
                    if (lastAdapt > 0 && ds > 0 && acked > 0) {
                        double ratio = (double) da / ds; int before = fps;
                        if (ratio >= 0.92 && fps < FPS_MAX) fps++;
                        else if (ratio < 0.75 && fps > FPS_MIN) fps = Math.max(FPS_MIN, fps - 2);
                        if (fps != before) enc.bitrate(fps);
                        detail = String.format(Locale.US, "%d fps to the dash · it showed %d of the last %d frames", fps, da, ds);
                    }
                    if (lastAdapt > 0 && t0 - lastStat >= 30_000) {
                        double secs = (t0 - lastStat) / 1000.0;
                        RideLog.add(String.format(Locale.US, "stream: %d fps · sent %.1f/s · %.0f kbps · keyframes %d · dash acks: frame %d, keyframe %d · maps on dash %s · phone %.1f °C",
                                fps, (sent - sentAtStat) / secs, (v.bytes - bytesAtStat) * 8 / secs / 1000, v.keyframes - keysAtStat,
                                l.acksFrame - ackFAtStat, l.acksKey - ackKAtStat, mapsOnDash ? "yes" : "no", batteryTemp()));
                        String msgs = l.messageSummary(); if (!msgs.isEmpty()) RideLog.add("dash sent: " + msgs);
                        sentAtStat = sent; bytesAtStat = v.bytes; keysAtStat = v.keyframes; ackFAtStat = l.acksFrame; ackKAtStat = l.acksKey; lastStat = t0;
                    } else if (lastStat == 0) { lastStat = t0; sentAtStat = sent; bytesAtStat = v.bytes; keysAtStat = v.keyframes; ackFAtStat = l.acksFrame; ackKAtStat = l.acksKey; }
                    sentAtAdapt = sent; ackedAtAdapt = acked; lastAdapt = t0;
                }
                SystemClock.sleep(Math.max(0, 1000 / fps - (SystemClock.elapsedRealtime() - t0)));
            }
            set("Stopped");
        } catch (Throwable t) {
            Log.e(TAG, "engine", t); set("Error: " + t.getMessage());
        } finally {
            try { if (feeder != null) feeder.interrupt(); } catch (Exception ignored) {}
            try { if (link != null) link.stop(); } catch (Exception ignored) {}
            try { if (enc != null) enc.release(); } catch (Exception ignored) {}
            try { if (video != null && enc != null && enc.current() != null) enc.current().finish(); else if (video != null) video.finish(); } catch (Exception ignored) {}
            RideLog.add("stop · " + status); RideLog.stop();
            try { if (helper != null) helper.release(); } catch (Exception ignored) {}
            if (held != null) held.close();
            reader.close();
            running = false;
            new Handler(Looper.getMainLooper()).post(this::stopAll);
        }
    }

    // ================================ Google Maps: idle / take over / give back ================================
    /**
     * At start: a typed destination starts a route on the hidden display; a running navigation is taken over;
     * otherwise Maps stays on the phone (idle) and is taken over the moment navigation starts.
     * (A new destination while Maps is navigating would raise Maps' "Exit navigation?" dialog on a display
     * nobody can touch, so Maps is restarted first.)
     */
    private void startMaps() throws RemoteException {
        if (dest == null) {
            if (NavState.current.active()) takeOver();
            else set("Waiting for navigation: start it in Google Maps");
            return;
        }
        String[] task = helper.mapsTask().trim().split(" ");
        if (NavState.current.active() || task.length == 2) {
            helper.stopMaps();
            for (int i = 0; i < 30 && NavState.current.active(); i++) SystemClock.sleep(100);
            NavState.reset();
        }
        set("Opening Google Maps…");
        helper.startOnDisplay(displayId, "-n " + MAPS + "/com.google.android.maps.MapsActivity");
        mapsOnDash = true;
        for (int i = 0; i < 40 && helper.mapsTask().trim().split(" ").length != 2; i++) SystemClock.sleep(250);
        SystemClock.sleep(4000);   // let Maps finish its first draw before handing it a route
        String uri = "'google.navigation:q=" + Uri.encode(dest.replace("'", "")) + "&mode=l'";
        for (int attempt = 0; attempt < 2 && running; attempt++) {
            set("Navigating to " + dest);
            helper.startOnDisplay(displayId, "-a android.intent.action.VIEW -d " + uri + " " + MAPS);
            for (int i = 0; i < 80 && !NavState.current.active() && running; i++) { SystemClock.sleep(250); if (i % 4 == 3) feedOnce(); }
            if (NavState.current.active()) return;
            Log.w(TAG, "navigation didn't start, retrying");
        }
        set("Google Maps didn't start navigation: start it in Maps");
    }

    private volatile long blackSince; private volatile int blackFixes;

    /** Maps on the dash but drawing nothing (seen on a real ride: black map behind the turn card): log it and bring Maps
     *  to the front of the hidden display, which makes it draw again. At most 3 nudges per takeover. */
    private void checkMapsDrawing(Bitmap frame, long now) {
        boolean black = true;
        if (frame != null) {
            try {
                Bitmap soft = frame.copy(Bitmap.Config.ARGB_8888, false);
                int w = soft.getWidth(), h = soft.getHeight(), dark = 0, n = 0;
                for (int y = 1; y < 8; y++) for (int x = 1; x < 12; x++) {
                    int c = soft.getPixel(x * w / 12, y * h / 8); n++;
                    if (((c >> 16 & 0xFF) * 3 + (c >> 8 & 0xFF) * 6 + (c & 0xFF)) / 10 < 10) dark++;
                }
                soft.recycle(); black = dark >= n * 0.97;
            } catch (Exception e) { return; }
        }
        if (!black) { if (blackSince > 0) RideLog.add("maps: drawing again"); blackSince = 0; return; }
        if (blackSince == 0) { blackSince = now; RideLog.add("maps: on the dash but " + (frame == null ? "no picture yet" : "black")); return; }
        if (now - blackSince >= 5_000 && blackFixes < 3) {
            blackFixes++; blackSince = now;
            RideLog.add("maps: still black: bringing it to the front (" + blackFixes + "/3)");
            IDashHelper hp = helper; if (hp != null) resumeMaps(hp);
        }
    }

    /** Bring the existing Maps task to the front of the dash display, as a launcher tap would (no new map, route kept). */
    private void resumeMaps(IDashHelper hp) {
        try { hp.startOnDisplay(displayId, "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n " + MAPS + "/com.google.android.maps.MapsActivity"); }
        catch (Exception e) { Log.w(TAG, "resume maps", e); }
    }

    private float batteryTemp() {
        android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        return b == null ? 0 : b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
    }

    /** Navigation is running on the phone: move Maps (as it is: route, stops, voice) onto the hidden display. */
    private void takeOver() {
        IDashHelper hp = helper; if (hp == null || mapsOnDash || movingMaps) return;
        movingMaps = true;
        try {
            String[] task = hp.mapsTask().trim().split(" ");
            if (task.length != 2) return;
            // Maps that went to the background (Home, another app) before the move draws only black on the hidden
            // display (reproduced on the test phone). Bring it to the front on the phone first, like opening it, so it
            // is fully running and drawing; then move it. If it's already in front this changes nothing.
            if (Integer.parseInt(task[1]) == 0) {
                hp.startOnDisplay(0, "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n " + MAPS + "/com.google.android.maps.MapsActivity");
                SystemClock.sleep(1200);
                task = hp.mapsTask().trim().split(" ");
                if (task.length != 2) return;
            }
            hp.moveTask(Integer.parseInt(task[0]), displayId);
            mapsOnDash = true; navLostAt = 0; blackSince = 0; blackFixes = 0;
            RideLog.add("maps: moved to the dash (task " + task[0] + ")");
            // a task that was paused when it moved (navigation started while the dash was already running) can stay
            // paused on the hidden display and draw nothing: resume it, like tapping Maps' icon (keeps the route)
            SystemClock.sleep(400); resumeMaps(hp);
            set("On the dash");
            Encoder e = enc; if (e != null) e.keyframe();
            updatePhoneScreen();
        } catch (Exception e) { Log.w(TAG, "takeOver", e); }
        finally { movingMaps = false; }
    }

    /** Navigation ended (arrived / exited): Maps back to the phone, dash back to the idle screen. */
    private void giveBack() {
        IDashHelper hp = helper; if (hp == null || !mapsOnDash || movingMaps) return;
        movingMaps = true;
        try {
            String[] task = hp.mapsTask().trim().split(" ");
            if (task.length == 2 && Integer.parseInt(task[1]) == displayId) hp.moveTask(Integer.parseInt(task[0]), 0);
            mapsOnDash = false; dest = null;
            RideLog.add("maps: back on the phone (navigation ended)");
            set("Waiting for navigation: start it in Google Maps");
            updatePhoneScreen();
        } catch (Exception e) { Log.w(TAG, "giveBack", e); }
        finally { movingMaps = false; }
    }

    /** What the dash's own gold band shows: street, distance left, ETA. */
    private DashProtocol.NavFields navFields() {
        NavState.Nav n = NavState.current;
        DashProtocol.NavFields f = new DashProtocol.NavFields();
        if (!n.active() || !mapsOnDash) { f.title = "Dash Nav"; f.etaHHMM = new SimpleDateFormat("HHmm", Locale.US).format(new Date()); return f; }
        f.title = !n.street.isEmpty() ? n.street : NavText.clean(n.instruction);
        if (n.leftM >= 0) { f.totalDist = (int) Math.min(0xFFFF, Math.round(n.leftM / 100.0)); f.totalUnit = DashProtocol.UNIT_KM_TENTHS; }
        f.etaHHMM = NavText.hhmm(n.arrive);
        if (f.etaHHMM == null) f.etaHHMM = new SimpleDateFormat("HHmm", Locale.US).format(new Date());
        f.glyph = NavState.dashGlyph(n.kind);
        f.turnDist = n.turnMeters; f.turnUnit = DashProtocol.UNIT_METERS;
        return f;
    }

    // ================================ the dash picture ================================
    private Bitmap wallpaper; private long wallpaperStamp = -1;

    private void paintFrame(Canvas c, Bitmap frame, NavState.Nav nav, Overlay overlay, Paint filter, Rect dst) {
        c.drawColor(Color.BLACK);
        if (mapsOnDash && nav.active() && frame != null) { c.drawBitmap(frame, CROP, dst, filter); overlay.draw(c, nav); }
        else overlay.drawIdle(c, wallpaper(), clock(), mapsOnDash ? "Navigation ended" : "Start navigation in Google Maps");
        // calls show on the dash's own band (pushBand), never over the map: navigation stays fully visible
        overlay.drawUi(c, menuOpen ? MENU : null, menuSel, badgeText(), hintText());
    }

    static String clock() { return new SimpleDateFormat("h:mm a", Locale.US).format(new Date()); }

    private Bitmap wallpaper() {
        if (!settings.wallpaper()) return null;
        File f = new File(getFilesDir(), "wallpaper.png");
        if (!f.exists()) return null;
        if (f.lastModified() != wallpaperStamp) { wallpaper = BitmapFactory.decodeFile(f.getPath()); wallpaperStamp = f.lastModified(); }
        return wallpaper;
    }

    // ================================ joystick ================================
    /*
     * The dash sends joystick codes while it shows our picture (its own music player handles itself and sends
     * next/previous). What Dash Nav does with them:
     *   Down, Down (within 2.5 s) .. menu: Play/pause · Next track · Previous track · Phone screen on/off · Close
     *   in the menu ............... Down = next row, Right = select, Left = close (closes itself after 10 s)
     *   ringing ................... Up = answer, Down = reject      in a call: Down = hang up
     *   music player (dash) ....... Right / Left = next / previous track
     */
    static final String[] MENU = {"Play / pause", "Next track", "Previous track", "Phone screen on / off", "Close"};
    static final int UP = 0x06, CALL_DOWN = 0x07, NEXT = 0x09, PREV = 0x0A, RIGHT = 0x13, LEFT = 0x14, DOWN = 0x15;
    private volatile boolean menuOpen; private volatile int menuSel; private volatile long menuAt, lastDownAt, badgeUntil, hintUntil;
    private volatile String badge = "";
    private int lastDir = -1; private long lastDirAt;

    private String badgeText() { return SystemClock.elapsedRealtime() < badgeUntil ? badge : null; }
    private String hintText() { return SystemClock.elapsedRealtime() < hintUntil ? "▼ again: menu" : null; }
    private void badge(String b) { badge = b; badgeUntil = SystemClock.elapsedRealtime() + 1500; }

    static String joyName(int code) {
        switch (code) {
            case UP: return "UP"; case CALL_DOWN: return "DOWN (call)"; case NEXT: return "RIGHT (music: next)";
            case PREV: return "LEFT (music: previous)"; case RIGHT: return "RIGHT"; case LEFT: return "LEFT"; case DOWN: return "DOWN";
            case 0x22: return "compass opened";
            default: return String.format(Locale.US, "code 0x%02X", code);
        }
    }

    /** The bike's stick sometimes adds a different direction right after the real one: drop it. */
    private boolean ghost(int code) {
        long now = SystemClock.elapsedRealtime();
        boolean isDir = code == RIGHT || code == LEFT || code == DOWN;
        if (!isDir) return false;
        if (lastDir != -1 && code != lastDir && now - lastDirAt < settings.ghostMs()) { Log.i(TAG, "joystick " + joyName(code) + " ignored (ghost)"); return true; }
        lastDir = code; lastDirAt = now;
        return false;
    }

    private void button(int code) {
        lastJoystick = joyName(code) + " · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        RideLog.add(String.format(Locale.US, "joystick: 0x%02X %s · menu %s · call %d", code, joyName(code), menuOpen ? "open" : "closed", callPhase));
        IDashHelper hp = helper; if (hp == null) return;
        if (ghost(code)) return;
        long now = SystemClock.elapsedRealtime();
        try {
            if (callPhase == 1 && code == UP) { hp.key(79); badge("Answered"); }                     // HEADSETHOOK
            else if (callPhase != 0 && (code == CALL_DOWN || (callPhase == 1 && code == DOWN))) { hp.key(6); badge(callPhase == 1 ? "Rejected" : "Call ended"); }
            else if (code == NEXT) { hp.mediaAction(1); badge("Next track"); }
            else if (code == PREV) { hp.mediaAction(2); badge("Previous track"); }
            else if (menuOpen) {
                menuAt = now;
                if (code == DOWN) menuSel = (menuSel + 1) % MENU.length;
                else if (code == RIGHT) menuAction(menuSel);
                else if (code == LEFT) menuOpen = false;
            } else if (code == DOWN) {
                if (now - lastDownAt < 2500) { menuOpen = true; menuSel = 0; menuAt = now; hintUntil = 0; lastDownAt = 0; }
                else { lastDownAt = now; hintUntil = now + 2500; }
            }
            if (!menuOpen && badgeText() == null && hintText() == null) badge("Joystick: " + joyName(code));   // so the rider sees it arrive
        } catch (RemoteException ignored) {}
    }

    private void menuAction(int i) {
        IDashHelper hp = helper; menuOpen = false;
        try {
            switch (i) {
                case 0: if (hp != null) hp.mediaAction(3); badge("Play / pause"); break;
                case 1: if (hp != null) hp.mediaAction(1); badge("Next track"); break;
                case 2: if (hp != null) hp.mediaAction(2); badge("Previous track"); break;
                case 3: riderDark = !panelOff; updatePhoneScreen(); badge(riderDark ? "Phone screen off" : "Phone screen on"); break;
                default: break;
            }
        } catch (RemoteException ignored) {}
    }

    // ================================ live data (once a second) ================================
    private void feedLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            long t = SystemClock.elapsedRealtime();
            feedOnce();
            SystemClock.sleep(Math.max(100, 1000 - (SystemClock.elapsedRealtime() - t)));
        }
    }

    private void feedOnce() {
        IDashHelper hp = helper; if (hp == null) return;
        Bundle b;
        try { b = hp.feed(); } catch (Exception e) { Log.w(TAG, "feed", e); return; }
        NavState.update(b);
        long now = SystemClock.elapsedRealtime();
        // Maps keeps showing "Arriving" after you reach the destination: 45 s of that counts as finished
        boolean arriving = NavState.current.active() && "arrive".equals(NavState.current.kind);
        if (arriving) { if (arrivedAt == 0) arrivedAt = now; } else arrivedAt = 0;
        boolean nav = NavState.current.active() && !(arriving && now - arrivedAt > 45_000);
        // follow Google Maps: navigation started -> to the dash; ended for 20 s -> back to the phone
        if (displayId >= 0 && running && feeder != null) {
            if (nav && !mapsOnDash && !movingMaps) new Thread(this::takeOver, "dash-takeover").start();
            if (!nav && mapsOnDash && !movingMaps) {
                if (navLostAt == 0) navLostAt = now;
                else if (now - navLostAt > 20_000) new Thread(this::giveBack, "dash-giveback").start();
            } else if (nav) navLostAt = 0;
        }
        call(b);
        String pkg = b.getString("mediaPkg");
        music = pkg == null ? null : new DashProtocol.Media(b.getString("song", ""), b.getString("album", ""), b.getString("artist", ""), b.getBoolean("playing"));
        pushBand();
        DashLink l = link;
        if (l != null) l.setGps(now - lastFix < 5000, heading);
    }

    /**
     * The dash's music widget (on its gold band): the song on the left and the time (12:10 PM) on the right
     * instead of the artist; during a call, who's calling and then the call duration. Back to music after.
     */
    private void pushBand() {
        DashLink l = link; if (l == null) return;
        String time = clock().toUpperCase(Locale.US);
        if (callPhase != 0) {
            String what = callPhase == 1 ? "Incoming call" : "Call " + duration();
            l.setMedia(new DashProtocol.Media(callerLabel(), what, what, true));
            l.setCaller(callPhase == 1 ? callerLabel() : null);
        } else {
            l.setCaller(null);
            DashProtocol.Media m = music;
            l.setMedia(m == null ? null : new DashProtocol.Media(m.title, time, time, m.playing));
        }
    }

    static boolean looksLikeNumber(String s) { return s != null && s.replaceAll("[\\s()+-]", "").matches("\\d{5,}"); }

    /** The contact name for a number, if the rider allowed Contacts (else null: the number is shown). */
    private final java.util.Map<String, String> names = new java.util.HashMap<>();
    private String contactName(String number) {
        if (names.containsKey(number)) return names.get(number);
        String name = null;
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Uri u = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception e) { Log.w(TAG, "contact lookup", e); }
        }
        names.put(number, name);
        return name;
    }

    private String callerLabel() { return caller.isEmpty() ? "Unknown caller" : caller; }
    private String duration() {
        long s = Math.max(0, (System.currentTimeMillis() - callStart) / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Calls: who is calling (contact name from the dialer/WhatsApp notification, else the number), duration. */
    private void call(Bundle b) {
        int st = b.getInt("callState");
        long now = System.currentTimeMillis();
        if (now < fakeRingUntil) st = 1; else if (now < fakeCallUntil) st = 2;
        int mode = getSystemService(android.media.AudioManager.class).getMode();   // also covers WhatsApp etc.
        int phase = (st == 1 || mode == android.media.AudioManager.MODE_RINGTONE) ? 1
                : (st == 2 || mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION) ? 2 : 0;
        String who = b.getString("caller", "");
        if (who.isEmpty()) who = b.getString("number", "");
        if (looksLikeNumber(who)) { String name = contactName(who); if (name != null) who = name; }   // saved contact: its name
        if (!who.isEmpty()) caller = who;
        if (phase == 2 && callPhase != 2) callStart = now;
        if (phase == 0) { caller = ""; names.clear(); }
        boolean changed = phase != callPhase;
        if (changed) RideLog.add("call: " + (phase == 0 ? "ended" : phase == 1 ? "ringing" : "answered")
                + " · dialer notification name: " + (b.getString("caller", "").isEmpty() ? "none" : looksLikeNumber(b.getString("caller", "")) ? "a number" : "yes")
                + " · number from telephony: " + (b.getString("number", "").isEmpty() ? "none" : "yes")
                + " · contacts permission: " + (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                + " · shown: " + (caller.isEmpty() ? "Unknown caller" : looksLikeNumber(caller) ? "a number" : "a name"));
        callPhase = phase;
        if (changed) updatePhoneScreen();
    }

    // ================================ phone screen ================================
    /*
     * Dash Nav never turns the phone screen off by itself. When the RIDER presses power, Android would put the
     * phone to sleep and Maps would stop drawing for the dash (a hidden display sleeps with the phone; Android 17
     * offers no way around that), so the phone is woken straight back up behind its lock screen and only its
     * panel is switched off: it looks and acts locked, and the dash keeps its live map. The next press shows the
     * lock screen. A call lights the screen like a phone normally does; afterwards it's back as the rider left it.
     */
    private void updatePhoneScreen() {
        boolean dark = running && riderDark && callPhase == 0;
        if (dark != panelOff) panel(!dark);
    }

    private void panel(boolean on) {
        IDashHelper hp = helper; if (hp == null) return;
        try { if (hp.setPanel(on)) panelOff = !on; } catch (RemoteException ignored) {}
    }

    private void watchPowerButton() {
        phoneDisplay = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) {
                if (id != Display.DEFAULT_DISPLAY || !running) return;
                Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                int st = d == null ? Display.STATE_UNKNOWN : d.getState();
                if (st == Display.STATE_OFF || st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND) powerPressed();
            }
        };
        getSystemService(DisplayManager.class).registerDisplayListener(phoneDisplay, new Handler(Looper.getMainLooper()));
        screenOff = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { powerPressed(); } };
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    /** The rider pressed power (or the phone timed out): keep Maps drawing, and do what the rider asked. */
    private void powerPressed() {
        long now = SystemClock.elapsedRealtime();
        if (!running || now - lastPowerAt < 1500) return;          // display listener + broadcast = one press
        lastPowerAt = now;
        final boolean wasDark = panelOff;
        RideLog.add("power: phone " + (wasDark ? "screen on requested" : "locked / screen off requested"));
        new Thread(() -> {
            IDashHelper hp = helper; if (hp == null) return;
            try { hp.key(224); } catch (RemoteException ignored) {}  // WAKEUP (behind the lock screen)
            SystemClock.sleep(300);
            if (wasDark) { riderDark = false; panelOff = false; }   // waking lit the panel: lock screen shows
            else { riderDark = true; updatePhoneScreen(); }          // rider wanted it off
        }, "dash-power").start();
    }

    // ================================ GPS (dash status + compass) ================================
    private android.location.LocationListener gps;
    private volatile long lastFix; private volatile int heading;
    private void startGps() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        gps = loc -> {
            lastFix = SystemClock.elapsedRealtime();
            if (loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() > 1.5f) heading = Math.round(loc.getBearing());
        };
        try { getSystemService(android.location.LocationManager.class).requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gps, Looper.getMainLooper()); }
        catch (Exception e) { Log.w(TAG, "gps", e); }
    }

    /** Debug: a call-category notification from this app (read back through dumpsys like a real call) + call state. */
    private void fakeCall(String name) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("testcall", "Test call", NotificationManager.IMPORTANCE_LOW));
        nm.notify(77, new Notification.Builder(this, "testcall").setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming call").setContentText(name).setCategory(Notification.CATEGORY_CALL).build());
        long now = System.currentTimeMillis();
        fakeRingUntil = now + 12_000; fakeCallUntil = now + 30_000;
        new Handler(Looper.getMainLooper()).postDelayed(() -> nm.cancel(77), 30_000);
        Log.i(TAG, "fake call from " + name);
    }

    private void stopAll() {
        running = false; active = false;
        if (screenOff != null) { try { unregisterReceiver(screenOff); } catch (Exception ignored) {} screenOff = null; }
        if (phoneDisplay != null) { getSystemService(DisplayManager.class).unregisterDisplayListener(phoneDisplay); phoneDisplay = null; }
        if (gps != null) { try { getSystemService(android.location.LocationManager.class).removeUpdates(gps); } catch (Exception ignored) {} gps = null; }
        try { if (helper != null) helper.release(); } catch (Exception ignored) {}
        try { Shizuku.unbindUserService(args, conn, true); } catch (Exception ignored) {}
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    private void fail(String s) { set(s); active = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }

    /** For the app's preview (and debugging): the dash picture + the hidden display, every 5 s. */
    private void snapshot(Bitmap frame, Overlay ov, NavState.Nav nav) {
        try {
            Bitmap soft = frame == null ? null : frame.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap b = Bitmap.createBitmap(OW, OH, Bitmap.Config.ARGB_8888);
            paintFrame(new Canvas(b), soft, nav, ov, new Paint(Paint.FILTER_BITMAP_FLAG), new Rect(0, 0, OW, OH));
            File dir = getExternalFilesDir(null);
            try (FileOutputStream o = new FileOutputStream(new File(dir, "snap.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            if (BuildConfig.DEBUG && soft != null) try (FileOutputStream o = new FileOutputStream(new File(dir, "full.png"))) { soft.compress(Bitmap.CompressFormat.PNG, 100, o); }
            Log.i(TAG, "frame: nav='" + nav.instruction + "' leftM=" + nav.leftM + " onDash=" + mapsOnDash + " fps=" + fps + " | " + linkStatus);
        } catch (Exception e) { Log.e(TAG, "snapshot", e); }
    }

    private void set(String s) { status = s; Log.i(TAG, s); notifyStatus(); }

    private void notifyStatus() {
        try { getSystemService(NotificationManager.class).notify(1, note(status + (linkStatus.isEmpty() ? "" : " · " + linkStatus))); } catch (Exception ignored) {}
    }

    private Notification note(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("dash", "Dash connection", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DashService.class).putExtra("stop", true), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "dash").setSmallIcon(android.R.drawable.ic_menu_mapmode)
                .setContentTitle("Dash Nav").setContentText(text).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    /**
     * H.264 in the shape the Tripper Dash accepts (Baseline 4.1, SPS 42 00 29, keyframe every second). Callback
     * mode: each encoded frame goes out the moment the encoder finishes it (no polling delay).
     */
    final class Encoder {
        final MediaCodec codec; final Surface surface;
        private final HandlerThread thread = new HandlerThread("dash-encoder");
        private volatile RideVideo rec, pending; private volatile MediaFormat outFormat;
        /** Start a new video file at the next keyframe (5-minute segments: a crash or dead battery loses at most one). */
        void rotate(RideVideo next) { pending = next; keyframe(); }
        RideVideo current() { return rec; }
        Encoder(VideoRtp rtp, RideVideo rec0) throws IOException {
            rec = rec0;
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OW, OH);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, rateFor(FPS_START));
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS_MAX);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);    // one keyframe per 12 frames (1.2.0: readable on the real dash)
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            f.setInteger(MediaFormat.KEY_LATENCY, 1);
            thread.start();
            MediaCodec mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mc.setCallback(callback(rtp), new Handler(thread.getLooper()));
            try { mc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
            catch (Exception e) {   // some encoders reject CBR/latency hints: fall back to the basics
                mc.release(); mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                mc.setCallback(callback(rtp), new Handler(thread.getLooper()));
                f.removeKey(MediaFormat.KEY_BITRATE_MODE); f.removeKey(MediaFormat.KEY_LATENCY); f.removeKey(MediaFormat.KEY_PRIORITY);
                mc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            codec = mc;
            surface = codec.createInputSurface(); codec.start();
        }
        private MediaCodec.Callback callback(VideoRtp rtp) {
            return new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(MediaCodec c, int i) {}
                @Override public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                    try {
                        ByteBuffer b = c.getOutputBuffer(i);
                        byte[] d = new byte[info.size]; b.position(info.offset); b.get(d);
                        c.releaseOutputBuffer(i, false);
                        rtp.accessUnit(d, SystemClock.elapsedRealtime());
                        RideVideo nxt = pending;
                        if (nxt != null && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && outFormat != null) {
                            final RideVideo old = rec; pending = null; nxt.format(outFormat); rec = nxt;
                            if (old != null) new Thread(old::finish, "dash-video-close").start();
                        }
                        RideVideo r = rec; if (r != null) r.frame(d, info);
                    } catch (Exception e) { Log.w(TAG, "encoder out", e); }
                }
                @Override public void onError(MediaCodec c, MediaCodec.CodecException e) { Log.e(TAG, "encoder", e); }
                @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat f) { outFormat = f; RideVideo r = rec; if (r != null) r.format(f); }
            };
        }
        /** ~45 kbps per frame per second (200 kbps at 4 fps, the stock app's figure), at most 600 kbps. */
        int rateFor(int fps) { return Math.min(600_000, Math.max(200_000, fps * 45_000)); }
        void bitrate(int fps) { try { Bundle b = new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, rateFor(fps)); codec.setParameters(b); } catch (Exception ignored) {} }
        void keyframe() { try { Bundle b = new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); codec.setParameters(b); } catch (Exception ignored) {} }
        void release() { try { codec.stop(); } catch (Exception ignored) {} codec.release(); thread.quitSafely(); }
    }
}
