package nav.dash;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The privileged half of Dash Nav. Shizuku starts this class in a separate process running as
 * shell (ADB mode) or root (root mode). It only does what a normal app can't:
 *  - create a hidden display that other apps (Google Maps) can run on, drawing into the app's surface
 *  - start activities on that display
 *  - power the phone's panel off/on while the device stays awake
 * Everything visual (overlay, fonts, encoding) happens in the normal app process.
 */
public class DashHelper extends IDashHelper.Stub {
    private static final String TAG = "DashNavHelper";
    private VirtualDisplay display;
    private int displayFlags;
    static final float REFRESH_HZ = 20f;
    private final Panel panel = new Panel();

    public DashHelper() { if (Looper.myLooper() == null) { try { Looper.prepare(); } catch (Throwable ignored) {} } }

    @Override public void destroy() { cleanup(); System.exit(0); }

    @Override public void setClient(IBinder client) {
        try { client.linkToDeath(() -> { Log.w(TAG, "app died: restoring panel"); cleanup(); System.exit(0); }, 0); }
        catch (Exception e) { Log.e(TAG, "linkToDeath", e); }
    }

    @Override public synchronized int createDisplay(Surface surface, int w, int h, int dpi) {
        try {
            if (display != null) display.release();
            DisplayManager dm = newDisplayManager(shellContext());
            int base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | (1 << 10);   // VIRTUAL_DISPLAY_FLAG_TRUSTED (hidden): allows other apps' activities
            // Always unlocked (hidden, shell may): Maps keeps drawing for the dash while the phone shows its lock screen.
            int[] tries = {base | (1 << 12), base};
            for (int flags : tries) {
                try {
                    // Capped refresh rate: Maps would otherwise render this display at 60 Hz for a 4-12 fps stream
                    // (the main source of heat). 20 Hz still gives every frame we send a fresh picture.
                    android.hardware.display.VirtualDisplayConfig cfg = new android.hardware.display.VirtualDisplayConfig.Builder("dash-nav", w, h, dpi)
                            .setFlags(flags).setSurface(surface).setRequestedRefreshRate(REFRESH_HZ).build();
                    display = dm.createVirtualDisplay(cfg, null, null);
                    displayFlags = flags; break;
                } catch (Throwable t) { Log.w(TAG, "createVirtualDisplay flags=0x" + Integer.toHexString(flags) + ": " + t); display = null; }
            }
            if (display == null) { display = dm.createVirtualDisplay("dash-nav", w, h, dpi, surface, base); displayFlags = base; }
            return display.getDisplay().getDisplayId();
        } catch (Throwable t) { Log.e(TAG, "createDisplay", t); return -1; }
    }

    @Override public String startOnDisplay(int displayId, String amArgs) {
        String r = sh("am start --display " + displayId + " " + amArgs);
        if (amArgs.contains(MAPS)) blockPip();
        return r;
    }

    @Override public boolean setPanel(boolean on) { return panel.set(on ? 2 : 0); }

    /** Root task id of Google Maps and the display it's on ("task display"), or "" if not running. */
    @Override public String mapsTask() {
        String out = sh("am stack list"); int display = -1;
        for (String line : out.split("\n")) {
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("RootTask id=(\\d+).*displayId=(\\d+)").matcher(line);
            if (d.find()) { display = Integer.parseInt(d.group(2)); continue; }
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("taskId=(\\d+): com\\.google\\.android\\.apps\\.maps/").matcher(line);
            if (t.find()) return t.group(1) + " " + display;
        }
        return "";
    }

    /** Move a running app's root task (e.g. Google Maps mid-navigation) to another display. */
    @Override public String moveTask(int taskId, int displayId) {
        String r = sh("am display move-stack " + taskId + " " + displayId);
        if (displayId != 0) blockPip(); else restorePip();   // back on the phone: the rider's own PiP setting again
        return r;
    }

    /*
     * Picture-in-picture: when the rider leaves Google Maps mid-navigation (e.g. to open Dash Nav), Maps shrinks
     * into a pinned PiP window. Moved to the hidden display like that, it stays a tiny pinned window and the map
     * never draws. Revoking Maps' PiP app-op makes Android expand it to fullscreen (navigation keeps running) and
     * stops it re-entering PiP during the ride. The rider's original setting is restored on stop/cleanup.
     */
    static final String MAPS = "com.google.android.apps.maps";
    private String pipPrev;

    private synchronized void blockPip() {
        if (pipPrev != null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PICTURE_IN_PICTURE: (\\w+)")
                .matcher(sh("appops get " + MAPS + " PICTURE_IN_PICTURE"));
        pipPrev = m.find() ? m.group(1) : "default";
        sh("appops set " + MAPS + " PICTURE_IN_PICTURE ignore");
    }

    private synchronized void restorePip() {
        if (pipPrev == null) return;
        sh("appops set " + MAPS + " PICTURE_IN_PICTURE " + pipPrev);
        pipPrev = null;
    }

    @Override public String stopMaps() { return sh("am force-stop " + MAPS); }

    @Override public synchronized void release() { cleanup(); }




    // ---- live data for the dash: Maps navigation, calls, music (no notification access needed) ----
    @Override public android.os.Bundle feed() {
        android.os.Bundle b = new android.os.Bundle();
        NotifDump.Result r = NotifDump.parse(sh("dumpsys notification --noredact"));
        b.putBoolean("nav", r.nav); b.putString("title", r.title); b.putString("text", r.text); b.putString("sub", r.sub);
        b.putInt("progress", r.progress); b.putInt("max", r.max); b.putString("caller", r.caller);
        int callState = 0;   // TelephonyManager: 0 idle, 1 ringing, 2 off-hook (any SIM)
        String tel = sh("dumpsys telephony.registry");
        java.util.regex.Matcher num = java.util.regex.Pattern.compile("mCallIncomingNumber=(\\+?[0-9][0-9 ()-]{3,})").matcher(tel);
        b.putString("number", num.find() ? num.group(1).trim() : "");
        java.util.regex.Matcher c = java.util.regex.Pattern.compile("mCallState=(\\d)").matcher(tel);
        boolean ringing = false, offhook = false;
        while (c.find()) { int v = Integer.parseInt(c.group(1)); ringing |= v == 1; offhook |= v == 2; }
        callState = ringing ? 1 : offhook ? 2 : 0;
        b.putInt("callState", callState);
        try {
            android.media.session.MediaController mc = media();
            if (mc != null) {
                android.media.MediaMetadata md = mc.getMetadata();
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                b.putString("mediaPkg", mc.getPackageName());
                b.putBoolean("playing", ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING);
                if (md != null) {
                    b.putString("song", str(md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)));
                    b.putString("artist", str(md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)));
                    b.putString("album", str(md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)));
                }
            }
        } catch (Throwable t) { b.putString("mediaError", String.valueOf(t)); }
        b.putInt("displayFlags", displayFlags);
        return b;
    }

    private android.media.session.MediaSessionManager msm;

    /** The music to show/control: whatever is playing, else the most recent session. */
    private android.media.session.MediaController media() throws Exception {
        if (msm == null) {
            // a bare app_process never initialises the media framework's service registry: do it once
            try {
                Class<?> c = Class.forName("android.media.MediaServiceManager");
                Object inst = c.getDeclaredConstructor().newInstance();
                for (String n : new String[]{"android.media.MediaFrameworkInitializer", "android.media.MediaFrameworkPlatformInitializer"})
                    try { Method m = Class.forName(n).getDeclaredMethod("setMediaServiceManager", c); m.setAccessible(true); m.invoke(null, inst); } catch (Throwable ignored) {}
            } catch (Throwable t) { Log.w(TAG, "media init", t); }
            msm = shellContext().getSystemService(android.media.session.MediaSessionManager.class);
        }
        java.util.List<android.media.session.MediaController> all = msm.getActiveSessions(null);
        android.media.session.MediaController playing = null;
        for (android.media.session.MediaController mc : all) {
            android.media.session.PlaybackState ps = mc.getPlaybackState();
            if (playing == null && ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) playing = mc;
        }
        return playing != null ? playing : (all.isEmpty() ? null : all.get(0));
    }

    /** 1 next, 2 previous, 3 play/pause. */
    @Override public void mediaAction(int action) {
        try {
            android.media.session.MediaController mc = media(); if (mc == null) return;
            android.media.session.MediaController.TransportControls t = mc.getTransportControls();
            if (action == 1) t.skipToNext(); else if (action == 2) t.skipToPrevious();
            else if (action == 3) { android.media.session.PlaybackState ps = mc.getPlaybackState(); if (ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) t.pause(); else t.play(); }
        } catch (Throwable t) { Log.w(TAG, "media", t); }
    }

    /** Inject a key (answer/end call, wake the phone). Only the codes Dash Nav uses are allowed. */
    @Override public void key(int code) {
        if (code == 79 /* HEADSETHOOK */ || code == 6 /* ENDCALL */ || code == 224 /* WAKEUP */ || code == 223 /* SLEEP */) sh("input keyevent " + code);
    }

    private static String str(String s) { return s == null ? "" : s; }

    private synchronized void cleanup() {
        panel.set(2);
        try {   // hand Google Maps back to the phone screen before the hidden display goes away
            String[] m = mapsTask().split(" ");
            if (display != null && m.length == 2 && Integer.parseInt(m[1]) == display.getDisplay().getDisplayId())
                moveTask(Integer.parseInt(m[0]), 0);
        } catch (Exception ignored) {}
        restorePip();
        if (display != null) { display.release(); display = null; }
    }

    // ---- privileged plumbing ----
    static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            Constructor<?> ctor = at.getDeclaredConstructor(); ctor.setAccessible(true);
            thread = ctor.newInstance();
            Field cur = at.getDeclaredField("sCurrentActivityThread"); cur.setAccessible(true); cur.set(null, thread);
        }
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

    static String sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + " 2>&1"});
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); InputStream in = p.getInputStream();
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            p.waitFor(); return bo.toString("UTF-8").trim();
        } catch (Exception e) { return "error: " + e; }
    }

    /**
     * Physical panel power via SurfaceControl + DisplayControl (services.jar, Android 14+).
     * Runs only in the Shizuku helper (shell/root app_process), where the app's hidden-API limits don't apply.
     */
    static final class Panel {
        Method setMode; Object token;
        @android.annotation.SuppressLint("BlockedPrivateApi")
        Panel() {
            try {
                Class<?> sc = Class.forName("android.view.SurfaceControl");
                setMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
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
            } catch (Throwable t) { Log.e(TAG, "panel control unavailable", t); }
        }
        boolean set(int mode) {
            try { if (token == null) return false; setMode.invoke(null, token, mode); return true; }
            catch (Throwable t) { Log.e(TAG, "panel", t); return false; }
        }
    }
}
