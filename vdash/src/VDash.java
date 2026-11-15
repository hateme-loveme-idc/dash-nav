import javax.crypto.Cipher;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * Virtual Tripper Dash: a test stand-in for the Himalayan 450 dash (firmware 11.61 behaviour).
 * Plain Java SE, so it runs on the server JVM and on Android via app_process.
 *
 *  control in : UDP :ctrlPort (the bike uses 2000; tests use 2100)
 *  control out: UDP to <phone>:2002
 *  video in   : UDP :rtpPort  (bike 5000; tests 5100), RTP H.264
 *
 * Behaves like the dash: offers an RSA-1024 key after the auth request, checks the SSID inside the
 * encrypted key packet, acknowledges every decoded access unit (09 06 55 for IDR, 09 04 55 otherwise)
 * and drops projection if the phone stops sending its keep-alives or route cards.
 *
 * Writes: events.log (one line per notable packet), state.json (1 Hz), dash.h264 (the reassembled stream).
 * Optional live mirror: --mirror host:port sends 'S'+len+json and 'V'+len+annexB records over TCP.
 * Button test: create the file <out>/press containing a hex code (e.g. 22) to send a joystick event.
 */
public final class VDash {
    static String ssid = "RE_TEST_000000";
    static int ctrlPort = 2100, rtpPort = 5100, phonePort = 2002;
    static File out = new File(".");
    static String mirrorAddr = null;
    static long durationMs = 0;

    static DatagramSocket ctrl, rtp;
    static volatile InetAddress phone;
    static KeyPair kp;
    static PrintWriter log;
    static DataOutputStream mirror;
    static final Object mirrorLock = new Object();
    static FileOutputStream video;
    static final long t0 = System.currentTimeMillis();

    // ---- dash state (what the cluster would show) ----
    static final Map<String, Object> st = new LinkedHashMap<>();
    static volatile boolean authed, navStarted, projecting;
    static volatile long lastKeepAlive, lastCard, lastRtp, lastHeartbeat;
    static long ctrlPackets, cards, keepAlives, heartbeats, acksKeySent, acksFrameSent, phoneAckKey, phoneAckFrame;
    static long rtpPackets, rtpLost, frames, idrFrames, badFrames, spsOk, spsBad, projectionDrops, authRejects;
    static int lastSeq = -1;
    static String title = "", eta = "", phoneName = "", clock = "";
    static int totalDist, totalUnit, glyph = 0xFF, turnDist, turnUnit, battery = -1, cardProj;
    static final List<String> titles = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--ssid": ssid = a[++i]; break;
                case "--ctrl": ctrlPort = Integer.parseInt(a[++i]); break;
                case "--rtp": rtpPort = Integer.parseInt(a[++i]); break;
                case "--phone-port": phonePort = Integer.parseInt(a[++i]); break;
                case "--out": out = new File(a[++i]); break;
                case "--mirror": mirrorAddr = a[++i]; break;
                case "--seconds": durationMs = Long.parseLong(a[++i]) * 1000; break;
                default: System.err.println("unknown arg " + a[i]); System.exit(2);
            }
        }
        out.mkdirs();
        log = new PrintWriter(new FileWriter(new File(out, "events.log"), false), true);
        video = new FileOutputStream(new File(out, "dash.h264"));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(1024); kp = g.generateKeyPair();
        ctrl = new DatagramSocket(null); ctrl.setReuseAddress(true); ctrl.bind(new InetSocketAddress(ctrlPort));
        rtp = new DatagramSocket(null); rtp.setReuseAddress(true); rtp.setReceiveBufferSize(1 << 20); rtp.bind(new InetSocketAddress(rtpPort));
        ev("up epoch=" + t0 + " ssid=" + ssid + " ctrl=:" + ctrlPort + " rtp=:" + rtpPort);
        if (mirrorAddr != null) connectMirror();

        Thread c = new Thread(VDash::ctrlLoop, "ctrl"); c.setDaemon(true); c.start();
        Thread r = new Thread(VDash::rtpLoop, "rtp"); r.setDaemon(true); r.start();
        long end = durationMs > 0 ? t0 + durationMs : Long.MAX_VALUE;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(250);
            watchdog();
            pressButton();
            if ((System.currentTimeMillis() - t0) % 1000 < 250) writeState();
        }
        writeState();
        ev("down");
        video.close();
        System.exit(0);
    }

    // ================= control =================
    static void ctrlLoop() {
        byte[] buf = new byte[65535];
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ctrl.receive(p);
                if (phone == null || !phone.equals(p.getAddress())) { phone = p.getAddress(); ev("phone at " + phone.getHostAddress()); }
                handle(Arrays.copyOf(p.getData(), p.getLength()));
            } catch (Exception e) { ev("ctrl error " + e); }
        }
    }

    /** Phone -> dash: len(2) segs(2) 00000000 02010005 "K1G " seq(1) TLVs. */
    static void handle(byte[] d) throws Exception {
        ctrlPackets++;
        if (d.length < 17 || d[12] != 'K' || d[13] != '1' || d[14] != 'G') { ev("bad frame " + hex(d, 20)); return; }
        int len = u16(d, 0);
        if (len != d.length) ev("length mismatch hdr=" + len + " actual=" + d.length);
        int segs = u16(d, 2) - 1;
        int i = 17, n = 0;
        List<int[]> tl = new ArrayList<>();
        Map<Integer, byte[]> f = new LinkedHashMap<>();
        while (i + 4 <= d.length) {                      // parse by length (lenient, like the dash)
            int t = d[i] & 0xFF, s = d[i + 1] & 0xFF, l = u16(d, i + 2);
            if (i + 4 + l > d.length) { ev("truncated TLV " + hx(t) + " " + hx(s)); break; }
            f.put((t << 8) | s, Arrays.copyOfRange(d, i + 4, i + 4 + l));
            i += 4 + l; n++;
        }
        if (i != d.length) ev("trailing bytes " + (d.length - i));
        if (n != segs) { String k = (d.length) + "/" + segs + "/" + n; if (countNotes.add(k)) ev("note: header says " + segs + " TLVs, packet carries " + n + " (len " + d.length + "; logged once)"); }

        if (f.containsKey(0x0804)) { ev("auth request"); sendKey(); return; }
        if (f.containsKey(0x0800)) { checkKey(f.get(0x0800)); return; }
        if (f.containsKey(0x060B)) { byte[] v = f.get(0x060B); phoneName = new String(v, 0, Math.max(0, v.length - 1), StandardCharsets.UTF_8); ev("phone name '" + phoneName + "'"); }
        if (f.containsKey(0x0606)) { byte[] v = f.get(0x0606); clock = String.format("%02d:%02d:%02d", v[0], v[1], v[2]); }
        if (f.containsKey(0x0556) && f.size() == 1) {
            int v = f.get(0x0556)[0] & 0xFF;
            if (v == 0x55) { keepAlives++; lastKeepAlive = now(); if (navStarted && !projecting && authed) { projecting = true; ev("projection ON"); } }
            else { ev("projection stop"); projecting = false; }
        }
        if (f.containsKey(0x0605) && f.size() == 1 && (f.get(0x0605)[0] & 0xFF) == 0xAA) { ev("projection off"); projecting = false; }
        if (f.containsKey(0x0680) && f.size() == 1) {
            int v = f.get(0x0680)[0] & 0xFF;
            if (v == 0x0B) { navStarted = true; ev("nav start"); } else ev("button echo " + hx(v));
        }
        if (f.containsKey(0x0611)) phoneAckKey++;
        if (f.containsKey(0x0612)) phoneAckFrame++;
        if (f.containsKey(0x0501)) {                           // route card
            if (!authed) ev("route card before auth!");
            cards++; lastCard = now();
            byte[] tv = f.get(0x0501);
            int z = 0; while (z < tv.length && tv[z] != 0) z++;
            String nt = new String(tv, 0, z, StandardCharsets.UTF_8);
            if (!nt.equals(title)) { title = nt; titles.add(nt); }
            totalDist = u16(f.get(0x0509), 0); totalUnit = f.get(0x0546)[0] & 0xFF;
            eta = new String(f.get(0x0508), StandardCharsets.US_ASCII);
            cardProj = f.containsKey(0x0605) ? f.get(0x0605)[0] & 0xFF : -1;
            int g = f.get(0x0502)[0] & 0xFF; if (!f.containsKey(0x0504)) glyph = g;
            String sig = title + "|" + dist(totalDist, totalUnit) + "|" + eta;
            if (!sig.equals(lastCardSig)) { lastCardSig = sig; ev("card title='" + title + "' total=" + dist(totalDist, totalUnit) + " eta=" + eta + " proj=" + hx(cardProj)); }
        }
        if (f.containsKey(0x0504)) {                           // active nav (dash turn circle)
            glyph = f.get(0x0502)[0] & 0xFF; turnDist = u16(f.get(0x0504), 0); turnUnit = f.get(0x0506)[0] & 0xFF;
            String sig = hx(glyph) + "|" + dist(turnDist, turnUnit);
            if (!sig.equals(lastTurnSig)) { lastTurnSig = sig; ev("turn circle glyph=" + hx(glyph) + " in " + dist(turnDist, turnUnit)); }
        }
        if (f.containsKey(0x0522)) {                           // caller card
            byte[] v = f.get(0x0522); int z = 0; while (z < v.length && v[z] != 0) z++;
            String c = new String(v, 0, z, StandardCharsets.UTF_8);
            if (!c.equals(caller)) { caller = c; ev(c.isEmpty() ? "caller card cleared" : "caller card '" + c + "'"); }
            callerSeenAt = now();
        }
        if (f.containsKey(0x0517)) { boolean on = (f.get(0x0517)[0] & 0xFF) == 0x55; if (on != musicOn) { musicOn = on; ev("music widget " + (on ? "ON" : "OFF")); } }
        if (f.containsKey(0x0519)) { boolean p = (f.get(0x0519)[0] & 0xFF) == 0x55; if (p != musicPlaying) { musicPlaying = p; ev(p ? "music playing" : "music paused"); } }
        if (f.containsKey(0x050D)) {
            String[] parts = new String(f.get(0x050D), StandardCharsets.ISO_8859_1).split("\u0000", -1);
            String np = String.join(" | ", parts);
            if (!np.equals(nowPlaying)) { nowPlaying = np; ev("now playing: " + np); }
        }
        if (f.containsKey(0x0604) && f.containsKey(0x0610)) {   // heartbeat
            heartbeats++; lastHeartbeat = now(); battery = (f.get(0x0604)[0] & 0xFF) - 100;
        }
    }
    static String lastCardSig = "", lastTurnSig = "";
    static final Set<String> countNotes = new HashSet<>();
    static String caller = "", nowPlaying = ""; static boolean musicOn, musicPlaying; static long callerSeenAt;

    static void sendKey() throws Exception {
        authed = false;
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] mod = strip(pub.getModulus().toByteArray()), exp = strip(pub.getPublicExponent().toByteArray());
        reply(tlv(0x07, 0x00, mod));
        reply(tlv(0x07, 0x03, exp));
        ev("sent RSA key (" + mod.length * 8 + " bit)");
    }

    static void checkKey(byte[] block) throws Exception {
        if (block.length != 128) { ev("key packet wrong size " + block.length); reply(tlv(0x07, 0x01, new byte[]{0})); authRejects++; return; }
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding"); c.init(Cipher.DECRYPT_MODE, kp.getPrivate());
        byte[] plain;
        try { plain = c.doFinal(block); } catch (Exception e) { ev("key decrypt failed " + e); reply(tlv(0x07, 0x01, new byte[]{0})); authRejects++; return; }
        byte[] want = ssid.getBytes(StandardCharsets.UTF_8);
        boolean ok = plain.length == want.length + 32 && Arrays.equals(Arrays.copyOf(plain, want.length), want);
        reply(tlv(0x07, 0x01, new byte[]{(byte) (ok ? 1 : 0)}));
        if (ok) { authed = true; ev("handshake OK (ssid matches, 256-bit session key)"); }
        else { authRejects++; ev("handshake REJECTED: got '" + new String(plain, 0, Math.max(0, plain.length - 32), StandardCharsets.UTF_8) + "'"); }
    }

    // ================= video =================
    static ByteArrayOutputStream au = new ByteArrayOutputStream();
    static ByteArrayOutputStream fu = null;
    static long auTs = -1;
    static boolean auBroken, firstShown, mirrorKeyed;
    static long nonKeyStarts;

    static void rtpLoop() {
        byte[] buf = new byte[4096];
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rtp.receive(p);
                rtpPacket(Arrays.copyOf(p.getData(), p.getLength()));
            } catch (Exception e) { ev("rtp error " + e); }
        }
    }

    static void rtpPacket(byte[] p) throws Exception {
        rtpPackets++; lastRtp = now();
        if (p.length < 13 || (p[0] & 0xC0) != 0x80) { ev("bad RTP header"); return; }
        int pt = p[1] & 0x7F; boolean marker = (p[1] & 0x80) != 0;
        int seq = u16(p, 2); long ts = ((p[4] & 0xFFL) << 24) | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        if (pt != 96) ev("unexpected payload type " + pt);
        if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xFFFF)) { rtpLost += (seq - lastSeq - 1) & 0xFFFF; auBroken = true; }
        lastSeq = seq;
        if (auTs != ts) { if (au.size() > 0) { ev("frame without marker bit"); badFrames++; } au.reset(); fu = null; auTs = ts; auBroken = false; }
        int type = p[12] & 0x1F;
        if (type == 28) {                                       // FU-A
            boolean start = (p[13] & 0x80) != 0, end = (p[13] & 0x40) != 0;
            if (start) { fu = new ByteArrayOutputStream(); fu.write((p[12] & 0xE0) | (p[13] & 0x1F)); }
            if (fu == null) { auBroken = true; return; }
            fu.write(p, 14, p.length - 14);
            if (end) { nal(fu.toByteArray()); fu = null; }
        } else if (type >= 1 && type <= 23) nal(Arrays.copyOfRange(p, 12, p.length));
        else ev("unsupported RTP NAL type " + type);
        if (marker) { frameDone(au.toByteArray(), auBroken); au.reset(); auTs = -1; }
    }

    /** A packet's NAL may itself carry SPS+PPS+IDR joined by start codes (the dash's expected bundling). */
    static void nal(byte[] n) throws IOException { au.write(new byte[]{0, 0, 0, 1}); au.write(n); }

    static void frameDone(byte[] annexB, boolean broken) throws IOException {
        boolean idr = false, sps = false;
        for (byte[] n : split(annexB)) {
            int t = n[0] & 0x1F;
            if (t == 5) idr = true;
            if (t == 7) { sps = true; if (n.length > 3 && (n[1] & 0xFF) == 0x42 && n[2] == 0 && (n[3] & 0xFF) == 0x29) spsOk++; else { spsBad++; ev("SPS signature " + hex(Arrays.copyOf(n, 4), 4) + " (dash wants 67 42 00 29)"); } }
            if (t == 6 || t == 9) ev("dash-unfriendly NAL type " + t);
        }
        if (idr && !sps) ev("IDR without SPS/PPS in front");
        if (broken) { badFrames++; return; }                    // a real dash would not decode it: no ack
        frames++; if (idr) idrFrames++;
        video.write(annexB);
        if (!projecting) { firstShown = false; return; }        // the real dash ignores video outside projection
        if (!firstShown) {                                      // what the dash shows first must be a keyframe
            firstShown = true;
            if (!idr) { nonKeyStarts++; ev("stream started on a P-frame (dash would show garbage until the next keyframe)"); }
        }
        if (idr) { reply(tlv(0x09, 0x06, new byte[]{0x55})); acksKeySent++; }
        else { reply(tlv(0x09, 0x04, new byte[]{0x55})); acksFrameSent++; }
        if (idr) mirrorKeyed = true;
        if (mirrorKeyed) mirror('V', annexB);
    }

    // ================= behaviour checks =================
    static void watchdog() {
        long t = now();
        if (projecting && (t - lastKeepAlive > 2500 || t - lastCard > 4000)) {
            projecting = false; projectionDrops++;
            ev("projection DROPPED (keep-alive " + (t - lastKeepAlive) + " ms ago, route card " + (t - lastCard) + " ms ago)");
        }
    }

    static void pressButton() {
        File f = new File(out, "press");
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            int code = Integer.parseInt(r.readLine().trim(), 16);
            reply(tlv(0x09, 0x00, new byte[]{0x01, (byte) code}));
            ev("button pressed " + hx(code));
        } catch (Exception e) { ev("press failed " + e); }
        f.delete();
    }

    // ================= output =================
    static void writeState() {
        st.put("uptime_s", (now() - t0) / 1000);
        st.put("phone", phone == null ? "" : phone.getHostAddress());
        st.put("phone_name", phoneName); st.put("clock", clock);
        st.put("authed", authed); st.put("nav_started", navStarted); st.put("projecting", projecting);
        st.put("title", title); st.put("total", dist(totalDist, totalUnit)); st.put("eta", eta.length() == 4 ? eta.substring(0, 2) + ":" + eta.substring(2) : eta);
        st.put("glyph", glyph); st.put("turn", dist(turnDist, turnUnit)); st.put("battery", battery); st.put("card_proj", hx(cardProj));
        st.put("ctrl_packets", ctrlPackets); st.put("route_cards", cards); st.put("keepalives", keepAlives); st.put("heartbeats", heartbeats);
        st.put("rtp_packets", rtpPackets); st.put("rtp_lost", rtpLost); st.put("frames", frames); st.put("idr", idrFrames); st.put("bad_frames", badFrames);
        st.put("sps_ok", spsOk); st.put("sps_bad", spsBad);
        st.put("acks_key_sent", acksKeySent); st.put("acks_frame_sent", acksFrameSent);
        st.put("phone_ack_key", phoneAckKey); st.put("phone_ack_frame", phoneAckFrame);
        st.put("projection_drops", projectionDrops); st.put("non_key_starts", nonKeyStarts); st.put("auth_rejects", authRejects);
        st.put("titles_seen", titles.size());
        st.put("caller", caller); st.put("music_on", musicOn); st.put("music_playing", musicPlaying); st.put("now_playing", nowPlaying);
        String j = json(st);
        try (FileWriter w = new FileWriter(new File(out, "state.json.tmp"))) { w.write(j); } catch (IOException e) { return; }
        new File(out, "state.json.tmp").renameTo(new File(out, "state.json"));
        mirror('S', j.getBytes(StandardCharsets.UTF_8));
    }

    static void ev(String s) {
        String line = String.format("%8.3f %s", (now() - t0) / 1000.0, s);
        log.println(line); System.out.println(line);
        mirror('E', line.getBytes(StandardCharsets.UTF_8));
    }

    static void connectMirror() {
        String[] hp = mirrorAddr.split(":");
        try {
            Socket s = new Socket(); s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 3000); s.setTcpNoDelay(true);
            mirror = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            ev("mirror connected " + mirrorAddr);
        } catch (Exception e) { System.out.println("mirror unavailable: " + e); }
    }

    static void mirror(char kind, byte[] data) {
        synchronized (mirrorLock) {
            if (mirror == null) return;
            try { mirror.writeByte(kind); mirror.writeInt(data.length); mirror.write(data); mirror.flush(); }
            catch (IOException e) { mirror = null; System.out.println("mirror lost"); }
        }
    }

    // ================= helpers =================
    /** Dash -> phone: len(2) segs(2) 4 bytes, TLVs. */
    static void reply(byte[] tlvBytes) {
        InetAddress ph = phone; if (ph == null) return;
        byte[] p = new byte[8 + tlvBytes.length];
        p[0] = (byte) (p.length >> 8); p[1] = (byte) p.length; p[3] = 1;
        System.arraycopy(tlvBytes, 0, p, 8, tlvBytes.length);
        try { ctrl.send(new DatagramPacket(p, p.length, ph, phonePort)); } catch (IOException e) { System.out.println("reply: " + e); }
    }
    static byte[] tlv(int t, int s, byte[] v) {
        byte[] o = new byte[4 + v.length]; o[0] = (byte) t; o[1] = (byte) s; o[2] = (byte) (v.length >> 8); o[3] = (byte) v.length;
        System.arraycopy(v, 0, o, 4, v.length); return o;
    }
    static byte[] strip(byte[] b) { return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b; }
    static int u16(byte[] b, int o) { return b == null || b.length < o + 2 ? -1 : ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }
    static long now() { return System.currentTimeMillis(); }
    static String hx(int v) { return v < 0 ? "--" : String.format("%02X", v); }
    static String dist(int v, int unit) { return unit == 0x10 ? String.format("%.1f km", v / 10.0) : unit == 0x30 ? v + " m" : v + "?" + hx(unit); }
    static String hex(byte[] b, int max) { StringBuilder s = new StringBuilder(); for (int i = 0; i < Math.min(b.length, max); i++) s.append(String.format("%02X ", b[i])); return s.toString().trim(); }

    static List<byte[]> split(byte[] d) {
        List<byte[]> nals = new ArrayList<>(); int start = -1, i = 0;
        while (i + 3 < d.length + 1) {
            if (i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) { if (start >= 0) nals.add(Arrays.copyOfRange(d, start, i)); i += 4; start = i; }
            else if (i + 2 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) { if (start >= 0) nals.add(Arrays.copyOfRange(d, start, i)); i += 3; start = i; }
            else i++;
            if (i >= d.length) break;
        }
        if (start >= 0 && start < d.length) nals.add(Arrays.copyOfRange(d, start, d.length));
        nals.removeIf(n -> n.length == 0);
        return nals;
    }

    static String json(Map<String, Object> m) {
        StringBuilder s = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (s.length() > 1) s.append(',');
            s.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String) s.append('"').append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"'); else s.append(v);
        }
        return s.append('}').toString();
    }
}
