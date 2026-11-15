package nav.dash;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-text parsing of Google Maps' navigation notification (no Android classes, so it's unit-tested on a JVM).
 *
 * Titles look like:
 *   "Head toward Main Rd"
 *   "120 m · Turn right onto Main Rd"
 *   "700 m · Turn right after City Travels (on the right) onto College Rd"
 *   "Turn left at Café X onto Mill Rd", "At the roundabout, take the 2nd exit onto Highway 48", "Your destination is on the left"
 * Landmark clauses ("after …", "at …", "past …", "(on the right)") must never decide the street or the turn.
 */
final class NavText {
    private NavText() {}

    private static final Pattern PAREN = Pattern.compile("\\s*\\([^)]*\\)");
    private static final Pattern DIST = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s?(m|km|ft|mi)\\b\\s*[·•\\-–—]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIST_ANY = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(m|km)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONTO = Pattern.compile(".*\\bonto\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOWARD = Pattern.compile(".*\\btowards?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON = Pattern.compile("^(?:continue|stay|keep going|go straight|head \\w+)\\s+(?:on|along)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANDMARK = Pattern.compile("\\s+(?:after|past|before|at|near|by)\\s+.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIR = Pattern.compile(
            "\\b(sharp|slightly|slight|keep|bear|turn|fork|take the ramp|take the exit|exit|ramp|merge)\\b(?:\\s+\\w+){0,3}?\\s+(left|right)\\b", Pattern.CASE_INSENSITIVE);

    /** Maps uses no-break spaces (U+00A0, U+202F "10:52\u202fpm"); Java's \\s doesn't match them. */
    static String spaces(String s) { return s == null ? "" : s.replaceAll("[\u00A0\u2007\u202F]", " "); }

    /** "Arrive 10:52 pm" -> "2252" (24 h HHMM for the dash), or null. */
    static String hhmm(String arrive) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([ap])?").matcher(spaces(arrive).toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
        if ("p".equals(m.group(3)) && h < 12) h += 12;
        if ("a".equals(m.group(3)) && h == 12) h = 0;
        return String.format(Locale.US, "%02d%02d", h, mi);
    }

    /** The instruction without the leading distance and without parenthetical asides. */
    static String clean(String instruction) {
        instruction = spaces(instruction);
        return PAREN.matcher(DIST.matcher(instruction).replaceFirst("")).replaceAll("").trim();
    }

    /** Distance to the next manoeuvre as text ("120 m"), or "". */
    static String turnDistance(String all) {
        Matcher d = DIST_ANY.matcher(all);
        return d.find() ? d.group(1).replace(',', '.') + " " + d.group(2).toLowerCase(Locale.ROOT) : "";
    }

    /** The road the rider is heading for ("onto X" wins, then "toward X", then "Continue on X"). */
    static String street(String instruction) {
        String s = clean(instruction);
        for (Pattern p : new Pattern[]{ONTO, TOWARD, ON}) {
            Matcher m = p.matcher(s);
            if (m.matches()) return trimStreet(m.group(1));
        }
        return "";
    }

    private static String trimStreet(String s) {
        s = s.replaceAll("\\s+for\\s+\\d+(?:[.,]\\d+)?\\s?(?:m|km)\\b.*$", "");   // "Continue on X for 2 km"
        return s.replaceAll("[\\s,.;]+$", "").trim();
    }

    private static final Pattern MANOEUVRE = Pattern.compile(
            "^(head|turn|continue|keep|slight|sharp|bear|merge|take|exit|fork|make|go|stay|at the|enter|leave|arriv|your destination|you have)\\b.*", Pattern.CASE_INSENSITIVE);

    /**
     * What the dash band should name when there's no "onto/toward" road: the place Maps names instead
     * ("950 m · Cafe X" = the destination is the next point; "Arriving" + "at Cafe X").
     */
    static String place(String instruction, String text) {
        String c = clean(instruction);
        String t = text == null ? "" : PAREN.matcher(text).replaceAll("").trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("at ")) return t.substring(3).trim();
        if (!c.isEmpty() && !MANOEUVRE.matcher(c).matches()) return c;
        return "";
    }

    /** Manoeuvre kind from the instruction text (landmarks and asides ignored). */
    static String kind(String instruction) {
        String l = clean(instruction).toLowerCase(Locale.ROOT);
        if (l.startsWith("arriv") || l.contains("destination") || l.startsWith("you have arrived")) return "arrive";
        if (l.contains("u-turn") || l.contains("make a u")) return "uturn";
        if (l.contains("roundabout") || l.contains("rotary")) return "roundabout";
        // only the manoeuvre phrase decides the side: cut "onto …", "toward …" and landmark clauses first
        String head = l.replaceAll("\\b(onto|toward|towards)\\b.*$", "");
        head = LANDMARK.matcher(head).replaceFirst("");
        Matcher m = DIR.matcher(head);
        String verb = null, side = null;
        if (m.find()) {
            verb = m.group(1); side = m.group(2);
            String phrase = m.group(0);                       // "turn slightly left", "turn sharp right"
            if (phrase.contains("sharp")) verb = "sharp"; else if (phrase.contains("slight")) verb = "slight";
        }
        if (head.startsWith("merge") || (verb != null && verb.equals("merge"))) return "merge";
        if (side == null) return "straight";
        if (verb.contains("ramp") || verb.contains("exit")) return "ramp_" + side;
        if (verb.equals("fork")) return "fork_" + side;
        if (verb.equals("sharp")) return "sharp_" + side;
        if (verb.startsWith("slight") || verb.equals("keep") || verb.equals("bear")) return "slight_" + side;
        return side;
    }

    /**
     * "Arrive 3:27 am" -> "12 min" / "1 h 5 min", relative to nowMs. Arrival up to 12 h in the past counts as
     * "0 min" (Maps rounds to the minute, so the arrival time is often already "now"); earlier than that
     * means tomorrow (a ride across midnight).
     */
    static String minutesLeft(String arrive, long nowMs) {
        if (arrive == null || arrive.isEmpty()) return "";
        for (String fmt : new String[]{"h:mm a", "H:mm"}) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(fmt, Locale.US);
                java.util.Date a = f.parse(spaces(arrive).toUpperCase(Locale.US).replace(".", "").replaceAll(".*?(\\d{1,2}:\\d{2}(\\s*[AP]M)?).*", "$1"));
                java.util.Calendar now = java.util.Calendar.getInstance(), at = java.util.Calendar.getInstance();
                now.setTimeInMillis(nowMs); at.setTime(a);
                at.set(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH));
                long diff = at.getTimeInMillis() - nowMs;
                if (diff < -12 * 3600_000L) diff += 24 * 3600_000L;
                long m = Math.max(0, Math.round(diff / 60000.0));
                return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
            } catch (Exception ignored) {}
        }
        return "";
    }
}
