package nav.dash;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Engine RPM from an ELM327-type OBD-II adapter on the bike's diagnostic port, over Bluetooth Low Energy (the
 * "iPhone-compatible" adapters) or classic Bluetooth (serial port profile, paired in Android's settings first).
 * Polls PID 01 0C as fast as the adapter answers (about 10 a second). Nothing is sent anywhere else.
 */
@SuppressLint("MissingPermission")   // callers only start it after BLUETOOTH_CONNECT is granted
final class ObdLink {
    private static final String TAG = "DashNavObd";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String[] INIT = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"};

    /** Latest engine speed, or -1; when it was read (elapsedRealtime). */
    volatile int rpm = -1;
    volatile long rpmAt;
    volatile String status = "Off";

    private final Context ctx;
    private final String address;
    private final boolean classic;
    private volatile boolean running;
    private Thread worker;

    // BLE state
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private final StringBuilder rxBuf = new StringBuilder();
    private final Object rxLock = new Object();
    private volatile boolean bleReady;

    ObdLink(Context ctx, String address, boolean classic) { this.ctx = ctx.getApplicationContext(); this.address = address; this.classic = classic; }

    /** Bike-data probe: extra requests run between RPM reads; replies keyed by request. */
    final java.util.concurrent.ConcurrentLinkedQueue<String> queries = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final java.util.concurrent.ConcurrentHashMap<String, String> answers = new java.util.concurrent.ConcurrentHashMap<>();
    boolean connected() { return status.startsWith("Live") || status.startsWith("Adapter connected"); }

    /** Fresh reading (under 2 s old), else -1. */
    int liveRpm() { return rpm >= 0 && SystemClock.elapsedRealtime() - rpmAt < 2000 ? rpm : -1; }

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "dash-obd"); worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        closeBle();
        status = "Off";
    }

    private void loop() {
        while (running) {
            try {
                if (classic) runClassic(); else runBle();
            } catch (InterruptedException e) { break; }
            catch (Exception e) { Log.w(TAG, "obd: " + e); status = "Adapter not reachable: retrying"; }
            closeBle(); rpm = -1;
            sleep(3000);
        }
    }

    // ---------------- classic Bluetooth (SPP) ----------------
    private void runClassic() throws Exception {
        BluetoothAdapter ad = ctx.getSystemService(BluetoothManager.class).getAdapter();
        if (ad == null || !ad.isEnabled()) { status = "Turn on Bluetooth"; return; }
        BluetoothDevice dev = ad.getRemoteDevice(address);
        status = "Connecting to the OBD adapter…";
        try (BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP)) {
            ad.cancelDiscovery(); s.connect();
            InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
            Io io = new Io() {
                public void send(String cmd) throws Exception { out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII)); out.flush(); }
                public String reply(long timeoutMs) throws Exception {
                    StringBuilder b = new StringBuilder(); long end = SystemClock.elapsedRealtime() + timeoutMs;
                    while (SystemClock.elapsedRealtime() < end && running) {
                        if (in.available() > 0) { int c = in.read(); if (c < 0) throw new java.io.IOException("closed"); if (c == '>') return b.toString(); b.append((char) c); }
                        else Thread.sleep(5);
                    }
                    return null;
                }
            };
            session(io);
        }
    }

    // ---------------- Bluetooth Low Energy ----------------
    private void runBle() throws Exception {
        BluetoothAdapter ad = ctx.getSystemService(BluetoothManager.class).getAdapter();
        if (ad == null || !ad.isEnabled()) { status = "Turn on Bluetooth"; return; }
        status = "Connecting to the OBD adapter…";
        bleReady = false;
        gatt = ad.getRemoteDevice(address).connectGatt(ctx, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int st, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) g.discoverServices();
                else if (state == BluetoothProfile.STATE_DISCONNECTED) bleReady = false;
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
                BluetoothGattCharacteristic[] pair = pickSerial(g);
                if (pair == null) { status = "This adapter has no serial service"; return; }
                tx = pair[1];
                g.setCharacteristicNotification(pair[0], true);
                BluetoothGattDescriptor d = pair[0].getDescriptor(CCCD);
                if (d != null) {
                    boolean ind = (pair[0].getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                    g.writeDescriptor(d, ind ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else bleReady = true;
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) { bleReady = true; }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
                synchronized (rxLock) { rxBuf.append(new String(v, StandardCharsets.US_ASCII)); rxLock.notifyAll(); }
            }
        }, BluetoothDevice.TRANSPORT_LE);
        long end = SystemClock.elapsedRealtime() + 15_000;
        while (!bleReady && running && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
        if (!bleReady) throw new java.io.IOException("no BLE serial link");
        Io io = new Io() {
            public void send(String cmd) {
                synchronized (rxLock) { rxBuf.setLength(0); }
                byte[] b = (cmd + "\r").getBytes(StandardCharsets.US_ASCII);
                int type = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                gatt.writeCharacteristic(tx, b, type);
            }
            public String reply(long timeoutMs) throws Exception {
                long end2 = SystemClock.elapsedRealtime() + timeoutMs;
                synchronized (rxLock) {
                    while (running && bleReady) {
                        int i = rxBuf.indexOf(">");
                        if (i >= 0) { String r = rxBuf.substring(0, i); rxBuf.delete(0, i + 1); return r; }
                        long left = end2 - SystemClock.elapsedRealtime(); if (left <= 0) return null;
                        rxLock.wait(left);
                    }
                }
                throw new java.io.IOException("BLE link lost");
            }
        };
        session(io);
    }

    /** The adapter's serial pipe: a notify characteristic to read and a writable one (often the same). Standard services skipped. */
    static BluetoothGattCharacteristic[] pickSerial(BluetoothGatt g) {
        BluetoothGattCharacteristic[] best = null;
        for (BluetoothGattService s : g.getServices()) {
            String u = s.getUuid().toString();
            if (u.startsWith("00001800") || u.startsWith("00001801") || u.startsWith("0000180a")) continue;
            BluetoothGattCharacteristic rx = null, w = null;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                if (rx == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) rx = c;
                if (w == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c;
            }
            if (rx != null && w != null) {
                best = new BluetoothGattCharacteristic[]{rx, w};
                if (u.startsWith("0000fff0") || u.startsWith("0000ffe0") || u.startsWith("000018f0")) return best;   // the usual ELM327 BLE services
            }
        }
        return best;
    }

    private void closeBle() {
        BluetoothGatt g = gatt; gatt = null; bleReady = false;
        if (g != null) try { g.disconnect(); g.close(); } catch (Exception ignored) {}
    }

    // ---------------- ELM327 conversation (both transports) ----------------
    interface Io { void send(String cmd) throws Exception; String reply(long timeoutMs) throws Exception; }

    private void session(Io io) throws Exception {
        status = "Adapter connected: setting up…";
        for (String c : INIT) {
            io.send(c);
            if (io.reply(c.equals("ATZ") ? 3000 : c.equals("0100") ? 12_000 : 2000) == null && !c.equals("0100")) throw new java.io.IOException("no answer to " + c);
            if (c.equals("ATZ")) Thread.sleep(300);
        }
        int misses = 0;
        while (running) {
            String q;
            while ((q = queries.poll()) != null) { io.send(q); String r = io.reply(3000); answers.put(q, r == null ? "(no reply)" : r.trim()); }
            long t0 = SystemClock.elapsedRealtime();
            io.send("010C");
            String r = io.reply(1500);
            if (r == null) throw new java.io.IOException("adapter stopped answering");
            int v = parseRpm(r);
            if (v >= 0) { rpm = v; rpmAt = SystemClock.elapsedRealtime(); misses = 0; status = "Live: " + v + " rpm"; }
            else if (++misses >= 5) {
                String u = r.toUpperCase(java.util.Locale.ROOT);
                status = u.contains("NO DATA") || u.contains("UNABLE") || u.contains("ERROR") ? "Adapter connected, no answer from the bike: ignition on?" : "Adapter connected, waiting for the bike";
                rpm = -1; Thread.sleep(1000);
            }
            long took = SystemClock.elapsedRealtime() - t0;
            if (took < 80) Thread.sleep(80 - took);   // at most ~12 readings a second
        }
    }

    static int parseRpm(String reply) { return ObdParse.rpm(reply); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
}
