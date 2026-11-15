package nav.dash;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.File;

import rikka.shizuku.Shizuku;

/** Dash Nav control screen: checklist, start/stop, optional destination, settings, live preview. */
public class MainActivity extends Activity {
    static final int BG = 0xFF0B1014, CARD = 0xFF162029, INK = 0xFFE9EEF2, MUTED = 0xFF98A3AD, ACCENT = 0xFF3FB6A8;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView checks, live, joy; private Button go; private EditText dest; private ImageView preview, wpPreview;
    private nav.dash.Settings settings;

    /** The live preview is only rendered while this screen is visible (saves work on the ride). */
    static volatile boolean visible;
    @Override protected void onResume() { super.onResume(); visible = true; }
    @Override protected void onPause() { super.onPause(); visible = false; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = new nav.dash.Settings(this);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(BG); scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(col);

        TextView title = text("Dash Nav", 30, INK, true); title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = text("GOOGLE MAPS ON YOUR HIMALAYAN 450", 12, MUTED, false); sub.setLetterSpacing(0.12f);
        col.addView(title); col.addView(sub);

        checks = text("", 15, INK, false); col.addView(card(checks));

        TextView how = text("Plan and start your route in Google Maps (two-wheeler mode, add stops), then tap Start. "
                + "Or type a destination below.", 14, MUTED, false);
        how.setPadding(0, dp(14), 0, dp(6)); col.addView(how);

        dest = new EditText(this); dest.setHint("Destination (optional)"); dest.setTextColor(INK); dest.setHintTextColor(MUTED);
        dest.setSingleLine(true); dest.setInputType(InputType.TYPE_CLASS_TEXT); dest.setBackground(round(CARD, dp(14)));
        dest.setPadding(dp(16), dp(14), dp(16), dp(14)); col.addView(dest);

        go = new Button(this); go.setAllCaps(false); go.setTextSize(18); go.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(58)); gp.topMargin = dp(14); go.setLayoutParams(gp);
        go.setOnClickListener(v -> toggle()); col.addView(go);

        live = text("", 13, MUTED, false); live.setPadding(0, dp(10), 0, 0); col.addView(live);

        preview = new ImageView(this); preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(300), dp(300)); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.topMargin = dp(12); preview.setLayoutParams(pp);
        col.addView(preview);

        col.addView(section("Settings"));
        col.addView(toggleRow("Dash turn circle", "Also show the turn on the dash's own circle (off: the turn card on the map)",
                settings.dashTurnCircle(), settings::dashTurnCircle));
        col.addView(toggleRow("Save ride videos", "What the dash showed, as videos in your Gallery (Movies › Dash Nav)",
                settings.record(), settings::record));

        col.addView(section("Wallpaper"));
        TextView wpHint = text("Shown on the dash while Google Maps isn't navigating. Without one, the dash shows the time.", 12, MUTED, false);
        col.addView(wpHint);
        wpPreview = new ImageView(this); wpPreview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(260), dp(260)); wp.gravity = Gravity.CENTER_HORIZONTAL; wp.topMargin = dp(10);
        wpPreview.setLayoutParams(wp); col.addView(wpPreview);
        LinearLayout wpRow = new LinearLayout(this); wpRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = smallButton("Choose image"); pick.setOnClickListener(v -> startActivityForResult(
                new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), 7));
        Button clear = smallButton("Remove"); clear.setOnClickListener(v -> { new File(getFilesDir(), "wallpaper.png").delete(); settings.wallpaper(false); showWallpaper(); });
        wpRow.addView(pick, new LinearLayout.LayoutParams(0, dp(46), 1f)); wpRow.addView(clear, new LinearLayout.LayoutParams(0, dp(46), 1f));
        col.addView(wpRow);
        col.addView(toggleRow("Show wallpaper", "When off, the dash shows the time while you're not navigating",
                settings.wallpaper(), v -> { settings.wallpaper(v); showWallpaper(); }));
        showWallpaper();

        col.addView(section("Joystick on the dash"));
        col.addView(text("While the dash shows the Dash Nav picture:\n"
                + "•  Down, Down: menu (play / pause, next, previous, phone screen on/off)\n"
                + "•  In the menu: Down = next row, Right = select, Left = close\n"
                + "•  Incoming call: Up = answer, Down = reject. In a call: Down = hang up\n"
                + "•  The dash's own music player: Right / Left = next / previous track\n"
                + "Everything else (switching the dash between navigation and music, centre press) is the dash's own.", 13, MUTED, false));
        joy = text("", 13, INK, false); joy.setPadding(0, dp(8), 0, 0); col.addView(joy);

        col.addView(section("Advanced"));
        col.addView(toggleRow("Test mode (virtual dash)", "Sends to a test dash instead of the bike. Leave off when riding.",
                settings.testMode(), settings::testMode));

        TextView foot = text("v" + BuildConfig.VERSION_NAME + " · Tripper Dash firmware 11.61 and newer", 12, MUTED, false);
        foot.setPadding(0, dp(18), 0, 0); col.addView(foot);
        setContentView(scroll);
        requestBasics();
        tick();
    }

    private void toggle() {
        if (DashService.active) { startForegroundService(new Intent(this, DashService.class).putExtra("stop", true)); return; }
        askBattery();   // not required to start, but the system may stop a long ride without it
        String missing = missing();
        if (missing != null) { Toast.makeText(this, missing, Toast.LENGTH_LONG).show(); fixFirst(); return; }
        startForegroundService(new Intent(this, DashService.class).putExtra("dest", dest.getText().toString().trim()));
    }

    private String missing() {
        if (!Shizuku.pingBinder()) return "Open Shizuku and tap Start first";
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "Allow Dash Nav in Shizuku";
        if (!settings.testMode() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Allow location: Android needs it to find the dash Wi-Fi";
        if (!settings.testMode() && !locationOn()) return "Turn on Location: Android needs it to find the dash Wi-Fi";
        return null;
    }

    private void fixFirst() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(1); return; }
        if (!settings.testMode() && !locationOn()) { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return; }
        if (!Shizuku.pingBinder()) {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            startActivity(i != null ? i : new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")));
        }
    }

    private void requestBasics() {
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String p : new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_CONTACTS})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 2);
        else askBattery();
    }

    /** After the permission answers (asking at the same time hid this prompt behind them). */
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) { super.onRequestPermissionsResult(req, perms, res); askBattery(); }

    private void askBattery() {
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
    }

    private boolean locationOn() { return getSystemService(android.location.LocationManager.class).isLocationEnabled(); }

    private void tick() {
        boolean shz = Shizuku.pingBinder(), shzOk = shz && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        boolean wifi = getSystemService(android.net.wifi.WifiManager.class).isWifiEnabled();
        boolean loc = locationOn(), batt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        NavState.Nav n = NavState.current;
        checks.setText(line(shzOk, shz ? (shzOk ? "Shizuku ready" : "Shizuku: allow Dash Nav") : "Shizuku not running")
                + "\n" + line(loc, loc ? "Location on (finds the dash Wi-Fi)" : "Turn on Location to find the dash Wi-Fi")
                + (settings.testMode() ? "" : "\n" + line(wifi, wifi ? "Wi-Fi on (the dash talks over Wi-Fi)" : "Turn on Wi-Fi to reach the dash"))
                + "\n" + line(batt, batt ? "Won't be stopped by battery saver" : "Battery saver may stop the dash")
                + (DashService.active ? "\n" + line(n.active(), n.active() ? "Maps: " + n.instruction + (n.arrive.isEmpty() ? "" : " · arrive " + n.arrive) : "Maps isn't navigating") : "")
                + (settings.testMode() ? "\n⚠  Test mode is on (virtual dash, not the bike)" : ""));
        boolean on = DashService.active;
        go.setText(on ? "Stop" : "Start on dash");
        go.setTextColor(on ? INK : 0xFF04140F);
        go.setBackground(round(on ? CARD : ACCENT, dp(29)));
        live.setText(on || !"Ready".equals(DashService.status)
                ? DashService.status + (DashService.linkStatus.isEmpty() ? "" : "\n" + DashService.linkStatus) + (DashService.detail.isEmpty() ? "" : "\n" + DashService.detail) : "");
        joy.setText(DashService.lastJoystick.isEmpty() ? "Last joystick press: none yet (press one while the dash shows Dash Nav)" : "Last joystick press: " + DashService.lastJoystick);
        File snap = new File(getExternalFilesDir(null), "snap.png");
        if (on && snap.exists()) { preview.setVisibility(View.VISIBLE); preview.setImageBitmap(Wallpaper.preview(BitmapFactory.decodeFile(snap.getPath()), "")); }   // as it looks on the round dial
        else preview.setVisibility(View.GONE);
        h.postDelayed(this::tick, 1500);
    }

    // ---- wallpaper ----
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 7 && res == RESULT_OK && data != null && data.getData() != null)          // picked: crop it
            startActivityForResult(new Intent(this, WallpaperEditor.class).setData(data.getData()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 8);
        else if (req == 8 && res == RESULT_OK) recreate();                                      // saved: refresh preview + switch
    }

    private void showWallpaper() {
        File f = new File(getFilesDir(), "wallpaper.png");
        android.graphics.Bitmap wp = f.exists() && settings.wallpaper() ? BitmapFactory.decodeFile(f.getPath()) : null;
        wpPreview.setImageBitmap(Wallpaper.preview(wp));
    }

    private Button smallButton(String label) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(label); b.setTextColor(INK); b.setBackground(round(CARD, dp(14))); return b;
    }

    private static String line(boolean ok, String s) { return (ok ? "✓  " : "•  ") + s; }

    // ---- small UI helpers ----
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private View card(TextView inner) {
        LinearLayout c = new LinearLayout(this); c.setBackground(round(CARD, dp(18))); c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(18); c.setLayoutParams(lp);
        inner.setLineSpacing(dp(4), 1f); c.addView(inner); return c;
    }
    private View section(String s) { TextView t = text(s.toUpperCase(), 12, ACCENT, true); t.setLetterSpacing(0.14f); t.setPadding(0, dp(26), 0, dp(6)); return t; }
    private View toggleRow(String name, String hint, boolean value, java.util.function.Consumer<Boolean> save) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout txt = new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(name, 15, INK, false)); txt.addView(text(hint, 12, MUTED, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this); sw.setChecked(value); sw.setOnCheckedChangeListener((v, c) -> save.accept(c));
        row.addView(sw); return row;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
