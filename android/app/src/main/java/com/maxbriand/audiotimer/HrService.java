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
    public static final String ACTION_COOLDOWN = "com.maxbriand.audiotimer.COOLDOWN";
    /** Night tracking on/off (NightTrack): the strap held through the night, one line a minute. */
    public static final String ACTION_NIGHT = "com.maxbriand.audiotimer.NIGHT";

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
    // The night's own accelerometer stream: 25 Hz is plenty to tell a side from the back, and
    // half the radio traffic of the live page's 50 for the eight hours it stays on.
    private static final byte[] PMD_START_ACC_NIGHT = {0x02, 0x02, 0x00, 0x01, 0x19, 0x00, 0x01, 0x01, 0x10, 0x00, 0x02, 0x01, 0x08, 0x00};
    private static final byte[] PMD_STOP_ECG = {0x03, 0x00};
    private static final byte[] PMD_STOP_ACC = {0x03, 0x02};

    // Intensity bands as % of heart-rate reserve (Karvonen):
    // bpm = resting + pct × (max − resting). Times accrue per band.
    // The first band, very light (<30%), is open at the bottom: a rate below the resting
    // one counts there too.
    private static final int NB = 5;
    private static final int[] BAND_LO = { Integer.MIN_VALUE, 30, 40, 60, 90 };
    private static final int[] BAND_HI = { 30, 40, 60, 90, Integer.MAX_VALUE };
    private static final long TICK_MS = 1000;
    private static final long ALERT_REPEAT_MS = 4500;
    private static final long SAMPLE_STALE_MS = 5000;  // no HR for this long = not counting
    private static final long RECONNECT_MS = 2500;

    private static final int PHASE_READY = 0, PHASE_ACTIVE = 1, PHASE_DONE = 2;
    // The stages of a run — see the fields below.
    private static final int ST_WARMUP = 0, ST_REACH = 1, ST_REST = 2, ST_COOLDOWN = 3, ST_NONE = -1;
    private static final String[] STAGE_NAMES = { "warmup", "reach", "rest", "cooldown" };
    // Anything shorter is the heart wavering across a limit, not a part of the run: its
    // time joins the part before.
    private static final long MIN_PART_MS = 30000;

    private static volatile Listener listener;
    public static void setListener(Listener l) { listener = l; }

    /** One Heart Rate Measurement packet, decoded: the rate, and the beat-to-beat intervals
     *  it carried (ms, possibly none). The fatigue check's strap test listens here — it runs
     *  in its own activity, with the page gone, so it cannot ride the page's live stream. */
    public interface BeatListener {
        void onBeats(int bpm, float[] rrMs);
    }
    private static volatile BeatListener beatListener;
    public static void setBeatListener(BeatListener l) { beatListener = l; }

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
    // A tracked night in progress — read on BLE binder threads like liveOn.
    private volatile boolean nightOn;
    private static final long NIGHT_WAKE_MS = 14 * 60 * 60 * 1000L;

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
    private int min = 110, max = 170, delaySec = 10;
    private int hrmax = 189, resting = 57;             // profile, 0 = unset
    private boolean vibOn = true, sndOn = false;

    // session
    private int phase = PHASE_READY;
    private long startedAt, endedAt;
    private long tIn;
    private final long[] tBand = new long[NB];
    private int peak;
    private final JSONArray parts = new JSONArray();
    /* The run is a chain of stages. Warm-up (part 0) lasts from the start tap until the
       range low is first reached. Then each part is two halves: the reach, up to the range
       high (toMax — the exercise log's M column), and the rest, back down to the range low
       (recovery — its R column); touching the low closes the part and opens the next. The
       cool down is the last stage: once Cool down is pressed (`cool`) the below-range alarm
       is off, and going under the low opens it instead of another part. A rest that never
       touches the low stays unset: a blank cell, never a guess. `parts` holds the finished
       stages, each with its `kind`.
       Cool down pressed mid-climb ends the climb there: the part goes into its rest at the
       tap (`maxHit` false — no toMax, the high was never reached) and the rest runs until
       the low, like any other; only then does the cool down begin (Maxime, 2026-09-25).
       A brisk walk (`walk`) is one session with no stages at all: no parts, no cool down —
       its time, bands, peak and alerts only. */
    private int stage = ST_WARMUP;
    private long partStart, maxAt, partIn;
    private boolean cool, maxHit, walk;
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

        // Restarted by the system (START_STICKY hands a null intent) in the middle of a
        // tracked night: everything this service knew is gone, but the night is on disk with
        // the strap's address — pick both up and carry on into the same file.
        if (intent == null && NightTrack.on(this) && !NightTrack.address(this).isEmpty()) {
            engine.post(() -> {
                address = NightTrack.address(this);
                wantConnected = true;
                beginNight();
                openGatt();
            });
        }

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
            case ACTION_NIGHT:
                if (i.getBooleanExtra("on", false)) {
                    String a = i.getStringExtra("deviceId");
                    if (a != null && !a.isEmpty() && !a.equals(address)) { address = a; wantConnected = true; openGatt(); }
                    NightTrack.begin(this, address);
                    beginNight();
                } else endNight();
                break;
            case ACTION_DISCONNECT:
                // The night holds the strap until the wake-up — a page closing its Live view
                // or a fatigue check letting go must not end eight hours of recording.
                if (nightOn) break;
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
                if (i.getBooleanExtra("start", false)) startSession(i.getBooleanExtra("walk", false));
                else endSession();
                break;
            case ACTION_COOLDOWN:
                if (phase != PHASE_ACTIVE || stage == ST_WARMUP || stage == ST_COOLDOWN) break;
                cool = i.getBooleanExtra("on", false);
                if (walk) { cool = false; break; }
                if (cool) {
                    if ("low".equals(outDir)) { outSince = 0; outDir = null; alerting = false; alerts.cancel(); }
                    // Mid-climb: the climb stops here and the part's rest starts now.
                    if (stage == ST_REACH) { stage = ST_REST; maxAt = SystemClock.elapsedRealtime(); maxHit = false; }
                    if (isFresh()) stepStages(SystemClock.elapsedRealtime());
                }
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

    // ---------------------------------------------------------- night tracking

    /** The night is on: hold the CPU until morning, and ask the strap for its accelerometer. */
    private void beginNight() {
        nightOn = true;
        wantConnected = true;
        if (wakeLock != null) wakeLock.acquire(NIGHT_WAKE_MS);
        if (connected) startNight();          // else from onServicesDiscovered, like the live page
        updateNotification();
    }

    private void startNight() {
        if (gatt == null || !connected) return;
        final BluetoothGatt g = gatt;
        subscribePmd(g, PMD_CTRL);
        subscribePmd(g, PMD_DATA);
        writePmd(g, PMD_START_ACC_NIGHT);      // refused if the live page already runs it at 50 Hz — same frames either way
    }

    /** The wake-up: write the minute in hand, close the night, and let the strap go unless a
     *  run or the live page still wants it. */
    private void endNight() {
        if (!nightOn && !NightTrack.on(this)) return;
        NightTrack.flush(this);
        NightTrack.end(this);
        NightTrack.reset();
        nightOn = false;
        if (gatt != null && connected && !liveOn) writePmd(gatt, PMD_STOP_ACC);
        if (phase != PHASE_ACTIVE && !liveOn) {
            wantConnected = false;
            engine.postDelayed(() -> { if (!nightOn && phase != PHASE_ACTIVE && !liveOn) { closeGatt(); stopSelf(); } }, 800);
        } else updateNotification();
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
        if (!nightOn) writePmd(g, PMD_STOP_ACC);   // the night still needs the accelerometer
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

    private void startSession(boolean asWalk) {
        walk = asWalk;
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
        maxAt = 0;
        partIn = 0;
        cool = false;
        maxHit = false;
        boolean fresh = isFresh();
        reachedMin = fresh && hr >= min;
        // Already at the range low on the tap: there is no warm-up to count.
        stage = reachedMin ? ST_REACH : ST_WARMUP;
        outSince = 0; outDir = null; alerting = false;
    }

    private void endSession() {
        if (phase == PHASE_ACTIVE && !walk) closeStage(SystemClock.elapsedRealtime(), ST_NONE);
        phase = PHASE_DONE;
        endedAt = SystemClock.elapsedRealtime();
        outSince = 0; outDir = null; alerting = false;
        alerts.cancel();
    }

    /** A finished stage joins the list — unless it lasted under MIN_PART_MS and there is a
     *  part before it, in which case its time goes there (to that part's rest, if it has one). */
    private void pushStage(JSONObject e, long durMs) throws Exception {
        JSONObject prev = parts.length() > 0 ? parts.getJSONObject(parts.length() - 1) : null;
        if (prev != null && durMs < MIN_PART_MS) {
            double dur = durMs / 1000.0;
            prev.put("dur", prev.optDouble("dur", 0) + dur);
            if ("part".equals(prev.optString("kind"))) {
                prev.put("inRange", prev.optDouble("inRange", 0) + e.optDouble("inRange", 0));
                if (!prev.isNull("recovery")) prev.put("recovery", prev.getDouble("recovery") + dur);
            }
        } else parts.put(e);
    }

    /** Close the stage in progress at `now` and open `next` (ST_NONE at the end of the session). */
    private void closeStage(long now, int next) {
        try {
            long durMs = now - partStart;
            JSONObject e = new JSONObject();
            e.put("dur", durMs / 1000.0);
            if (stage == ST_WARMUP) {
                e.put("kind", "warmup");
                e.put("target", min);
                if (durMs > 0) pushStage(e, durMs);
            } else if (stage == ST_COOLDOWN) {
                e.put("kind", "cooldown");
                pushStage(e, durMs);
            } else if (stage == ST_REACH || stage == ST_REST) {
                e.put("kind", "part");
                e.put("inRange", partIn / 1000.0);
                e.put("target", max);
                e.put("toMax", stage == ST_REST && maxHit ? (Object) ((maxAt - partStart) / 1000.0) : JSONObject.NULL);
                e.put("recovery", stage == ST_REST && next != ST_NONE
                    ? (Object) ((now - maxAt) / 1000.0) : JSONObject.NULL);
                pushStage(e, durMs);
            }
        } catch (Exception ex) { /* a malformed part must never kill the session */ }
        stage = next;
        partStart = now;
        maxAt = 0;
        partIn = 0;
        maxHit = false;
    }

    /** The stage machine, one step at a time. */
    private void stepStages(long now) {
        if (stage == ST_WARMUP && hr >= min) closeStage(now, ST_REACH);
        if (stage == ST_REACH) {
            if (hr >= max) { stage = ST_REST; maxAt = now; maxHit = true; alerts.partDone(vibOn, sndOn); }
            else if (cool && hr < min) closeStage(now, ST_COOLDOWN);
        } else if (stage == ST_REST && hr <= min) {
            closeStage(now, cool ? ST_COOLDOWN : ST_REACH);
        }
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
        // ...and once Cool down is pressed it is off for good.
        if ("low".equals(out) && (!reachedMin || cool)) out = null;
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
        if (hr >= min && hr <= max) { tIn += dt; partIn += dt; }
        int bi = bandIndex();
        if (bi >= 0) tBand[bi] += dt;

        if (!walk) stepStages(now);
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
                if (nightOn) startNight();  // a strap that dropped at 3 a.m. and came back
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
            else if (PMD_DATA.equals(ch.getUuid())) {
                emitLiveBytes("pmd", value);
                if (nightOn) NightTrack.onAcc(HrService.this, value);
            }
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
        BeatListener bl = beatListener;
        if (bl == null && !nightOn) return;
        final float[] rrMs = rrOf(v, wide);
        if (bl != null) bl.onBeats(value, rrMs);
        if (nightOn) NightTrack.onBeats(this, value, rrMs);
    }

    /** The RR intervals of a Heart Rate Measurement, in ms. After the flags and the rate
     *  comes the energy field when bit 3 says so, then — bit 4 — the intervals themselves,
     *  uint16 each, in 1/1024 s. */
    private static float[] rrOf(byte[] v, boolean wide) {
        int flags = v[0] & 0xff;
        int i = wide ? 3 : 2;
        if ((flags & 0x08) != 0) i += 2;
        if ((flags & 0x10) == 0 || i + 1 >= v.length) return new float[0];
        float[] out = new float[(v.length - i) / 2];
        for (int n = 0; n < out.length; n++, i += 2)
            out[n] = ((v[i] & 0xff) | ((v[i + 1] & 0xff) << 8)) * 1000f / 1024f;
        return out;
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
            boolean live = phase == PHASE_ACTIVE && stage != ST_NONE;
            s.put("partElapsed", live ? (now - partStart) / 1000.0 : 0);
            // The two halves of the part in progress: the climb (frozen once the high is
            // reached) and the rest after it.
            s.put("reach", !live ? 0 : stage == ST_REST ? (maxAt - partStart) / 1000.0 : (now - partStart) / 1000.0);
            s.put("rest", live && stage == ST_REST ? (now - maxAt) / 1000.0 : 0);
            s.put("stage", stage >= 0 ? STAGE_NAMES[stage] : "warmup");
            s.put("cool", cool);
            s.put("walk", walk);
            s.put("reachedMin", reachedMin);
            s.put("out", outDir == null ? JSONObject.NULL : outDir);
            s.put("outFor", outSince == 0 ? 0 : (now - outSince) / 1000.0);
            s.put("alerting", alerting);
            s.put("night", nightOn);
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

    /** How many numbered parts are finished — the warm-up and the cool down are not counted. */
    private int numbered() {
        int n = 0;
        for (int i = 0; i < parts.length(); i++)
            if ("part".equals(parts.optJSONObject(i) == null ? null : parts.optJSONObject(i).optString("kind"))) n++;
        return n;
    }

    /** Live status in the shade — also the proof the engine is still running. */
    private Notification buildNotification() {
        String text;
        if (nightOn && phase != PHASE_ACTIVE) text = "Tracking the night since " + NightTrack.clock(NightTrack.startedAt(this))
            + (connected && isFresh() ? " · " + hr + " bpm" : " · waiting for the strap");
        else if (!connected) text = wantConnected ? "Reconnecting to the strap…" : "Watching your heart rate";
        else if (!isFresh()) text = "Waiting for signal…";
        else if (phase != PHASE_ACTIVE) text = hr + " bpm · no session running";
        else if (!reachedMin && hr < min) text = hr + " bpm · warm-up — alerts arm at " + min;
        else if (stage == ST_COOLDOWN || (cool && hr < min)) text = hr + " bpm · cool down";
        else if (outDir == null) text = hr + " bpm · part " + (numbered() + 1)
            + (stage == ST_REST ? " · rest down to " + min : " · reach " + max);
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
        if (nightOn) NightTrack.flush(this);   // killed mid-night: keep the minute in hand
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
