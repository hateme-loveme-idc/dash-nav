package nav.dash.link;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * H.264 (Annex-B from MediaCodec) -> RTP packets the Tripper Dash accepts.
 *  - SPS/PPS are cached and sent bundled in front of every IDR (start codes between them).
 *  - SPS profile signature normalised to 42 00 29 (Baseline, constraint byte 00, level 4.1): the dash
 *    only leaves its loading screen for that exact signature.
 *  - SEI (6) and AUD (9) units are dropped; no STAP-A aggregation; FU-A for units over 1380 bytes;
 *    marker bit on the last packet of each frame; 90 kHz clock; payload type 96.
 */
public final class VideoRtp {
    public interface Sink { void send(byte[] rtpPacket); }

    private static final int MAX_PAYLOAD = 1380, PT = 96;
    private static final byte[] SC = {0, 0, 0, 1};
    private final Sink sink;
    private final SecureRandom rnd = new SecureRandom();
    private int seq = rnd.nextInt(0xFFFF);
    private final int ssrc = rnd.nextInt();
    private final long tsBase = rnd.nextInt() & 0xFFFFFFFFL;
    private byte[] sps, pps;
    public volatile long frames, keyframes, packets, bytes;
    private volatile boolean waitKey = true;

    /** After (re)connecting: send nothing until the next keyframe, so the dash never starts on P-frames. */
    public void waitForKeyframe() { waitKey = true; }

    public VideoRtp(Sink sink) { this.sink = sink; }

    /** Feed one encoder output buffer (one access unit, Annex-B). */
    public void accessUnit(byte[] annexB, long wallClockMs) {
        List<byte[]> out = new ArrayList<>();
        boolean idr = false;
        for (byte[] nal : split(annexB)) {
            if (nal.length == 0) continue;
            int type = nal[0] & 0x1F;
            if (type == 7) sps = normaliseSps(nal);
            else if (type == 8) pps = nal;
            else if (type == 5) { out.add(sps != null && pps != null ? concat(sps, SC, pps, SC, nal) : nal); keyframes++; idr = true; }
            else if (type == 6 || type == 9) { /* dropped */ }
            else if ((type >= 1 && type <= 4) || (type >= 10 && type <= 12)) out.add(nal);
        }
        if (out.isEmpty()) return;
        if (waitKey) { if (!idr) return; waitKey = false; }
        long ts = (tsBase + wallClockMs * 90L) & 0xFFFFFFFFL;
        for (int i = 0; i < out.size(); i++) send(out.get(i), i == out.size() - 1, ts);
        frames++;
    }

    static byte[] normaliseSps(byte[] s) {
        if (s.length >= 4 && (s[0] & 0x1F) == 7 && s[1] == 0x42 && s[3] == 0x29) { s = s.clone(); s[2] = 0; }
        return s;
    }

    private void send(byte[] nal, boolean endOfFrame, long ts) {
        if (nal.length <= MAX_PAYLOAD) { emit(nal, 0, nal.length, null, endOfFrame, ts); return; }
        int type = nal[0] & 0x1F;
        byte ind = (byte) ((nal[0] & 0xE0) | 28);
        int off = 1; boolean first = true;
        while (off < nal.length) {
            int len = Math.min(MAX_PAYLOAD - 2, nal.length - off);
            boolean last = off + len >= nal.length;
            byte hdr = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | type);
            emit(nal, off, len, new byte[]{ind, hdr}, last && endOfFrame, ts);
            off += len; first = false;
        }
    }

    private void emit(byte[] src, int off, int len, byte[] fuHeader, boolean marker, long ts) {
        int h = fuHeader == null ? 0 : 2;
        byte[] p = new byte[12 + h + len];
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marker ? 0x80 : 0) | PT);
        p[2] = (byte) (seq >> 8); p[3] = (byte) seq;
        p[4] = (byte) (ts >> 24); p[5] = (byte) (ts >> 16); p[6] = (byte) (ts >> 8); p[7] = (byte) ts;
        p[8] = (byte) (ssrc >> 24); p[9] = (byte) (ssrc >> 16); p[10] = (byte) (ssrc >> 8); p[11] = (byte) ssrc;
        if (fuHeader != null) { p[12] = fuHeader[0]; p[13] = fuHeader[1]; }
        System.arraycopy(src, off, p, 12 + h, len);
        seq = (seq + 1) & 0xFFFF;
        packets++; bytes += p.length;
        sink.send(p);
    }

    /** Split on 4- or 3-byte start codes. */
    static List<byte[]> split(byte[] d) {
        List<byte[]> nals = new ArrayList<>();
        int start = -1, i = 0;
        while (i < d.length) {
            boolean sc4 = i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1;
            boolean sc3 = !sc4 && i + 2 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1;
            if (sc4 || sc3) {
                if (start >= 0) nals.add(java.util.Arrays.copyOfRange(d, start, i));
                i += sc4 ? 4 : 3; start = i;
            } else i++;
        }
        if (start >= 0 && start < d.length) nals.add(java.util.Arrays.copyOfRange(d, start, d.length));
        return nals;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n]; int k = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, o, k, p.length); k += p.length; }
        return o;
    }
}
