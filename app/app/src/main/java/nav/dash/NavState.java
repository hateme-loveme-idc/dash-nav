package nav.dash;

import android.graphics.Bitmap;
import android.os.Bundle;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Google Maps is telling the rider right now. Filled once a second from the Shizuku helper's feed
 * (Maps' own navigation notification, read via dumpsys: no notification access, so Play Protect doesn't
 * block the install).
 */
final class NavState {
    private NavState() {}
    static final String MAPS = "com.google.android.apps.maps";

    /** Immutable snapshot of what Maps is telling the rider right now. */
    public static final class Nav {
        public final String instruction, street, turnDist, arrive, kind; public final long leftM, totalM; public final int turnMeters;
        public final Bitmap icon; public final long at;
        Nav(String instruction, String street, String turnDist, String arrive, String kind, long leftM, long totalM, Bitmap icon) {
            this.instruction = instruction; this.street = street; this.turnDist = turnDist; this.arrive = arrive; this.kind = kind;
            this.leftM = leftM; this.totalM = totalM; this.icon = icon; this.at = System.currentTimeMillis();
            this.turnMeters = meters(turnDist);
        }
        static int meters(String d) {
            try {
                String[] p = d.split(" ");
                double v = Double.parseDouble(p[0]);
                return (int) Math.round(p[1].equals("km") ? v * 1000 : v);
            } catch (Exception e) { return 0; }
        }
        public boolean active() { return !instruction.isEmpty(); }
    }

    static volatile Nav current = empty();

    static Nav empty() { return new Nav("", "", "", "", "straight", -1, -1, null); }
    static void reset() { current = empty(); }

    /** Update from the helper's feed bundle. */
    static void update(Bundle b) {
        if (b == null || !b.getBoolean("nav")) { reset(); return; }
        String title = b.getString("title", ""), text = b.getString("text", ""), sub = b.getString("sub", "");
        int prog = b.getInt("progress", -1), max = b.getInt("max", -1);
        current = parse(title, text, sub, prog, max, null);
        if (BuildConfig.DEBUG) {
            String sig = title + "|" + text + "|" + sub + "|" + prog + "/" + max;
            if (!sig.equals(lastSig)) { lastSig = sig; android.util.Log.i("DashNavFeed", "nav: " + sig + " -> kind=" + current.kind + " leftM=" + current.leftM + " turnM=" + current.turnMeters); }
        }
    }
    private static String lastSig = "";

    static Nav parse(String title, String text, String sub, int prog, int max, Bitmap icon) {
        String instruction = title.isEmpty() ? text : title;
        String all = (title + " " + text).trim();
        String turnDist = NavText.turnDistance(all);
        String street = NavText.street(instruction);
        if (street.isEmpty()) street = NavText.place(instruction, text);
        Matcher a = Pattern.compile("(\\d{1,2}:\\d{2}\\s*(?:[ap]\\.?m\\.?)?)", Pattern.CASE_INSENSITIVE).matcher(NavText.spaces(sub));
        String arrive = a.find() ? a.group(1).replace(".", "").toLowerCase(Locale.ROOT) : "";
        long left = max > 0 && prog >= 0 ? Math.max(0, max - prog) : -1;
        return new Nav(instruction, street, turnDist, arrive, NavText.kind(instruction.isEmpty() ? text : instruction), left, max, icon);
    }

    /** Our turn kind -> the Tripper Dash's own glyph code (left-hand traffic: clockwise roundabouts/U-turns). */
    static int dashGlyph(String kind) {
        switch (kind) {
            case "arrive": return 0;
            case "left": return 20; case "right": return 21;
            case "sharp_left": return 22; case "sharp_right": return 23;
            case "slight_left": return 24; case "slight_right": return 25;
            case "uturn": return 26; case "merge": return 27;
            case "fork_left": return 6; case "fork_right": return 5;
            case "ramp_left": return 8; case "ramp_right": return 7;
            case "roundabout": return 10;
            default: return 9;
        }
    }

}
