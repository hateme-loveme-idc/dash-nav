package nav.dash;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses `dumpsys notification --noredact` (run by the Shizuku helper as shell). This is how Dash Nav reads
 * Google Maps' turn-by-turn notification and the caller's name WITHOUT notification access: sideloaded apps
 * that ask for notification access are blocked by Google Play Protect in some countries.
 * Pure Java, so it's unit-tested on a JVM against real captured output.
 */
final class NotifDump {
    private NotifDump() {}
    static final String MAPS = "com.google.android.apps.maps";

    static final class Result {
        boolean nav; String title = "", text = "", sub = ""; int progress = -1, max = -1;
        String caller = "";   // title of a call notification (incoming or ongoing), "" if none
    }

    private static final Pattern PKG = Pattern.compile("pkg=(\\S+)");
    private static final Pattern CHANNEL = Pattern.compile("channel=(\\S+)");
    private static final Pattern CATEGORY = Pattern.compile("category=(\\S+)");
    private static final Pattern EXTRA = Pattern.compile("^\\s*android\\.(title|text|subText|progress|progressMax)=(?:null|\\S+ \\((.*)\\))\\s*$");

    static Result parse(String dump) {
        Result r = new Result();
        String[] blocks = dump.split("(?m)^(?=\\s*NotificationRecord\\()");
        for (String b : blocks) {
            int nl = b.indexOf('\n');
            String head = nl < 0 ? b : b.substring(0, nl);
            if (!head.contains("NotificationRecord(")) continue;
            String pkg = find(PKG, head), ch = find(CHANNEL, head), cat = find(CATEGORY, head);
            boolean isNav = MAPS.equals(pkg) && (ch.startsWith("1_foreground") || "navigation".equals(cat));
            boolean isCall = "call".equals(cat);
            if (!isNav && !isCall) continue;
            String title = "", text = "", sub = ""; int prog = -1, max = -1;
            String[] lines = b.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.matches("^ {0,2}\\S.*")) break;        // next dumpsys section: this record ended
                Matcher m = EXTRA.matcher(line);
                if (!m.matches()) continue;
                String v = m.group(2) == null ? "" : NavText.spaces(m.group(2)).trim();
                switch (m.group(1)) {
                    case "title": title = v; break;
                    case "text": text = v; break;
                    case "subText": sub = v; break;
                    case "progress": prog = toInt(v); break;
                    case "progressMax": max = toInt(v); break;
                }
            }
            if (isNav && !r.nav && !(title.isEmpty() && text.isEmpty())) {
                r.nav = true; r.title = title; r.text = text; r.sub = sub; r.progress = prog; r.max = max;
            }
            if (isCall && r.caller.isEmpty()) r.caller = callerName(title, text);
        }
        return r;
    }

    private static final Pattern GENERIC = Pattern.compile(
            "(?i)^((incoming|ongoing|outgoing|current|missed|wi-?fi|hd|volte)\\s+)*((voice|video|whatsapp|audio)\\s+)?call(ing)?\\b.*|^(calling|dialing|ringing|on hold|connecting)\\b.*|^$");
    /** A dialer may put "Incoming call" in the title and the name/number in the text, or the other way round. */
    static String callerName(String title, String text) {
        String t = title == null ? "" : title.trim(), x = text == null ? "" : text.trim();
        if (!GENERIC.matcher(t).matches()) return t;
        if (!GENERIC.matcher(x).matches()) return x;
        return "";
    }

    private static String find(Pattern p, String s) { Matcher m = p.matcher(s); return m.find() ? m.group(1) : ""; }
    private static int toInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; } }
}
