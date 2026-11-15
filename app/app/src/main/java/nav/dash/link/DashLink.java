package nav.dash.link;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.BatteryManager;
import android.os.PatternMatcher;
import android.util.Log;

import java.math.BigInteger;
import java.net.*;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.crypto.Cipher;

/**
 * Connection to the Royal Enfield Tripper Dash (Himalayan 450, firmware 11.61+).
 *
 *  Wi-Fi: the dash is an access point "RE_xxxx_yymmdd", factory passphrase 12345678, dash at 192.168.1.1.
 *  Control: UDP, phone :2000 -> broadcast :2000; dash -> phone :2002. Video: RTP/H.264 -> dash :5000.
 *  Session: start burst -> RSA public key (07 00 modulus, 07 03 exponent) -> we send RSA(SSID + AES-256 key)
 *  -> 07 01 01 accepted -> navigation entry -> stream (projection keep-alive 4 Hz, route card 1 Hz,
 *  status 1 Hz) while answering the dash's per-frame "decoded" notices.
 *
 * Test mode talks the same protocol to a virtual dash at a configurable address instead.
 */
public final class DashLink {
    public enum State { IDLE, WIFI, CONNECTING, AUTH, READY, STREAMING, ERROR }

    public interface Listener {
        void onState(State s, String detail);
        void onButton(int code);
        /** The rider tapped Cancel on Android's Wi-Fi prompt: treat as Stop. */
        default void onUserCancelled() {}
        /** Remember this dash (name + access-point MAC) for instant reconnects next time. */
        default void onDashLearned(String ssid, String bssid) {}
    }

    public static final class Config {
        public boolean testMode = false;
        public String ssidPrefix = "RE_", passphrase = "12345678";
        public String testHost = "127.0.0.1", testSsid = "RE_TEST_000000";
        public int testCtrlPort = 2100, testRtpPort = 5100;
        public String phoneName = "Dash Nav";
        public boolean dashTurnCircle = false;          // Option B: our card shows the turn, dash circle off
        public String knownSsid = "", knownBssid = ""; // the dash joined last time (approved: no prompt)
    }

    static final String TAG = "DashNavLink";
    static final int CTRL_PORT = 2000, RX_PORT = 2002, RTP_PORT = 5000;
    static final String DASH_IP = "192.168.1.1", BROADCAST = "192.168.1.255";
    static final long AUTH_TIMEOUT_MS = 15_000, WATCHDOG_MS = 6_000;

    private final Context ctx;
    private final Config cfg;
    private final Listener listener;
    private final Supplier<DashProtocol.NavFields> nav;

    private volatile State state = State.IDLE;
    private volatile boolean wanted;
    private ConnectivityManager.NetworkCallback wifiCb;
    private volatile Network network;
    private volatile String ssid;
    private DatagramSocket tx, rx, rtp;
    private InetSocketAddress ctrlDest, rtpDest;
    private final AtomicInteger seq = new AtomicInteger();
    private Thread sessionThread, rxThread;

    // handshake
    private volatile BigInteger modulus, exponent;
    private volatile boolean keySent, authed;
    private int authAttempts;
    // health
    private volatile long lastRxMs, firstAckMs;
    public volatile long acksKey, acksFrame, txPackets;
    private volatile boolean netBlocked;
    // caller card + music widget
    private volatile String caller; private boolean callShown;
    private volatile DashProtocol.Media media; private String mediaKey; private long mediaSentAt;
    public void setCaller(String name) { caller = name; }
    private volatile boolean gpsFix = true; private volatile int heading;
    /** Phone GPS state + heading for the dash's status icons and its compass view. */
    public void setGps(boolean fix, int headingDeg) { gpsFix = fix; heading = headingDeg; }
    public void setMedia(DashProtocol.Media m) { media = m; }
    static final String NET_BLOCKED = "Android is blocking Dash Nav's network. App info → Mobile data & Wi-Fi → allow network access";

    public DashLink(Context ctx, Config cfg, Supplier<DashProtocol.NavFields> nav, Listener listener) {
        this.ctx = ctx.getApplicationContext(); this.cfg = cfg; this.nav = nav; this.listener = listener;
    }

    public State state() { return state; }
    public boolean streaming() { return state == State.STREAMING; }

    // ================= lifecycle =================
    public synchronized void start() {
        if (wanted) return;
        wanted = true;
        if (cfg.testMode) { ssid = cfg.testSsid; startSession(); }
        else joinWifi();
    }

    public synchronized void stop() {
        wanted = false;
        try { if (tx != null) { send(DashProtocol.projectionStop()); send(DashProtocol.projectionOff()); } } catch (Exception ignored) {}
        closeSockets();
        if (sessionThread != null) sessionThread.interrupt();
        if (wifiCb != null) { try { cm().unregisterNetworkCallback(wifiCb); } catch (Exception ignored) {} wifiCb = null; }
        network = null;
        set(State.IDLE, "stopped");
    }

    // ================= Wi-Fi =================
    private ConnectivityManager cm() { return ctx.getSystemService(ConnectivityManager.class); }

    private long requestedAt, lastScanAt; private int attempt;

    /**
     * Joining the dash Wi-Fi.
     *  - A dash we joined before (already approved by the rider, so Android asks nothing): short 10 s requests
     *    for that exact network, aimed at its access point (every 3rd request unpinned in case it changed).
     *    Repeating short requests makes Android rescan, which joins far faster than one long request.
     *  - First time: wait until a "RE_…" network is actually in range, then one 30 s request, which shows
     *    Android's "Connect to device?" picker once.
     *  - Cancel on that picker = Stop. Wi-Fi switched off = say so and wait.
     */
    private void joinWifi() {
        if (!wanted || wifiCb != null) return;
        android.net.wifi.WifiManager wm = ctx.getSystemService(android.net.wifi.WifiManager.class);
        if (!wm.isWifiEnabled()) {
            set(State.WIFI, "turn on Wi-Fi to reach the dash");
            new Thread(() -> { sleep(2000); if (wanted && network == null) joinWifi(); }, "dash-wifi-off").start();
            return;
        }
        boolean known = !cfg.knownSsid.isEmpty();
        if (!known && Boolean.FALSE.equals(dashInRange())) {
            set(State.WIFI, "waiting for the bike: turn the ignition on");
            new Thread(() -> { sleep(3000); if (wanted && network == null) joinWifi(); }, "dash-scan").start();
            return;
        }
        attempt++;
        WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setWpa2Passphrase(cfg.passphrase);
        if (known) {
            sb.setSsid(cfg.knownSsid);
            if (attempt % 3 != 0 && !cfg.knownBssid.isEmpty())
                try { sb.setBssid(android.net.MacAddress.fromString(cfg.knownBssid)); } catch (Exception ignored) {}
            set(State.WIFI, "connecting to " + cfg.knownSsid + "…");
        } else {
            sb.setSsidPattern(new PatternMatcher(cfg.ssidPrefix, PatternMatcher.PATTERN_PREFIX));
            set(State.WIFI, "connecting to the dash Wi-Fi…");
        }
        final int timeout = known ? 10_000 : 30_000;
        NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(sb.build()).build();
        wifiCb = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                if (!(caps.getTransportInfo() instanceof WifiInfo)) return;
                WifiInfo wi = (WifiInfo) caps.getTransportInfo();
                String s = wi.getSSID();
                if (s == null) return;
                s = s.replace("\"", "");
                if (s.isEmpty() || s.equals("<unknown ssid>")) return;
                boolean fresh = network == null || !s.equals(ssid);
                network = n; ssid = s;
                if (fresh) {
                    Log.i(TAG, "dash Wi-Fi joined: " + s + " after " + (System.currentTimeMillis() - requestedAt) + " ms");
                    String bssid = wi.getBSSID();
                    if (bssid != null && !bssid.equals("02:00:00:00:00:00")) {
                        cfg.knownSsid = s; cfg.knownBssid = bssid;
                        try { listener.onDashLearned(s, bssid); } catch (Exception ignored) {}
                    }
                    startSession();
                }
            }
            @Override public void onLost(Network n) {
                Log.w(TAG, "dash Wi-Fi lost");
                network = null; closeSockets();
                if (wanted) set(State.WIFI, "dash Wi-Fi lost (bike off?): reconnecting…");
            }
            @Override public void onUnavailable() {
                long took = System.currentTimeMillis() - requestedAt;
                wifiCb = null;
                if (!wanted) return;
                if (took < timeout - 1500) {                                // closed before its timeout: the rider cancelled
                    Log.i(TAG, "rider cancelled the Wi-Fi prompt");
                    wanted = false; set(State.IDLE, "cancelled");
                    try { listener.onUserCancelled(); } catch (Exception ignored) {}
                    return;
                }
                set(State.WIFI, known ? "looking for " + cfg.knownSsid + " (bike on?)" : "the dash Wi-Fi didn't answer: trying again");
                new Thread(() -> { sleep(known ? 500 : 3000); if (wanted) joinWifi(); }, "dash-wifi-retry").start();
            }
        };
        requestedAt = System.currentTimeMillis();
        cm().requestNetwork(req, wifiCb, timeout);
    }

    /** TRUE/FALSE from the latest Wi-Fi scan; null if Android won't tell (location off) – then just ask. */
    private Boolean dashInRange() {
        try {
            if (!ctx.getSystemService(android.location.LocationManager.class).isLocationEnabled()) return null;
            android.net.wifi.WifiManager wm = ctx.getSystemService(android.net.wifi.WifiManager.class);
            boolean asked = false;
            if (System.currentTimeMillis() - lastScanAt > 30_000) { lastScanAt = System.currentTimeMillis(); asked = wm.startScan(); }
            long now = android.os.SystemClock.elapsedRealtime() * 1000;   // ScanResult.timestamp is µs since boot
            java.util.List<android.net.wifi.ScanResult> rs = wm.getScanResults();
            long newest = Long.MAX_VALUE; int match = 0;
            for (android.net.wifi.ScanResult r : rs) {
                newest = Math.min(newest, now - r.timestamp);
                if (r.SSID != null && r.SSID.startsWith(cfg.ssidPrefix)) { match++; if (now - r.timestamp < 180_000_000L) return true; }
            }
            Log.d(TAG, "scan: " + rs.size() + " networks, " + match + " named " + cfg.ssidPrefix + "…, newest " + (newest / 1_000_000) + " s old, rescan " + asked);
            return false;
        } catch (SecurityException e) { return null; }
    }

    // ================= session =================
    private synchronized void startSession() {
        if (sessionThread != null && sessionThread.isAlive()) sessionThread.interrupt();
        sessionThread = new Thread(this::session, "dash-session");
        sessionThread.start();
    }

    private void session() {
        int backoff = 2000;
        while (wanted && !Thread.currentThread().isInterrupted()) {
            try {
                openSockets();
                set(State.CONNECTING, "talking to the dash");
                modulus = exponent = null; keySent = authed = false; authAttempts = 0; lastRxMs = 0; firstAckMs = 0;
                rxThread = new Thread(this::receiveLoop, "dash-rx"); rxThread.start();

                set(State.AUTH, "secure handshake");
                for (byte[] p : DashProtocol.startBurst(cfg.phoneName)) { send(p); sleep(20); }
                long deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS, lastBurst = System.currentTimeMillis();
                while (!authed && System.currentTimeMillis() < deadline && wanted) {
                    sleep(100);
                    // a dash that is still booting (ignition just turned on) ignores early packets: ask again
                    if (modulus == null && System.currentTimeMillis() - lastBurst > 3000) {
                        for (byte[] p : DashProtocol.startBurst(cfg.phoneName)) { send(p); sleep(20); }
                        lastBurst = System.currentTimeMillis();
                    }
                }
                if (!authed) throw new IllegalStateException(lastRxMs == 0 ? "no answer from the dash (is the bike on?)"
                        : "the dash didn't accept the handshake (check the dash Wi-Fi name)");

                enterNav();
                mediaKey = null; callShown = false;               // fresh dash session: resend music/call state
                send(DashProtocol.projectionFrame()); sleep(60);   // projection on first, then video (keyframe first)
                set(State.STREAMING, "streaming to the dash");
                backoff = 2000;
                long n = 0;
                // 4 Hz loop: projection keep-alive every tick; route card + nav + status every 4th tick
                while (wanted && !Thread.currentThread().isInterrupted()) {
                    send(DashProtocol.projectionFrame());
                    if (n % 4 == 0) {
                        DashProtocol.NavFields f = navFields();
                        send(DashProtocol.routeCard(f, true));
                        if (cfg.dashTurnCircle) send(DashProtocol.activeNav(f));
                        send(status());
                        if (n % 120 == 0) send(DashProtocol.time());
                        String who = caller;
                        if (who != null) { send(DashProtocol.callNotify(who)); callShown = true; }
                        else if (callShown) { send(DashProtocol.callClear()); callShown = false; }
                        sendMedia();
                    }
                    if (firstAckMs > 0 && System.currentTimeMillis() - lastRxMs > WATCHDOG_MS)
                        throw new IllegalStateException("the dash stopped answering");
                    n++; sleep(250);
                }
            } catch (Exception e) {
                Log.w(TAG, "session: " + e.getMessage());
                closeSockets();
                if (!wanted) return;
                set(State.ERROR, netBlocked ? NET_BLOCKED : e.getMessage() + ": retrying");
                sleep(backoff); backoff = Math.min(backoff * 2, 15_000);
            }
        }
    }

    /** Music widget: on change, and every 5 s as a refresh. */
    private void sendMedia() {
        DashProtocol.Media m = media;
        long now = System.currentTimeMillis();
        if (m == null) {
            if (mediaKey != null) { send(DashProtocol.musicActive(false)); mediaKey = null; }
            return;
        }
        if (m.key().equals(mediaKey) && now - mediaSentAt < 5000) return;
        send(DashProtocol.musicActive(true)); send(DashProtocol.musicPlaying(m.playing)); send(DashProtocol.nowPlaying(m));
        mediaKey = m.key(); mediaSentAt = now;
    }

    private void enterNav() {
        DashProtocol.NavFields f = navFields();
        send(DashProtocol.navContext()); sleep(40);
        send(DashProtocol.emptyLists()); sleep(40);
        for (int i = 0; i < 4; i++) { send(DashProtocol.routeCard(f, false)); sleep(i < 1 ? 100 : 500); }
        send(DashProtocol.projectionFrame()); sleep(60);
        send(DashProtocol.navPlaceholder()); sleep(10);
        send(DashProtocol.navStart()); sleep(40);
        send(DashProtocol.routeCard(f, true));
        set(State.READY, "dash ready");
    }

    private DashProtocol.NavFields navFields() {
        DashProtocol.NavFields f = nav.get();
        if (!cfg.dashTurnCircle) { f.glyph = DashProtocol.GLYPH_NONE; f.turnDist = 0; }
        return f;
    }

    private byte[] status() {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lvl = b == null ? 50 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        boolean chg = b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return DashProtocol.heartbeat(lvl, chg, true, gpsFix, 25, heading);
    }

    // ================= receive =================
    private void receiveLoop() {
        byte[] buf = new byte[65535];
        DatagramSocket sock = rx;
        while (wanted && sock != null && !sock.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                lastRxMs = System.currentTimeMillis();
                for (K1g.Tlv t : K1g.parse(p.getData(), p.getLength())) handle(t);
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (wanted && !sock.isClosed()) Log.w(TAG, "rx: " + e.getMessage());
                return;
            }
        }
    }

    private void handle(K1g.Tlv t) {
        if (t.type == 0x07) {                                   // handshake
            if (t.sub == 0x00) modulus = new BigInteger(1, t.value);
            else if (t.sub == 0x03) exponent = new BigInteger(1, t.value);
            else if (t.sub == 0x01) {
                if (t.u8() == 0x01) { authed = true; Log.i(TAG, "handshake accepted"); }
                else if (++authAttempts <= 5) { Log.w(TAG, "handshake rejected: retrying"); modulus = exponent = null; keySent = false; send(DashProtocol.authRequest()); }
                return;
            }
            if (!keySent && modulus != null && exponent != null) { keySent = true; send(keyPacket()); }
        } else if (t.type == 0x09 && t.sub == 0x06 && t.u8() == 0x55) {   // keyframe decoded
            if (firstAckMs == 0) { firstAckMs = System.currentTimeMillis(); Log.i(TAG, "dash decoded our first keyframe"); }
            acksKey++; send(DashProtocol.ackKeyframe());
        } else if (t.type == 0x09 && t.sub == 0x04 && t.u8() == 0x55) {   // frame decoded
            acksFrame++; send(DashProtocol.ackFrame());
        } else if (t.type == 0x09 && t.sub == 0x00 && t.value.length > 0) {   // joystick / button
            int code = t.value[t.value.length - 1] & 0xFF;
            send(DashProtocol.buttonAck(code));
            if (code == 0x22) send(DashProtocol.projectionFrame());       // compass opened: GPS available
            try { listener.onButton(code); } catch (Exception ignored) {}
        }
    }

    private byte[] keyPacket() {
        try {
            byte[] aes = new byte[32]; new SecureRandom().nextBytes(aes);
            byte[] name = ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] payload = new byte[name.length + 32];
            System.arraycopy(name, 0, payload, 0, name.length); System.arraycopy(aes, 0, payload, name.length, 32);
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding"); c.init(Cipher.ENCRYPT_MODE, pk);
            return DashProtocol.authKey(c.doFinal(payload));
        } catch (Exception e) { throw new IllegalStateException("handshake crypto: " + e); }
    }

    // ================= sockets =================
    private synchronized void openSockets() throws Exception {
        closeSockets();
        Network n = network;
        tx = new DatagramSocket(null); tx.setReuseAddress(true); tx.setBroadcast(true);
        rx = new DatagramSocket(null); rx.setReuseAddress(true); rx.setSoTimeout(500);
        rtp = new DatagramSocket(null);
        if (n != null) { n.bindSocket(tx); n.bindSocket(rx); n.bindSocket(rtp); }
        tx.bind(new InetSocketAddress(CTRL_PORT));
        rx.bind(new InetSocketAddress(RX_PORT));
        rtp.bind(new InetSocketAddress(0));
        if (cfg.testMode) {
            ctrlDest = new InetSocketAddress(cfg.testHost, cfg.testCtrlPort);
            rtpDest = new InetSocketAddress(cfg.testHost, cfg.testRtpPort);
        } else {
            ctrlDest = new InetSocketAddress(BROADCAST, CTRL_PORT);
            rtpDest = new InetSocketAddress(DASH_IP, RTP_PORT);
        }
    }

    private synchronized void closeSockets() {
        for (DatagramSocket s : new DatagramSocket[]{tx, rx, rtp}) if (s != null) s.close();
        tx = rx = rtp = null;
    }

    void send(byte[] pkt) {
        DatagramSocket s = tx; InetSocketAddress d = ctrlDest;
        if (s == null || d == null) return;
        byte[] p = K1g.withSeq(pkt, seq.getAndIncrement());
        try { s.send(new DatagramPacket(p, p.length, d)); txPackets++; netBlocked = false; }
        catch (Exception e) {
            Log.d(TAG, "tx: " + e.getMessage());
            if (String.valueOf(e.getMessage()).contains("EPERM") && !netBlocked) {
                netBlocked = true;   // per-app firewall (some ROMs block new apps): tell the rider how to fix it
                set(State.ERROR, NET_BLOCKED);
            }
        }
    }

    /** Video path (called from the encoder thread). */
    public void sendRtp(byte[] pkt) {
        DatagramSocket s = rtp; InetSocketAddress d = rtpDest;
        if (s == null || d == null || state != State.STREAMING) return;
        try { s.send(new DatagramPacket(pkt, pkt.length, d)); } catch (Exception e) { Log.d(TAG, "rtp: " + e.getMessage()); }
    }

    private void set(State s, String detail) {
        state = s; Log.i(TAG, s + ": " + detail);
        try { listener.onState(s, detail); } catch (Exception ignored) {}
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
