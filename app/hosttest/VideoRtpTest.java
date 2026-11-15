package nav.dash.link;

import java.util.ArrayList;
import java.util.List;

/** Host-side test for the RTP packetiser (run: hosttest/run.sh). */
public class VideoRtpTest {
    static int fail = 0, n = 0;
    static void ok(String what, boolean c) { n++; if (!c) { fail++; System.out.println("FAIL " + what); } }
    static byte[] au(int... nalTypesAndSizes) {   // pairs: type, size
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < nalTypesAndSizes.length; i += 2) {
            o.write(0); o.write(0); o.write(0); o.write(1);
            int t = nalTypesAndSizes[i], sz = nalTypesAndSizes[i + 1];
            if (t == 7) { o.write(0x67); o.write(0x42); o.write(0xC0); o.write(0x29); for (int k = 4; k < sz; k++) o.write(0x11); }
            else { o.write(t == 5 ? 0x65 : t == 8 ? 0x68 : t == 6 ? 0x06 : 0x41); for (int k = 1; k < sz; k++) o.write(0x22); }
        }
        return o.toByteArray();
    }
    public static void main(String[] a) {
        List<byte[]> pk = new ArrayList<>();
        VideoRtp r = new VideoRtp(pk::add);
        r.accessUnit(au(7, 12, 8, 5), 0);                    // codec config only: cached, nothing sent
        ok("config alone sends nothing", pk.isEmpty());
        r.accessUnit(au(1, 500), 250);                       // P before any keyframe: gated
        ok("P-frame before first keyframe is held back", pk.isEmpty());
        r.accessUnit(au(6, 20, 5, 3000), 500);               // SEI + IDR (3000 B -> FU-A)
        ok("IDR sent", !pk.isEmpty());
        byte[] first = pk.get(0);
        ok("RTP v2, PT 96", (first[0] & 0xC0) == 0x80 && (first[1] & 0x7F) == 96);
        ok("FU-A indicator type 28", (first[12] & 0x1F) == 28);
        ok("FU start bit on first fragment", (first[13] & 0x80) != 0);
        ok("FU carries the bundle's first NAL type (SPS)", (first[13] & 0x1F) == 7);
        ok("SPS signature normalised to 42 00 29", first[14] == 0x42 && first[15] == 0x00 && first[16] == 0x29);
        byte[] last = pk.get(pk.size() - 1);
        ok("marker bit only on the frame's last packet", (last[1] & 0x80) != 0 && pk.subList(0, pk.size() - 1).stream().noneMatch(p -> (p[1] & 0x80) != 0));
        ok("FU end bit on last fragment", (last[13] & 0x40) != 0);
        ok("packets within 1380 + headers", pk.stream().allMatch(p -> p.length <= 12 + 1380));
        boolean seqOk = true;
        for (int i = 1; i < pk.size(); i++) seqOk &= (((pk.get(i)[2] & 0xFF) << 8 | (pk.get(i)[3] & 0xFF)) - ((pk.get(i - 1)[2] & 0xFF) << 8 | (pk.get(i - 1)[3] & 0xFF)) & 0xFFFF) == 1;
        ok("sequence numbers consecutive", seqOk);
        ok("SEI dropped (no single-NAL type 6)", pk.stream().noneMatch(p -> (p[12] & 0x1F) == 6));
        int before = pk.size();
        r.accessUnit(au(1, 400), 750);
        ok("P-frame after keyframe: one small packet", pk.size() == before + 1 && (pk.get(before)[12] & 0x1F) == 1 && (pk.get(before)[1] & 0x80) != 0);
        long ts1 = ((long) (pk.get(before)[4] & 0xFF) << 24) | ((pk.get(before)[5] & 0xFF) << 16) | ((pk.get(before)[6] & 0xFF) << 8) | (pk.get(before)[7] & 0xFF);
        long ts0 = ((long) (first[4] & 0xFF) << 24) | ((first[5] & 0xFF) << 16) | ((first[6] & 0xFF) << 8) | (first[7] & 0xFF);
        ok("90 kHz clock (250 ms = 22500)", ((ts1 - ts0) & 0xFFFFFFFFL) == 22500);
        r.waitForKeyframe(); int b2 = pk.size();
        r.accessUnit(au(1, 400), 1000);
        ok("after reconnect: P-frames held until the next keyframe", pk.size() == b2);
        r.accessUnit(au(5, 800), 1250);
        ok("next keyframe goes out with SPS/PPS bundled", pk.size() > b2);
        System.out.println(n - fail + "/" + n + " passed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
