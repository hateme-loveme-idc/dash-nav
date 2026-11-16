package nav.dash;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import nav.dash.link.DashLink;
import nav.dash.link.K1g;

/**
 * "Test bike data": finds out what the dash (and an OBD adapter, if chosen) will tell us, so the next build can show
 * odometer, range, RPM… Writes everything to Download/Dash Nav/bike-data-*.txt for the developer.
 *
 *  1. Listen (60 s): every message the dash sends, verbatim, while the rider flips through the dash's screens.
 *  2. Ask (~1 min, only with the bike parked): one request-shaped message for each unexplored message type/sub and
 *     whatever the dash answers within 250 ms. The message kinds Dash Nav already uses are left out.
 *  3. OBD (if an adapter is set): the standard read-only engine values. The VIN is never read.
 */
final class BikeProbe {
    static volatile boolean running;

    private static final int LISTEN_S = 60;
    /** Kinds Dash Nav already sends (05 = information, 06 = commands): never re-sent by the probe. */
    private static final int[] SKIP_TYPES = {0x05, 0x06, 0x07};
    private static final String[][] OBD = {
            {"0100", "supported values 01-20"}, {"0120", "supported values 21-40"}, {"0140", "supported values 41-60"},
            {"0160", "supported values 61-80"}, {"0180", "supported values 81-A0"}, {"01A0", "supported values A1-C0"},
            {"0101", "fault status"}, {"0104", "engine load"}, {"0105", "coolant temperature"}, {"010B", "intake pressure"},
            {"010C", "engine RPM"}, {"010D", "vehicle speed"}, {"010E", "ignition timing"}, {"010F", "intake air temperature"},
            {"0111", "throttle position"}, {"011C", "OBD standard"}, {"011F", "run time since start"},
            {"0121", "distance with fault lamp on"}, {"012F", "fuel level"}, {"0131", "distance since codes cleared"},
            {"0133", "barometric pressure"}, {"0142", "battery voltage"}, {"0146", "ambient temperature"},
            {"0151", "fuel type"}, {"015C", "oil temperature"}, {"015E", "fuel rate"}, {"01A4", "gear (transmission)"},
            {"01A6", "odometer"}, {"0902", "(VIN: skipped on purpose)"}, {"03", "stored fault codes"}, {"ATRV", "adapter: battery voltage"},
            {"ATDP", "adapter: protocol in use"},
    };

    static void start(Context ctx, DashLink link, ObdLink obd) {
        if (running) return;
        running = true;
        new Thread(() -> {
            try { run(ctx, link, obd); }
            catch (Exception e) { DashService.probeStatus = "Stopped: " + e.getMessage(); }
            finally { link.rawTap = null; running = false; }
        }, "dash-probe").start();
    }

    private static void run(Context ctx, DashLink link, ObdLink obd) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("Dash Nav ").append(BuildConfig.VERSION_NAME).append(" · bike data test · ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append('\n');
        long t0 = SystemClock.elapsedRealtime();
        ConcurrentLinkedQueue<String> rx = new ConcurrentLinkedQueue<>();
        link.rawTap = t -> rx.add(String.format(Locale.US, "%6.2f  %02X/%02X len%d  %s",
                (SystemClock.elapsedRealtime() - t0) / 1000.0, t.type, t.sub, t.value.length, K1g.hex(t.value, 64)));

        // 1. listen
        out.append("\n== 1. Listen ").append(LISTEN_S).append(" s (frame/keyframe acknowledgements counted, not listed)\n");
        Map<String, Integer> acks = new LinkedHashMap<>();
        for (int s = LISTEN_S; s > 0 && running; s--) {
            DashService.probeStatus = "1/3 Listening: flip through the dash's screens now (trip, fuel, range…) · " + s + " s";
            SystemClock.sleep(1000);
            drain(rx, out, acks);
        }
        out.append("acknowledgements: ").append(acks).append('\n');

        // 2. ask
        out.append("\n== 2. Ask: one request per message kind, replies within 250 ms\n");
        int asked = 0, answered = 0, total = 0;
        for (int type = 0x00; type <= 0x0F; type++) { if (!skip(type)) total += 0x40; }
        for (int type = 0x00; type <= 0x0F && running; type++) {
            if (skip(type)) continue;
            for (int sub = 0x00; sub < 0x40 && running; sub++) {
                if (!link.streaming()) { out.append("dash link dropped: stopped asking at ").append(String.format(Locale.US, "%02X/%02X", type, sub)).append('\n'); type = 0x10; break; }
                rx.clear();
                link.probe(type, sub, new byte[]{0x01});
                asked++;
                SystemClock.sleep(250);
                StringBuilder got = new StringBuilder(); Map<String, Integer> ign = new LinkedHashMap<>();
                drain(rx, got, ign);
                if (got.length() > 0) { answered++; out.append(String.format(Locale.US, "asked %02X/%02X =01 →\n", type, sub)).append(got); }
                if (asked % 16 == 0) DashService.probeStatus = "2/3 Asking the dash: " + asked + " of " + total + " (" + answered + " answered)";
            }
        }
        out.append("asked ").append(asked).append(", answered ").append(answered).append('\n');

        // 3. OBD
        out.append("\n== 3. OBD adapter\n");
        if (obd == null) out.append("no adapter chosen (Settings › RPM bar)\n");
        else if (!obd.connected()) out.append("adapter not connected: ").append(obd.status).append('\n');
        else {
            DashService.probeStatus = "3/3 Reading engine values from the OBD adapter…";
            obd.answers.clear();
            for (String[] q : OBD) if (!q[0].equals("0902")) obd.queries.add(q[0]);
            long end = SystemClock.elapsedRealtime() + 90_000;
            while (obd.answers.size() < OBD.length - 1 && SystemClock.elapsedRealtime() < end && running) SystemClock.sleep(200);
            for (String[] q : OBD) out.append(String.format(Locale.US, "%-5s %-30s %s%n", q[0], q[1], q[0].equals("0902") ? "-" : obd.answers.getOrDefault(q[0], "(no reply)").replace('\r', ' ')));
        }
        Uri file = save(ctx, out.toString());
        DashService.probeStatus = "Done: " + answered + " of " + asked + " requests answered · saved to Download/Dash Nav" + (file == null ? " (save failed)" : "");
    }

    private static boolean skip(int type) { for (int t : SKIP_TYPES) if (t == type) return true; return false; }

    /** Moves new dash messages to out; frame/keyframe acks (09/04, 09/06) are only counted. */
    private static void drain(ConcurrentLinkedQueue<String> rx, StringBuilder out, Map<String, Integer> acks) {
        String l;
        while ((l = rx.poll()) != null) {
            String kind = l.substring(8, 13);
            if (kind.equals("09/04") || kind.equals("09/06")) acks.merge(kind, 1, Integer::sum);
            else out.append(l).append('\n');
        }
    }

    private static Uri save(Context ctx, String text) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, "bike-data-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dash Nav");
            Uri u = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (OutputStream o = ctx.getContentResolver().openOutputStream(u)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
            return u;
        } catch (Exception e) { return null; }
    }
}
