package nav.dash.link;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tripper Dash control-packet framing ("K1G").
 *
 * Phone -> dash (big-endian):
 *   [0..1]  total length (including these two bytes)
 *   [2..3]  segment count = 1 (fixed header) + number of TLVs
 *   [4..7]  00 00 00 00
 *   [8..11] 02 01 00 05
 *   [12..15] "K1G "
 *   [16]    rolling sequence byte (set when sending)
 *   [17..]  TLVs: type(1) sub(1) length(2) value
 *
 * Dash -> phone: [0..1] length, [2..3] segment count, [4..7] ignored, TLVs from offset 8.
 */
public final class K1g {
    private K1g() {}

    static final byte[] FIXED = {0, 0, 0, 0, 0x02, 0x01, 0x00, 0x05, 0x4B, 0x31, 0x47, 0x20};

    /** One type/sub/value segment. */
    public static final class Tlv {
        public final int type, sub; public final byte[] value;
        public Tlv(int type, int sub, byte[] value) { this.type = type & 0xFF; this.sub = sub & 0xFF; this.value = value; }
        public int u8() { return value.length > 0 ? value[0] & 0xFF : -1; }
        @Override public String toString() { return String.format("%02X %02X [%s]", type, sub, hex(value, 24)); }
    }

    public static Tlv tlv(int type, int sub, int... bytes) {
        byte[] v = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) v[i] = (byte) bytes[i];
        return new Tlv(type, sub, v);
    }

    public static Tlv tlv(int type, int sub, byte[] value) { return new Tlv(type, sub, value); }

    public static Tlv u16(int type, int sub, int v) { return tlv(type, sub, (v >> 8) & 0xFF, v & 0xFF); }

    /** Build an outgoing packet (sequence byte left 0; {@link #withSeq} sets it). */
    public static byte[] build(Tlv... tlvs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int segs = 1 + tlvs.length;
        o.write(0); o.write(0);
        o.write((segs >> 8) & 0xFF); o.write(segs & 0xFF);
        o.write(FIXED, 0, FIXED.length);
        o.write(0);
        for (Tlv t : tlvs) {
            o.write(t.type); o.write(t.sub);
            o.write((t.value.length >> 8) & 0xFF); o.write(t.value.length & 0xFF);
            o.write(t.value, 0, t.value.length);
        }
        return fixLength(o.toByteArray());
    }

    static byte[] fixLength(byte[] b) { b[0] = (byte) ((b.length >> 8) & 0xFF); b[1] = (byte) (b.length & 0xFF); return b; }

    /** Copy with the rolling sequence byte (right after "K1G ") set. */
    public static byte[] withSeq(byte[] pkt, int seq) {
        byte[] out = pkt.clone();
        int k = indexOf(out, new byte[]{0x4B, 0x31, 0x47, 0x20}, false);
        if (k >= 0 && k + 4 < out.length) out[k + 4] = (byte) seq;
        return fixLength(out);
    }

    /** Parse a dash -> phone packet into TLVs. */
    public static List<Tlv> parse(byte[] d, int len) {
        List<Tlv> out = new ArrayList<>();
        if (len < 8) return out;
        int segs = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        int i = 8;
        for (int n = 0; n < segs && i + 4 <= len; n++) {
            int type = d[i] & 0xFF, sub = d[i + 1] & 0xFF, l = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            i += 4;
            int end = Math.min(i + l, len);
            byte[] v = new byte[end - i]; System.arraycopy(d, i, v, 0, v.length);
            out.add(new Tlv(type, sub, v));
            i = end;
        }
        return out;
    }

    public static byte[] fromHex(String h) {
        h = h.replace(" ", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    public static String hex(byte[] b, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) s.append(String.format("%02X", b[i])).append(i + 1 < b.length ? " " : "");
        if (b.length > max) s.append("…");
        return s.toString();
    }

    static int indexOf(byte[] hay, byte[] needle, boolean fromEnd) {
        int last = hay.length - needle.length;
        for (int n = 0; n <= last; n++) {
            int i = fromEnd ? last - n : n;
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }
}
