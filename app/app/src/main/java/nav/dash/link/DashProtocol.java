package nav.dash.link;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static nav.dash.link.K1g.*;

/**
 * The Tripper Dash control messages (firmware 11.61+). Values come from observing the dash and the
 * stock phone app; field meanings are noted where known.
 */
public final class DashProtocol {
    private DashProtocol() {}

    // ---- handshake ----
    /** Ask the dash for its RSA public key. */
    public static byte[] authRequest() { return fromHex("0016000200000000020100054b314720000804000101"); }

    /** RSA-encrypted (SSID + AES-256 session key); the ciphertext is always 128 bytes (RSA-1024). */
    public static byte[] authKey(byte[] cipher128) {
        if (cipher128.length != 128) throw new IllegalArgumentException("expected 128-byte RSA block");
        byte[] head = fromHex("0095000200000000020100054B3147200008000080");
        byte[] out = Arrays.copyOf(head, head.length + 128);
        System.arraycopy(cipher128, 0, out, head.length, 128);
        return out;
    }

    // ---- session start ----
    /** Sent right after the sockets open; the dash only offers its key after seeing this burst. */
    public static List<byte[]> startBurst(String phoneName) {
        return Arrays.asList(
                authRequest(),
                phoneName(phoneName),
                time(),
                fromHex("0016000200000000020100054b314720030557000155"),
                fromHex("0016000200000000020100054b3147200405560001aa"),
                fromHex("0016000200000000020100054b3147200506050001aa"),
                fromHex("0016000200000000020100054b3147200605170001aa"),
                fromHex("001d000200000000020100054b314720080a020008aa55000000000000"),
                fromHex("0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa"
                        + "0601000101054c000113052d00020000051b0001190521000132054d000132"));
    }

    /** Name shown on the dash's "connected to" screen. */
    public static byte[] phoneName(String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 200) raw = Arrays.copyOf(raw, 200);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] head = fromHex("0021000200000000020100054b314720");
        o.write(head, 0, head.length);
        o.write(0x01); o.write(0x06); o.write(0x0B); o.write(0x00); o.write(raw.length + 1);
        o.write(raw, 0, raw.length); o.write(0);
        return K1g.fixLength(o.toByteArray());
    }

    /** 06 06: clock (the dash shows the phone's time; re-sent every 30 s). */
    public static byte[] time() {
        Calendar c = Calendar.getInstance();
        return build(tlv(0x06, 0x06, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)));
    }

    // ---- navigation mode ----
    public static byte[] navContext() { return fromHex("0016000200000000020100054B31472000052E00011E"); }
    public static byte[] emptyLists() { return fromHex("002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100"); }
    /** Start navigation display: send once, after the first route cards. */
    public static byte[] navStart() { return fromHex("0016000200000000020100054B31472000068000010B"); }
    public static byte[] navPlaceholder() { return build(tlv(0x06, 0x0A, 0x00, 0x00)); }

    // ---- projection (video) control ----
    /** Projection keep-alive: repeat at the video frame rate (4 Hz). */
    public static byte[] projectionFrame() { return fromHex("0016000200000000020100054B314720000556000155"); }
    public static byte[] projectionStop() { return fromHex("0016000200000000020100054B3147200005560001AA"); }
    public static byte[] projectionOff() { return fromHex("0016000200000000020100054B3147200006050001AA"); }
    /** Reply to the dash's per-keyframe "decoded" notice (09 06 55): mandatory. */
    public static byte[] ackKeyframe() { return fromHex("0016000200000000020100054B314720000611000155"); }
    /** Reply to 09 04 55 (other frames). */
    public static byte[] ackFrame() { return fromHex("0016000200000000020100054B314720000612000155"); }
    /** Echo a joystick/button event back (06 80 <code>). */
    public static byte[] buttonAck(int code) { return build(tlv(0x06, 0x80, code)); }

    // ---- 1 Hz status heartbeat (phone battery, charging, signal, GPS, volumes, temperature, heading) ----
    private static final byte[] HEARTBEAT = fromHex(
            "0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa"
                    + "0601000101054c000113052d00020000051b0001190521000132054d000132");

    public static byte[] heartbeat(int batteryPct, boolean charging, boolean signal, boolean gps, int tempC, int headingDeg) {
        byte[] p = HEARTBEAT.clone();
        put1(p, 0x06, 0x04, Math.max(0, Math.min(100, batteryPct)) + 100);
        put1(p, 0x06, 0x0F, charging ? 0x55 : 0xAA);
        put1(p, 0x06, 0x01, signal ? 0x01 : 0x00);
        put1(p, 0x06, 0x03, gps ? 0x55 : 0xAA);
        put1(p, 0x06, 0x10, tempC + 40);
        int d = ((headingDeg % 360) + 360) % 360;
        int at = indexOf(p, new byte[]{0x05, 0x2D, 0x00, 0x02}, false);
        if (at >= 0) { p[at + 4] = (byte) (d >> 8); p[at + 5] = (byte) d; }
        return p;
    }

    // ---- route card (the gold band + the dash's own turn widget) ----
    /** Units for distance fields. */
    public static final int UNIT_KM_TENTHS = 0x10, UNIT_METERS = 0x30;
    /** "No maneuver" for the secondary slot; also used to keep the dash's own turn circle empty. */
    public static final int GLYPH_NONE = 0xFF;

    private static final byte[] CARD = fromHex(
            "007e001100000000020100054b31472025050100145461696c6c65206465204d617320647520477200"
                    + "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509"
                    + "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa");
    private static final byte[] CARD_PREFIX, CARD_SUFFIX;
    static {
        int magic = indexOf(CARD, new byte[]{0x4B, 0x31, 0x47, 0x20}, false);
        int seq = magic + 4;
        int titleLen = ((CARD[seq + 3] & 0xFF) << 8) | (CARD[seq + 4] & 0xFF);
        CARD_PREFIX = Arrays.copyOfRange(CARD, 0, seq);
        CARD_SUFFIX = Arrays.copyOfRange(CARD, seq + 5 + titleLen, CARD.length);
    }

    /** What the dash's own navigation fields should show. */
    public static final class NavFields {
        public String title = "";                 // left side of the gold band
        public int glyph = GLYPH_NONE;            // dash turn-circle glyph (NONE = ours on the video only)
        public int turnDist = 0, turnUnit = UNIT_METERS;
        public int totalDist = 0, totalUnit = UNIT_KM_TENTHS;   // right side of the gold band (flag + km)
        public String etaHHMM = null;             // 4 ASCII digits
    }

    /** Full route card; send before nav start, then ~1 Hz while streaming (the dash drops projection without it). */
    public static byte[] routeCard(NavFields f, boolean projectionOn) {
        byte[] title = f.title.getBytes(StandardCharsets.UTF_8);
        if (title.length > 60) title = Arrays.copyOf(title, 60);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(CARD_PREFIX, 0, CARD_PREFIX.length);
        o.write(0);
        o.write(0x05); o.write(0x01); o.write(0); o.write(title.length + 1);
        o.write(title, 0, title.length); o.write(0);
        o.write(CARD_SUFFIX, 0, CARD_SUFFIX.length);
        byte[] b = o.toByteArray();
        // all patched fields live after the title: search from the end so title text can't collide
        put1end(b, 0x06, 0x05, projectionOn ? 0x55 : 0xAA);
        put1end(b, 0x05, 0x02, f.glyph);
        put1end(b, 0x05, 0x06, f.turnUnit);           // (the card has no turn-distance field: that's activeNav 05 04)
        put1end(b, 0x05, 0x03, GLYPH_NONE);
        put2end(b, 0x05, 0x05, 0);
        put1end(b, 0x05, 0x07, UNIT_METERS);
        put2end(b, 0x05, 0x09, f.totalDist);
        put1end(b, 0x05, 0x46, f.totalUnit);
        if (f.etaHHMM != null && f.etaHHMM.length() == 4) {
            int m = indexOf(b, new byte[]{0x05, 0x08, 0x00, 0x04}, true);
            if (m >= 0) for (int i = 0; i < 4; i++) b[m + 4 + i] = (byte) f.etaHHMM.charAt(i);
        }
        return K1g.fixLength(b);
    }

    /** Live navigation numbers (~1 Hz while guiding): turn glyph + distance to turn + total remaining. */
    public static byte[] activeNav(NavFields f) {
        return build(
                tlv(0x05, 0x02, f.glyph), u16(0x05, 0x04, f.turnDist), tlv(0x05, 0x06, f.turnUnit),
                tlv(0x05, 0x03, GLYPH_NONE), u16(0x05, 0x05, 0), tlv(0x05, 0x07, UNIT_METERS),
                u16(0x05, 0x09, f.totalDist), tlv(0x05, 0x46, f.totalUnit),
                tlv(0x05, 0x0A, 0x55), tlv(0x06, 0x05, 0x55), tlv(0x06, 0x0D, 0xAA));
    }

    // ---- caller card (05 22) and music widget (05 17 / 05 19 / 05 0D): the dash draws these itself ----
    /** Incoming call: caller name, NUL-terminated (max 20 chars). Repeat ~1 Hz while ringing. */
    public static byte[] callNotify(String caller) {
        String c = caller.length() > 20 ? caller.substring(0, 20) : caller;
        byte[] n = c.getBytes(StandardCharsets.UTF_8);
        return build(tlv(0x05, 0x22, Arrays.copyOf(n, n.length + 1)));
    }
    /** Call over: empty caller card. */
    public static byte[] callClear() { return build(tlv(0x05, 0x22, 0x00)); }

    /** What's playing. */
    public static final class Media {
        public final String title, album, artist; public final boolean playing;
        public Media(String title, String album, String artist, boolean playing) {
            this.title = title == null ? "" : title; this.album = album == null ? "" : album; this.artist = artist == null ? "" : artist; this.playing = playing;
        }
        String key() { return title + "\u0000" + album + "\u0000" + artist + "\u0000" + playing; }
    }
    /** Music widget on/off (the dash shows now-playing only while this is on). */
    public static byte[] musicActive(boolean on) { return build(tlv(0x05, 0x17, on ? 0x55 : 0xAA)); }
    public static byte[] musicPlaying(boolean playing) { return build(tlv(0x05, 0x19, playing ? 0x55 : 0xAA)); }
    /** Now playing: title \0 album \0 artist, 19 single-byte chars each (the dash font is single-byte). */
    public static byte[] nowPlaying(Media m) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] t = field(m.title), a = field(m.album), r = field(m.artist);
        o.write(t, 0, t.length); o.write(0); o.write(a, 0, a.length); o.write(0); o.write(r, 0, r.length);
        return build(tlv(0x05, 0x0D, o.toByteArray()));
    }
    static byte[] field(String s) {
        String t = s.length() > 19 ? s.substring(0, 19) : s;
        byte[] b = new byte[t.length()];
        for (int i = 0; i < t.length(); i++) b[i] = (byte) Math.min(t.charAt(i), 0xFF);
        return b;
    }

    private static void put1(byte[] p, int t, int s, int v) {
        int at = indexOf(p, new byte[]{(byte) t, (byte) s, 0, 1}, false);
        if (at >= 0 && at + 4 < p.length) p[at + 4] = (byte) v;
    }
    private static void put1end(byte[] p, int t, int s, int v) {
        int at = indexOf(p, new byte[]{(byte) t, (byte) s, 0, 1}, true);
        if (at >= 0 && at + 4 < p.length) p[at + 4] = (byte) v;
    }
    private static void put2end(byte[] p, int t, int s, int v) {
        int at = indexOf(p, new byte[]{(byte) t, (byte) s, 0, 2}, true);
        if (at >= 0 && at + 5 < p.length) { p[at + 4] = (byte) ((v >> 8) & 0xFF); p[at + 5] = (byte) (v & 0xFF); }
    }
}
