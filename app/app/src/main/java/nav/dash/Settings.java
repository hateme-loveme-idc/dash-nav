package nav.dash;

import android.content.Context;
import android.content.SharedPreferences;

import nav.dash.link.DashLink;

/** User settings (all have safe defaults for the Himalayan 450). */
final class Settings {
    private final SharedPreferences p;
    Settings(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** Talk to a virtual dash instead of the bike (development / testing). */
    boolean testMode() { return BuildConfig.DEBUG && p.getBoolean("test_mode", false); }
    void testMode(boolean v) { p.edit().putBoolean("test_mode", v).apply(); }
    String testHost() { return p.getString("test_host", "127.0.0.1"); }
    void testHost(String v) { p.edit().putString("test_host", v).apply(); }

    /** Also drive the dash's own turn circle (off = Option B, the turn card on the map). */
    boolean dashTurnCircle() { return p.getBoolean("dash_turn_circle", false); }
    void dashTurnCircle(boolean v) { p.edit().putBoolean("dash_turn_circle", v).apply(); }

    /** Save the dash video to the app folder (for troubleshooting). */
    boolean record() { return p.getBoolean("record", false); }
    void record(boolean v) { p.edit().putBoolean("record", v).apply(); }

    /** Ignore a different joystick direction this soon after the last press (the stick's ghost second press). */
    int ghostMs() { return p.getInt("ghost_ms", 300); }
    void ghostMs(int v) { p.edit().putInt("ghost_ms", v).apply(); }
    /** Show the rider's wallpaper on the dash while Google Maps isn't navigating. */
    boolean wallpaper() { return p.getBoolean("wallpaper", false); }
    void wallpaper(boolean v) { p.edit().putBoolean("wallpaper", v).apply(); }
    /** The dash Wi-Fi we joined last (name + access-point MAC): later connections go straight to it. */
    String dashSsid() { return p.getString("dash_ssid", ""); }
    String dashBssid() { return p.getString("dash_bssid", ""); }
    void dash(String ssid, String bssid) { p.edit().putString("dash_ssid", ssid).putString("dash_bssid", bssid).apply(); }

    /** RPM bar along the top of the map, fed by a Bluetooth OBD-II adapter (ELM327 type). */
    boolean rpmBar() { return p.getBoolean("rpm_bar", false); }
    void rpmBar(boolean v) { p.edit().putBoolean("rpm_bar", v).apply(); }
    String obdAddress() { return p.getString("obd_address", ""); }
    String obdName() { return p.getString("obd_name", ""); }
    boolean obdClassic() { return p.getBoolean("obd_classic", false); }
    void obd(String address, String name, boolean classic) { p.edit().putString("obd_address", address).putString("obd_name", name).putBoolean("obd_classic", classic).apply(); }
    /** Full scale and red zone of the bar (Himalayan 450: peak power at 8,000 rpm, rev limit near 9,000). */
    int rpmMax() { return p.getInt("rpm_max", 9000); }
    int rpmRedline() { return p.getInt("rpm_redline", 8000); }

    /** Dash Wi-Fi password (factory default 12345678). */
    String passphrase() { return p.getString("passphrase", "12345678"); }

    DashLink.Config linkConfig() {
        DashLink.Config c = new DashLink.Config();
        c.testMode = testMode(); c.testHost = testHost(); c.passphrase = passphrase();
        c.dashTurnCircle = dashTurnCircle();
        c.knownSsid = dashSsid(); c.knownBssid = dashBssid();
        return c;
    }
}
