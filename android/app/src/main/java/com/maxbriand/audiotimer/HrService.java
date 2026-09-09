package com.maxbriand.audiotimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * The session engine. Everything that has to keep working with the screen off
 * lives here: the H10 subscription, zone detection, the alert delay and its
 * repeats, and the time accounting. None of it may sit in the WebView — a
 * hidden page gets its timers throttled and can be frozen outright, which is
 * why alerts used to arrive late or not at all once the screen went dark.
 *
 * The WebView is a display only. It pushes settings and session commands down
 * (via ZoneServicePlugin intents) and renders the state this service pushes up;
 * when it is throttled or gone, the alerts and the timers carry on regardless.
 */
public class HrService extends Service {

    public interface Listener {
        void onState(JSONObject state);
        /** Raw live-page traffic (PMD frames, HR packets, device info) — decoded by the page. */
        default void onLive(JSONObject ev) {}
    }

    public static final String ACTION_CONNECT = "com.maxbriand.audiotimer.CONNECT";
    public static final String ACTION_DISCONNECT = "com.maxbriand.audiotimer.DISCONNECT";
    public static final String ACTION_SETTINGS = "com.maxbriand.audiotimer.SETTINGS";
    public static final String ACTION_SESSION = "com.maxbriand.audiotimer.SESSION";
    public static final String ACTION_LIVE = "com.maxbriand.audiotimer.LIVE";

    private static final String CHANNEL = "zone_alarm_session";
    private static final int NOTIFICATION_ID = 1;

    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID BATT_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATT_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Live-data page extras: standard Device Information, and Polar's
    // proprietary PMD service (ECG + accelerometer streams). The service only
    // moves bytes; the page decodes them, so the framing lives in one place.
    private static final UUID DIS_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID DIS_MODEL = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    private static final UUID DIS_SERIAL = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");
    private static final UUID DIS_FW = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    private static final UUID PMD_SERVICE = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_CTRL = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_DATA = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8");
    // start measurement: ECG 130 Hz/14-bit; ACC 50 Hz/16-bit/±8 G — mirrors index.html.
    private static final byte[] PMD_START_ECG = {0x02, 0x00, 0x00, 0x01, (byte) 0x82, 0x00, 0x01, 0x01, 0x0e, 0x00};
    private static final byte[] PMD_START_ACC = {0x02, 0x02, 0x00, 0x01, 0x32, 0x00, 0x01, 0x01, 0x10, 0x00, 0x02, 0x01, 0x08, 0x00};
    private static final byte[] PMD_STOP_ECG = {0x03, 0x00};
    private static final byte[] PMD_STOP_ACC = {0x03, 0x02};

    // Intensity bands as % of heart-rate reserve (Karvonen):
    // bpm = resting + pct × (max − resting). Times accrue per band.
    private static final int NB = 4;
    private static final int[] BAND_LO = { 30, 40, 60, 90 };
    private static final int[] BAND_HI = { 40, 60, 90, Integer.MAX_VALUE };
    private static final long TICK_MS = 1000;
    private static final long ALERT_REPEAT_MS = 4500;
    private static final long SAMPLE_STALE_MS = 5000;  // no HR for this long = not counting
    private static final long RECONNECT_MS = 2500;

    private static final int PHASE_READY = 0, PHASE_ACTIVE = 1, PHASE_DONE = 2;

    private static volatile Listener listener;
    public static void setListener(Listener l) { listener = l; }

    private static volatile boolean running;
    /** Lets the plugin address a live engine without ever starting one by accident. */
    public static boolean isRunning() { return running; }

    private PowerManager.WakeLock wakeLock;
    private HandlerThread thread;
    private Handler engine;
    private NotificationManager nm;
    private Alerts alerts;

    // connection
    private String address;
    private BluetoothGatt gatt;
    private boolean wantConnected, connected;
    private int batt = -1;
    private long battAt;                              // wall clock of the last battery read
    private static final long BATT_REREAD_MS = 60000;
    // Written on the engine thread, read on BLE binder threads when forwarding.
    private volatile boolean liveOn;

    // One GATT operation at a time: Android silently drops overlapping ops, so
    // every read / descriptor write / characteristic write goes through this
    // queue (engine-thread confined) and the completion callback pumps the next.
    private final java.util.ArrayDeque<Runnable> ops = new java.util.ArrayDeque<>();
    private boolean opBusy;
    private int opSeq;

    private void op(Runnable r) { engine.post(() -> { ops.add(r); pump(); }); }

    private void opDone() { engine.post(() -> { opBusy = false; pump(); }); }

    private void pump() {
        if (opBusy) return;
        Runnable r = ops.poll();
        if (r == null) return;
        opBusy = true;
        final int seq = ++opSeq;
        try { r.run(); } catch (Exception e) { opBusy = false; pump(); return; }
        // If a completion callback never comes (op refused, stack hiccup), the
        // queue must not stall forever.
        engine.postDelayed(() -> { if (opBusy && seq == opSeq) { opBusy = false; pump(); } }, 3000);
    }

    // settings, mirrored from the UI
    private int min = 80, max = 170, delaySec = 10;
    private int hrmax = 0, resting = 0;                // profile, 0 = unset
    private boolean vibOn = true, sndOn = false;

    // session
    private int phase = PHASE_READY;
    private long startedAt, endedAt;
    private long tIn;
    private final long[] tBand = new long[NB];
    private int peak;
    private final JSONArray parts = new JSONArray();
    private long partStart, partIn;
    private boolean above;
    // Alerts stay silent until the heart rate has reached the min once —
    // starting a session at rest must not trip the below-range alarm.
    private boolean reachedMin;

    // live sample + alert state
    private int hr;
    private long lastSampleAt, lastTickAt;
    private long outSince;          // 0 = in range
    private String outDir;
    private boolean alerting;
    private long lastAlertAt;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        alerts = new Alerts(this);
        thread = new HandlerThread("zone-engine");
        thread.start();
        engine = new Handler(thread.getLooper());
        lastTickAt = SystemClock.elapsedRealtime();
        engine.postDelayed(tick, TICK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZoneAlarm:session");
            wakeLock.setReferenceCounted(false);
        }
        // 6 h cap as a battery safety net; a session never runs that long.
        if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);

        final String action = intent == null ? null : intent.getAction();
        if (action != null) {
            final Intent i = intent;
            engine.post(() -> handle(action, i));
        }
        return START_STICKY;
    }

    private void handle(String action, Intent i) {
        switch (action) {
            case ACTION_CONNECT:
                address = i.getStringExtra("deviceId");
                wantConnected = true;
                openGatt();
                break;
            case ACTION_DISCONNECT:
                wantConnected = false;
                closeGatt();
                stopSelf();
                break;
            case ACTION_SETTINGS:
                min = i.getIntExtra("min", min);
                max = i.getIntExtra("max", max);
                delaySec = i.getIntExtra("delay", delaySec);
                hrmax = i.getIntExtra("hrmax", hrmax);
                resting = i.getIntExtra("resting", resting);
                vibOn = i.getBooleanExtra("vib", vibOn);
                sndOn = i.getBooleanExtra("snd", sndOn);
                // A limit change restarts the out-of-range clock, as on the web.
                outSince = 0; outDir = null; alerting = false;
                break;
            case ACTION_SESSION:
                if (i.getBooleanExtra("start", false)) startSession();
                else endSession();
                break;
            case ACTION_LIVE:
                boolean on = i.getBooleanExtra("on", false);
                if (on == liveOn) break;
                liveOn = on;
                if (on) startLive();
                else stopLive();
                break;
        }
        push();
    }

    // -------------------------------------------------------------- live page

    /** Queue the live-page extras: device info reads, PMD subscriptions, stream starts. */
    private void startLive() {
        if (gatt == null || !connected) return;   // retried from onServicesDiscovered
        final BluetoothGatt g = gatt;
        for (UUID u : new UUID[]{ DIS_MODEL, DIS_SERIAL, DIS_FW }) {
            final UUID uuid = u;
            op(() -> {
                BluetoothGattService s = g.getService(DIS_SERVICE);
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(uuid);
                if (c == null || !g.readCharacteristic(c)) opDone();
            });
        }
        subscribePmd(g, PMD_CTRL);
        subscribePmd(g, PMD_DATA);
        writePmd(g, PMD_START_ECG);
        writePmd(g, PMD_START_ACC);
        engine.removeCallbacks(battReread);
        engine.post(battReread);
    }

    // While the live page is open, ask the strap for its battery every minute —
    // the H10 reports coarse steps, so at least the read time is honest.
    private final Runnable battReread = new Runnable() {
        @Override public void run() {
            if (!liveOn || gatt == null || !connected) return;
            final BluetoothGatt g = gatt;
            op(() -> {
                BluetoothGattService bs = g.getService(BATT_SERVICE);
                BluetoothGattCharacteristic bc = bs == null ? null : bs.getCharacteristic(BATT_LEVEL);
                if (bc == null || !g.readCharacteristic(bc)) opDone();
            });
            engine.postDelayed(this, BATT_REREAD_MS);
        }
    };

    private void stopLive() {
        engine.removeCallbacks(battReread);
        if (gatt == null || !connected) return;
        final BluetoothGatt g = gatt;
        writePmd(g, PMD_STOP_ECG);
        writePmd(g, PMD_STOP_ACC);
    }

    private void subscribePmd(BluetoothGatt g, UUID chUuid) {
        op(() -> {
            BluetoothGattService s = g.getService(PMD_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(chUuid);
            if (c == null) { opDone(); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) { opDone(); return; }
            // The control point indicates, the data stream notifies.
            byte[] v = PMD_CTRL.equals(chUuid)
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v);
            else { d.setValue(v); if (!g.writeDescriptor(d)) opDone(); }
        });
    }

    private void writePmd(BluetoothGatt g, byte[] cmd) {
        op(() -> {
            BluetoothGattService s = g.getService(PMD_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(PMD_CTRL);
            if (c == null) { opDone(); return; }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(cmd);
                if (!g.writeCharacteristic(c)) opDone();
            }
        });
    }

    private void emitLive(JSONObject ev) {
        Listener l = listener;
        if (l != null) l.onLive(ev);
    }

    private void emitLiveBytes(String kind, byte[] v) {
        if (!liveOn || v == null) return;
        try {
            JSONObject ev = new JSONObject();
            ev.put("k", kind);
            JSONArray b = new JSONArray();
            for (byte x : v) b.put(x & 0xff);
            ev.put("b", b);
            emitLive(ev);
        } catch (Exception e) { /* a dropped frame is invisible on a live scope */ }
    }

    // ---------------------------------------------------------------- session

    private void startSession() {
        // The clock starts on the tap; the below-range alarm stays silent
        // until the heart rate has reached the min once (reachedMin).
        phase = PHASE_ACTIVE;
        startedAt = SystemClock.elapsedRealtime();
        endedAt = 0;
        tIn = 0;
        for (int i = 0; i < NB; i++) tBand[i] = 0;
        peak = 0;
        while (parts.length() > 0) parts.remove(0);
        partStart = startedAt;
        partIn = 0;
        boolean fresh = isFresh();
        above = fresh && hr >= max;
        reachedMin = fresh && hr >= min;
        outSince = 0; outDir = null; alerting = false;
    }

    private void endSession() {
        phase = PHASE_DONE;
        endedAt = SystemClock.elapsedRealtime();
        outSince = 0; outDir = null; alerting = false;
        alerts.cancel();
    }

    private void closePart() {
        try {
            JSONObject p = new JSONObject();
            p.put("inRange", partIn / 1000.0);
            p.put("toMax", (SystemClock.elapsedRealtime() - partStart) / 1000.0);
            p.put("target", max);
            parts.put(p);
        } catch (Exception e) { /* a malformed part must never kill the session */ }
        above = true;
        alerts.partDone(vibOn, sndOn);
    }

    // ------------------------------------------------------------------- tick

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long dt = now - lastTickAt;
            lastTickAt = now;
            if (dt > 0) advance(now, Math.min(dt, SAMPLE_STALE_MS));
            push();
            updateNotification();
            engine.postDelayed(this, TICK_MS);
        }
    };

    private boolean isFresh() {
        return connected && hr > 0 && SystemClock.elapsedRealtime() - lastSampleAt < SAMPLE_STALE_MS;
    }

    /** Which intensity band the rate falls in, or -1 (needs the profile). */
    private int bandIndex() {
        if (hrmax <= 0 || resting <= 0 || hrmax <= resting) return -1;
        double pct = (hr - resting) * 100.0 / (hrmax - resting);
        for (int i = 0; i < NB; i++)
            if (pct >= BAND_LO[i] && pct < BAND_HI[i]) return i;
        return -1;
    }

    /** One step of zone detection, alerting and time accounting. */
    private void advance(long now, long dt) {
        if (phase != PHASE_ACTIVE || !isFresh()) {
            if (alerting) { alerting = false; alerts.cancel(); }
            return;
        }
        if (hr > peak) peak = hr;
        if (hr >= min) reachedMin = true;

        String out = hr < min ? "low" : hr > max ? "high" : null;
        // The below-range alarm arms only once the min has been reached —
        // before that, being low is just the warm-up.
        if ("low".equals(out) && !reachedMin) out = null;
        if (out == null) {
            outSince = 0; outDir = null; alerting = false;
        } else {
            if (outSince == 0 || !out.equals(outDir)) { outSince = now; outDir = out; alerting = false; }
            if (!alerting && (now - outSince) / 1000.0 >= delaySec) {
                alerting = true; lastAlertAt = now; alerts.buzz(out, vibOn, sndOn);
            } else if (alerting && now - lastAlertAt >= ALERT_REPEAT_MS) {
                lastAlertAt = now; alerts.buzz(out, vibOn, sndOn);
            }
        }

        // Only time inside the range counts — anything over the high limit does not.
        if (hr >= min && hr <= max) { tIn += dt; if (!above) partIn += dt; }
        int bi = bandIndex();
        if (bi >= 0) tBand[bi] += dt;

        if (!above && hr >= max) closePart();
        else if (above && hr < max) { above = false; partStart = now; partIn = 0; }
    }

    // -------------------------------------------------------------------- BLE

    private void openGatt() {
        if (address == null) return;
        closeGatt();
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothDevice dev = bm.getAdapter().getRemoteDevice(address);
            gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void closeGatt() {
        connected = false;
        ops.clear();
        opBusy = false;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception e) { /* already gone */ }
            gatt = null;
        }
    }

    private void scheduleReconnect() {
        if (!wantConnected) return;
        engine.postDelayed(() -> { if (wantConnected && !connected) openGatt(); }, RECONNECT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // A bigger MTU first: PMD ECG frames do not fit the 23-byte
                // default. Service discovery follows from onMtuChanged.
                try { if (!g.requestMtu(517)) g.discoverServices(); }
                catch (Exception e) { try { g.discoverServices(); } catch (Exception e2) { /* revoked */ } }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                engine.post(() -> { connected = false; closeGatt(); push(); scheduleReconnect(); });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            try { g.discoverServices(); } catch (Exception e) { /* permission revoked mid-run */ }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            engine.post(() -> {
                ops.clear();
                opBusy = false;
            });
            op(() -> {
                BluetoothGattService svc = g.getService(HR_SERVICE);
                BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(HR_MEASUREMENT);
                if (ch == null) { opDone(); engine.post(HrService.this::scheduleReconnect); return; }
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
                if (cccd == null) { opDone(); return; }
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(cccd)) opDone();
                }
            });
            op(() -> {
                BluetoothGattService bs = g.getService(BATT_SERVICE);
                BluetoothGattCharacteristic bc = bs == null ? null : bs.getCharacteristic(BATT_LEVEL);
                if (bc == null || !g.readCharacteristic(bc)) opDone();
            });
            engine.post(() -> {
                connected = true;
                push();
                if (liveOn) startLive();   // live page open across a reconnect
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            opDone();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            opDone();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (HR_MEASUREMENT.equals(ch.getUuid())) { onHr(value); emitLiveBytes("hrraw", value); }
            else if (PMD_DATA.equals(ch.getUuid())) emitLiveBytes("pmd", value);
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, ch, ch.getValue());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value, int status) {
            UUID u = ch.getUuid();
            if (BATT_LEVEL.equals(u) && value != null && value.length > 0) {
                final int b = value[0] & 0xff;
                engine.post(() -> { batt = b; battAt = System.currentTimeMillis(); push(); });
            } else if (value != null && (DIS_MODEL.equals(u) || DIS_SERIAL.equals(u) || DIS_FW.equals(u))) {
                try {
                    JSONObject ev = new JSONObject();
                    ev.put("k", "dis");
                    ev.put(DIS_MODEL.equals(u) ? "model" : DIS_SERIAL.equals(u) ? "serial" : "fw",
                        new String(value, java.nio.charset.StandardCharsets.UTF_8).trim());
                    emitLive(ev);
                } catch (Exception e) { /* decoration */ }
            }
            opDone();
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(g, ch, ch.getValue(), status);
        }
    };

    /** Heart Rate Measurement (0x2A37): bit 0 of the flags picks uint8 vs uint16. */
    private void onHr(byte[] v) {
        if (v == null || v.length < 2) return;
        boolean wide = (v[0] & 0x01) != 0;
        final int value = wide
            ? (v.length >= 3 ? ((v[1] & 0xff) | ((v[2] & 0xff) << 8)) : (v[1] & 0xff))
            : (v[1] & 0xff);
        engine.post(() -> {
            hr = value;
            lastSampleAt = SystemClock.elapsedRealtime();
            connected = true;
        });
    }

    // ------------------------------------------------------------------ output

    private JSONObject state() {
        JSONObject s = new JSONObject();
        try {
            long now = SystemClock.elapsedRealtime();
            s.put("connected", connected);
            s.put("wantConnected", wantConnected);
            s.put("hr", isFresh() ? hr : 0);
            s.put("batt", batt);
            s.put("battAt", battAt);
            s.put("phase", phase == PHASE_ACTIVE ? "active" : phase == PHASE_DONE ? "done" : "ready");
            s.put("elapsed", phase == PHASE_READY ? 0 : ((endedAt == 0 ? now : endedAt) - startedAt) / 1000.0);
            s.put("tIn", tIn / 1000.0);
            JSONArray bands = new JSONArray();
            for (int i = 0; i < NB; i++) bands.put(tBand[i] / 1000.0);
            s.put("bands", bands);
            s.put("peak", peak);
            // A copy, not the live array: the engine thread keeps appending to
            // `parts` while the UI thread serialises whatever it was handed.
            s.put("parts", new JSONArray(parts.toString()));
            s.put("partIn", partIn / 1000.0);
            s.put("partElapsed", phase == PHASE_ACTIVE && !above ? (now - partStart) / 1000.0 : 0);
            s.put("above", above);
            s.put("reachedMin", reachedMin);
            s.put("out", outDir == null ? JSONObject.NULL : outDir);
            s.put("outFor", outSince == 0 ? 0 : (now - outSince) / 1000.0);
            s.put("alerting", alerting);
        } catch (Exception e) { /* fall through with whatever was set */ }
        return s;
    }

    private void push() {
        Listener l = listener;
        if (l != null) l.onState(state());
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Active session", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else
            startForeground(NOTIFICATION_ID, buildNotification());
    }

    /** Live status in the shade — also the proof the engine is still running. */
    private Notification buildNotification() {
        String text;
        if (!connected) text = wantConnected ? "Reconnecting to the strap…" : "Watching your heart rate";
        else if (!isFresh()) text = "Waiting for signal…";
        else if (phase != PHASE_ACTIVE) text = hr + " bpm · no session running";
        else if (!reachedMin && hr < min) text = hr + " bpm · warm-up — alerts arm at " + min;
        else if (outDir == null) text = hr + " bpm · in range · part " + (parts.length() + 1);
        else text = hr + " bpm · " + ("high".equals(outDir) ? "above " + max : "below " + min);

        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        return b
            .setContentTitle("Zone Alarm")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification() {
        try { nm.notify(NOTIFICATION_ID, buildNotification()); } catch (Exception e) { /* shade unavailable */ }
    }

    @Override
    public void onDestroy() {
        running = false;
        wantConnected = false;
        closeGatt();
        alerts.cancel();
        if (engine != null) engine.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }
}
