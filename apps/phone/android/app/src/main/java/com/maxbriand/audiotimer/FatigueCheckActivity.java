package com.maxbriand.audiotimer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/*
 * A fatigue check, from the ring to the record.
 *
 * It rings like the alarm it is, over the lock screen, until Start (or "Skip the whole
 * check") is tapped. Then the steps the check was given, always in this order, each with
 * its own Skip — a step that cannot be done is said so, never guessed:
 *
 *   1. Strap test — the H10's heart rate is shown from the first beat it sends, and that
 *      beat starts five calm minutes. The first two only settle; minutes 2 to 5 are the
 *      record: HR is the average of the rate over those three minutes, HRV the RMSSD over
 *      all the beat-to-beat intervals the strap sent in them. An interval outside
 *      300–2000 ms (a 200 or a 30 bpm beat) is a lost contact, not a heartbeat: it is left
 *      out, counted, and the successive difference is not taken across the hole it leaves.
 *   2. PVT — done on the computer; the phone only waits to be told it is over.
 *   3. The fatigue question — FatigueQuestion's, and staged for the server exactly as it
 *      always was, so the sleep diary keeps reading its morning score where it did.
 *   4. Eyes — the same kind of question about eye fatigue (Maxime, 2026-10-06). Both are
 *      0–5 scales with a sentence per level (FatigueQuestion.FATIGUE_LEVELS / EYE_LEVELS),
 *      recorded with their scale.
 *
 * The whole check is ONE record, handed to FatigueChecks for the page to file in its log.
 * A run started from ⚙'s Test button is the same run, but its record is marked as a test
 * and goes nowhere: the page drops it instead of filing it in the log, and its fatigue
 * answer is not sent to the server, because the diary takes the first score after the night
 * as the morning's, and a tap made to try the feature out must not become that.
 *
 * The strap is the service's (HrService), not this screen's: if a run or the Live page
 * already holds it, the test only listens; if nothing does, the test asks for the
 * connection and lets go of it when it ends. Until the first beat arrives, "Connect
 * sensor" scans for heart-rate straps and connects the one picked — the way in when no
 * strap is known yet, Bluetooth was never allowed, or the known one is not answering
 * (Maxime, 2026-09-25). The pick becomes the strap the checks use from then on.
 *
 * The strap test's two settling minutes can be skipped (Maxime, 2026-10-04): recording starts
 * at the tap — or at the first beat, when tapped before it — and lasts the same 3 minutes.
 *
 * The heart page (🫀) starts the same strap test by hand (`manual`): no ring, no other step,
 * and every result is its own record, saved when it is shown — "Run again" starts another,
 * and the screen lists all of this sitting's results. Its records carry anchor "manual" and
 * the column "manual_test": the diary keeps them out of the fatigue columns and lists the
 * day's tests in one manual_test column.
 */
public class FatigueCheckActivity extends Activity {
  static final String EXTRA_CHECK_ID = "checkId";
  private static final String EXTRA_LABEL = "label", EXTRA_STRAP = "strap", EXTRA_PVT = "pvt",
    EXTRA_QUESTION = "question", EXTRA_EYES = "eyes", EXTRA_TEST = "test", EXTRA_DUE = "dueAt",
    EXTRA_DAY = "localDay", EXTRA_ANCHOR = "anchor", EXTRA_OFFSET = "offsetMin", EXTRA_MANUAL = "manual";

  /** The heart page's HR / HRV test: the strap test alone, started now, no ring. */
  static Intent manualIntent(Context c){
    return new Intent(c, FatigueCheckActivity.class)
      .putExtra(EXTRA_CHECK_ID, "manual")
      .putExtra(EXTRA_LABEL, "Manual test")
      .putExtra(EXTRA_ANCHOR, "manual")
      .putExtra(EXTRA_STRAP, true)
      .putExtra(EXTRA_MANUAL, true);
  }

  private static final long CALM_MS = 5 * 60000L, RECORD_FROM_MS = 2 * 60000L;
  private static final long SILENT_MS = 10000L;     // no packet for this long: say so
  private static final long FIND_MS = 3000L;        // how long a link someone else holds gets to show itself
  private static final float RR_MIN = 300f, RR_MAX = 2000f;
  private static final int MIN_INTERVALS = 30;      // under this, three minutes recorded nothing usable

  private static final int BG = FatigueQuestion.BG, SURFACE = FatigueQuestion.SURFACE,
    TEXT = FatigueQuestion.TEXT, MUTED = FatigueQuestion.MUTED, ACCENT = FatigueQuestion.ACCENT;
  private static final int GOOD = Color.parseColor("#7ee0a1"), WARN = Color.parseColor("#ffdf8e");

  static Intent intent(Context c, JSONObject k, long dueAt, boolean test){
    boolean wake = !"sleep".equals(k.optString("anchor"));
    return new Intent(c, FatigueCheckActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(EXTRA_CHECK_ID, k.optString("id"))
      .putExtra(EXTRA_LABEL, FatigueChecks.label(k))
      .putExtra(EXTRA_ANCHOR, wake ? "wake" : "sleep")
      .putExtra(EXTRA_OFFSET, Math.max(0, k.optInt("offsetMin", 0)))
      .putExtra(EXTRA_STRAP, k.optBoolean("strap"))
      .putExtra(EXTRA_PVT, k.optBoolean("pvt"))
      .putExtra(EXTRA_QUESTION, k.optBoolean("question"))
      .putExtra(EXTRA_EYES, k.optBoolean("eyes"))
      .putExtra(EXTRA_TEST, test)
      .putExtra(EXTRA_DUE, dueAt)
      .putExtra(EXTRA_DAY, dayOf(wake, Math.max(0, k.optInt("offsetMin", 0)), dueAt));
  }

  /* The day a check belongs to — not the date it rings on. A wake check goes with the day
     of its wake-up; a sleep check with the day its night is named after, the date 12 h
     before the bedtime (the diary's rule for nights): "1 h 30 before sleep" ringing at
     00:24 is the evening before's (2026-09-30). A test has no due time: it runs now. */
  static String dayOf(boolean wake, int offsetMin, long dueAt){
    long at = dueAt > 0 ? dueAt : System.currentTimeMillis();
    long anchor = wake ? at - offsetMin * 60000L : at + offsetMin * 60000L - 12 * 3600000L;
    return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(anchor));
  }

  static String stepsLine(JSONObject k){
    ArrayList<String> s = new ArrayList<>();
    if (k.optBoolean("strap")) s.add("strap test");
    if (k.optBoolean("pvt")) s.add("PVT");
    if (k.optBoolean("question")) s.add("fatigue question");
    if (k.optBoolean("eyes")) s.add("eyes");
    return s.isEmpty() ? "no step" : android.text.TextUtils.join(" · ", s);
  }

  /* The check under way, if any. It lives in a task of its own, so leaving for another app
     and coming back through the launcher lands on the app, not on the check — which then sat
     unseen behind it (2026-10-01). MainActivity hands the screen back to it on every resume. */
  private static FatigueCheckActivity live;

  /** Bring the running check back in front; false when none is under way. */
  static boolean bringBack(Context c){
    FatigueCheckActivity a = live;
    if (a == null || a.isFinishing() || a.saved) return false;
    try {
      c.startActivity(new Intent(c, FatigueCheckActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      return true;
    } catch (Exception e){ return false; }
  }

  private final Handler ui = new Handler(Looper.getMainLooper());
  private MediaPlayer player;
  private Vibrator vibrator;
  private LinearLayout root;

  private final ArrayList<String> steps = new ArrayList<>();
  private int index = -1;
  private boolean test, saved, manual;
  // The manual test's results this sitting, newest last: "HH:MM — HR bpm · HRV ms".
  private final ArrayList<String> sitting = new ArrayList<>();
  // When recording starts and ends, counted from the first beat: 2 and 5 minutes, unless the
  // settling was skipped.
  private long recFrom = RECORD_FROM_MS, calmEnd = CALM_MS;
  private boolean skipAsked;
  private Button skipSettleBtn;
  private String label, day, anchor;
  private long startedAt;
  private final JSONObject record = new JSONObject(), results = new JSONObject();

  // strap test
  private boolean strapLive, ownsLink;
  private Boolean foreignLink;      // was the strap's service already someone else's? decided once
  private long firstBeatAt, lastBeatAt, recFromWall;
  private long hrSum; private int hrN, dropped, ectopic;
  private final ArrayList<Float> rr = new ArrayList<>();     // NaN = a hole in the chain
  // Every interval received while recording, unfiltered, and what became of it:
  // 0 kept, 1 out of range (lost contact), 2 rejected as ectopic (> 20% off the reference).
  private final ArrayList<Integer> rrRaw = new ArrayList<>(), rrFlag = new ArrayList<>();
  private float rrRef = Float.NaN;  // last accepted interval: what the next one is compared with
  private int rrRejectRun;          // ectopic rejections in a row since the last accepted one
  // Held back until the starting reference is known: the median of the first 5 in-range
  // intervals, so one extra beat at the very start can't become the reference.
  private final ArrayList<Float> rrPending = new ArrayList<>();
  private static final float ECTOPIC_FRAC = 0.20f;
  private static final int REANCHOR_AFTER = 3, START_REF_N = 5;
  // Over 5% of the intervals left out (lost contact or extra/missed beats): RMSSD isn't trusted.
  private static final double POOR_FRAC = 0.05;
  private TextView hrView, phaseView, clockView, hintView;
  private Button connectBtn;
  private BluetoothLeScanner scanner;
  private ScanCallback scanCb;
  private AlertDialog picker;
  private static final int REQ_BT = 7;
  private static final long SCAN_MS = 15000L;
  private static final ParcelUuid HR_UUID = ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb");
  private long pvtShownAt;

  @Override
  protected void onCreate(Bundle b){
    super.onCreate(b);
    if (Build.VERSION.SDK_INT >= 27){
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                         | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    // Five minutes of sitting still is five minutes of not touching the phone.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    Intent in = getIntent();
    // A bring-back that arrived after the check was gone carries no check: nothing to show.
    if (in.getStringExtra(EXTRA_CHECK_ID) == null){ saved = true; finish(); return; }
    live = this;
    test = in.getBooleanExtra(EXTRA_TEST, false);
    manual = in.getBooleanExtra(EXTRA_MANUAL, false);
    label = in.getStringExtra(EXTRA_LABEL);
    day = in.getStringExtra(EXTRA_DAY);
    anchor = in.getStringExtra(EXTRA_ANCHOR);
    if (in.getBooleanExtra(EXTRA_STRAP, false)) steps.add("strap");
    if (in.getBooleanExtra(EXTRA_PVT, false)) steps.add("pvt");
    if (in.getBooleanExtra(EXTRA_QUESTION, false)) steps.add("question");
    if (in.getBooleanExtra(EXTRA_EYES, false)) steps.add("eyes");
    startedAt = System.currentTimeMillis();
    try {
      record.put("id", UUID.randomUUID().toString());
      record.put("checkId", in.getStringExtra(EXTRA_CHECK_ID));
      record.put("label", label == null ? "" : label);
      record.put("anchor", anchor == null ? "wake" : anchor);
      // The delay as a number, beside the words: "what + when" is what a results column is
      // named after ("HRV +5min", "Fatigue -1h sleep"), and words are no key to group by.
      record.put("offsetMin", in.getIntExtra(EXTRA_OFFSET, 0));
      record.put("test", test);
      record.put("dueAt", in.getLongExtra(EXTRA_DUE, 0));
      record.put("at", startedAt);
      record.put("localDay", day == null ? "" : day);
    } catch (Exception ignored){}

    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setGravity(Gravity.CENTER);
    int p = dp(28);
    root.setPadding(p, p, p, p);
    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(BG);
    sv.setFillViewport(true);
    sv.addView(root);
    setContentView(sv);

    if (manual){ index = 0; showStrap(); return; }   // started by hand: no ring, straight in
    showIntro();
    ring();
  }

  // ------------------------------------------------------------------ widgets

  private int dp(int v){ return FatigueQuestion.dp(this, v); }

  private TextView text(String s, int size, int color, boolean bold){
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setGravity(Gravity.CENTER);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  private TextView add(TextView t, int top){
    t.setPadding(0, dp(top), 0, 0);
    root.addView(t);
    return t;
  }

  private Button button(String s, boolean primary, Runnable r){
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(primary ? 18 : 15);
    if (primary){
      b.setTextColor(BG);
      b.setTypeface(Typeface.DEFAULT_BOLD);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(TEXT);
      bg.setCornerRadius(dp(14));
      b.setBackground(bg);
    } else {
      b.setTextColor(MUTED);
      b.setBackgroundColor(Color.TRANSPARENT);
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, primary ? dp(58) : LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, dp(primary ? 28 : 14), 0, 0);
    b.setLayoutParams(lp);
    b.setOnClickListener(v -> r.run());
    root.addView(b);
    return b;
  }

  /** "Step 2 of 3 · PVT" — where in the check this screen is. */
  private void head(String name){
    root.removeAllViews();
    add(text(manual ? "HR / HRV — manual test" : (test ? "TEST · " : "") + "Step " + (index + 1) + " of " + steps.size(), 13, MUTED, false), 0);
    add(text(name, 26, TEXT, true), 6);
  }

  // ------------------------------------------------------------------ the ring

  private void showIntro(){
    root.removeAllViews();
    add(text(test ? "Fatigue check — test" : "Fatigue check", 28, TEXT, true), 0);
    add(text(label == null || label.isEmpty() ? "" : label, 16, ACCENT, false), 8);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < steps.size(); i++)
      sb.append(i + 1).append(". ").append(stepName(steps.get(i))).append(i + 1 < steps.size() ? "\n" : "");
    add(text(steps.isEmpty() ? "This check has no step — edit it in ⚙ → Day." : sb.toString(), 16, MUTED, false), 22);
    button("Start", true, () -> { quiet(); next(); });
    button("Skip the whole check", false, () -> { quiet(); finishCheck(); });
  }

  private static String stepName(String s){
    return "strap".equals(s) ? "Strap test — 5 calm minutes"
         : "pvt".equals(s) ? "PVT — on the computer"
         : "eyes".equals(s) ? "Eyes — 0 to 5"
         : "Fatigue question — 0 to 5";
  }

  private void ring(){
    try {
      Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
      player = new MediaPlayer();
      player.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
      player.setDataSource(this, tone);
      player.setLooping(true);
      player.prepare();
      player.start();
    } catch (Exception ignored){}    // a silent screen still runs the check
    try {
      vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      long[] pattern = {0, 600, 500};
      if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
      else vibrator.vibrate(pattern, 0);
    } catch (Exception ignored){}
  }

  private void quiet(){
    if (player != null){
      try { player.stop(); } catch (Exception ignored){}
      player.release();
      player = null;
    }
    if (vibrator != null){ vibrator.cancel(); vibrator = null; }
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    nm.cancel(FatigueCheckReceiver.NOTIF_ID);
  }

  /** One short buzz: the record started, the record ended — eyes can stay shut. */
  private void buzz(long ms){
    try {
      Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
      else v.vibrate(ms);
    } catch (Exception ignored){}
  }

  // ------------------------------------------------------------------ the steps

  private void next(){
    index++;
    if (index >= steps.size()){ finishCheck(); return; }
    String s = steps.get(index);
    if ("strap".equals(s)) showStrap();
    else if ("pvt".equals(s)) showPvt();
    else if ("eyes".equals(s)) showEyes();
    else showQuestion();
  }

  private void put(String step, JSONObject r){
    try { results.put(step, r); } catch (Exception ignored){}
  }

  private static JSONObject status(String s){
    JSONObject o = new JSONObject();
    try { o.put("status", s); } catch (Exception ignored){}
    return o;
  }

  // ---- 1. strap test

  private void showStrap(){
    head("Strap test");
    hrView = add(text("--", 72, TEXT, true), 18);
    add(text("bpm", 14, MUTED, false), 0);
    clockView = add(text("", 34, ACCENT, true), 20);
    phaseView = add(text("", 15, MUTED, false), 6);
    hintView = add(text("", 14, WARN, false), 14);
    connectBtn = button("Connect sensor", false, this::pickSensor);
    connectBtn.setTextColor(ACCENT);
    connectBtn.setTextSize(17);
    skipSettleBtn = button("Skip the 2 min — record now", false, this::skipSettle);
    skipSettleBtn.setTextColor(ACCENT);
    if (manual) button("Stop", false, this::finishCheck);
    else button("Skip the strap test", false, () -> { endStrap(); put("strap", status("skipped")); next(); });

    recFrom = RECORD_FROM_MS; calmEnd = CALM_MS; skipAsked = false;
    firstBeatAt = lastBeatAt = recFromWall = 0;
    hrSum = 0; hrN = 0; dropped = 0; rr.clear();
    ectopic = 0; rrRaw.clear(); rrFlag.clear(); rrPending.clear(); rrRef = Float.NaN; rrRejectRun = 0;
    strapLive = true;

    if (Build.VERSION.SDK_INT >= 31
        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED){
      phaseView.setText("Bluetooth is not allowed for the app yet.");
      hintView.setText("Tap Connect sensor to allow it and pick the strap.");
      return;
    }
    HrService.setBeatListener((bpm, rrMs) -> ui.post(() -> onBeats(bpm, rrMs)));
    phaseView.setText("Looking for the strap — put it on, sit down.");
    // Decided on the first attempt only: after "Run it again" the service this test started
    // may still be winding down, and must not be mistaken for a run's.
    if (foreignLink == null) foreignLink = HrService.isRunning();
    final boolean wasRunning = foreignLink;
    final String address = FatigueChecks.strap(this);
    // A link someone else holds (a run, the Live page) shows itself within seconds, and is
    // only listened to. Nothing heard: ask for the connection — and own it only when the
    // service was nobody's, because letting go of it stops the service, run included.
    ui.postDelayed(() -> {
      if (!strapLive || firstBeatAt != 0 || lastBeatAt != 0) return;
      if (address.isEmpty()){
        phaseView.setText("No strap known yet.");
        hintView.setText("Tap Connect sensor to pick it.");
        return;
      }
      ownsLink = !wasRunning;
      connect(address);
    }, wasRunning ? FIND_MS : 0);
    ui.postDelayed(tick, 500);
  }

  /** The two settling minutes, skipped: the 3 recorded minutes start now (or at the first beat). */
  private void skipSettle(){
    skipAsked = true;
    long el = firstBeatAt == 0 ? 0 : SystemClock.elapsedRealtime() - firstBeatAt;
    if (el < recFrom){ recFrom = el; calmEnd = el + (CALM_MS - RECORD_FROM_MS); }
    if (skipSettleBtn != null) skipSettleBtn.setVisibility(View.GONE);
  }

  private void connect(String address){
    try {
      Intent i = new Intent(this, HrService.class).setAction(HrService.ACTION_CONNECT)
        .putExtra("deviceId", address);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    } catch (Exception e){
      hintView.setText("Could not start the strap connection — skip, or try from the app.");
    }
  }

  private void onBeats(int bpm, float[] rrMs){
    if (!strapLive) return;
    long now = SystemClock.elapsedRealtime();
    lastBeatAt = now;
    if (bpm <= 0){ hrView.setText("--"); return; }        // connected, no skin contact yet
    hrView.setText(String.valueOf(bpm));
    if (firstBeatAt == 0){
      firstBeatAt = now; hintView.setText("");
      if (connectBtn != null) connectBtn.setVisibility(View.GONE);
      stopScan();
    }
    if (now - firstBeatAt < recFrom || now - firstBeatAt >= calmEnd) return;
    if (recFromWall == 0) recFromWall = System.currentTimeMillis();
    hrSum += bpm; hrN++;
    for (float v : rrMs){
      rrRaw.add(Math.round(v));
      if (!Float.isNaN(rrRef)){ classifyRr(v); continue; }
      rrPending.add(v);
      int inRange = 0;
      for (float p : rrPending) if (p >= RR_MIN && p <= RR_MAX) inRange++;
      if (inRange >= START_REF_N) flushPendingRr();
    }
  }

  /** Sets the starting reference from what is held back, then runs it through the filter. */
  private void flushPendingRr(){
    ArrayList<Float> ok = new ArrayList<>();
    for (float p : rrPending) if (p >= RR_MIN && p <= RR_MAX) ok.add(p);
    if (!ok.isEmpty()){
      java.util.Collections.sort(ok);
      int m = ok.size() / 2;
      rrRef = ok.size() % 2 == 1 ? ok.get(m) : (ok.get(m - 1) + ok.get(m)) / 2f;
    }
    for (float p : rrPending) classifyRr(p);
    rrPending.clear();
  }

  private void classifyRr(float v){
    if (v < RR_MIN || v > RR_MAX){ dropped++; rr.add(Float.NaN); rrFlag.add(1); return; }
    // An extra beat or a missed one stays in range but jumps from its neighbours. Compare with
    // the last accepted interval (not the last raw one), so the beat after an early beat is
    // judged against a normal one. After 3 rejections in a row the heart rate really moved:
    // take this one as the new reference.
    if (!Float.isNaN(rrRef) && Math.abs(v - rrRef) > ECTOPIC_FRAC * rrRef && rrRejectRun < REANCHOR_AFTER){
      ectopic++; rrRejectRun++; rr.add(Float.NaN); rrFlag.add(2); return;
    }
    rrRef = v; rrRejectRun = 0; rr.add(v); rrFlag.add(0);
  }

  private boolean recording;
  private final Runnable tick = new Runnable(){
    @Override public void run(){
      if (!strapLive) return;
      long now = SystemClock.elapsedRealtime();
      if (firstBeatAt != 0){
        long el = now - firstBeatAt;
        if (el >= calmEnd){ finishStrap(); return; }
        boolean rec = el >= recFrom;
        if (rec && !recording){ recording = true; buzz(200); if (skipSettleBtn != null) skipSettleBtn.setVisibility(View.GONE); }
        long left = calmEnd - el;
        clockView.setText(String.format(Locale.US, "%d:%02d", left / 60000, (left / 1000) % 60));
        clockView.setTextColor(rec ? GOOD : ACCENT);
        long toRec = recFrom - el;
        phaseView.setText(rec
          ? "Recording — stay still, breathe normally."
          : String.format(Locale.US, "Settle down — recording starts in %d:%02d.", toRec / 60000, (toRec / 1000) % 60));
        if (now - lastBeatAt > SILENT_MS) hintView.setText("The strap went silent — check it is still on and wet.");
        else if (hintView.getText().toString().startsWith("The strap went")) hintView.setText("");
      }
      ui.postDelayed(this, 500);
    }
  };

  private void finishStrap(){
    final long toWall = System.currentTimeMillis();
    buzz(500);
    flushPendingRr();     // a recording too short to fill the starting window
    final double[] h = FatigueChecks.hrv(rr);
    final int n = (int) h[0];
    endStrap();
    head("Strap test");
    final int beats = n;
    if (n < MIN_INTERVALS || Double.isNaN(h[1]) || hrN == 0){
      add(text("Not enough beats", 22, WARN, true), 22);
      add(text("The strap sent " + n + " beat-to-beat intervals in the three minutes — too few to mean anything. "
        + "Check the contact (wet the electrodes) and run it again, or skip.", 15, MUTED, false), 10);
      button("Run it again", true, this::showStrap);
      if (manual){ showSitting(); button("Done", false, this::finishCheck); return; }
      button("Skip the strap test", false, () -> {
        JSONObject f = status("failed");
        try { f.put("beats", beats); } catch (Exception ignored){}
        put("strap", f); next();
      });
      return;
    }
    final double hr = Math.round(hrSum * 10.0 / hrN) / 10.0;
    final double rmssd = Math.round(h[1] * 10.0) / 10.0;
    final boolean poor = rrFlag.size() > 0 && (dropped + ectopic) > POOR_FRAC * rrFlag.size();
    JSONObject r = status("done");
    try {
      r.put("hr", hr);
      r.put("rmssd", rmssd);
      r.put("beats", n);
      r.put("dropped", dropped);
      r.put("ectopic", ectopic);
      r.put("quality", poor ? "poor" : "good");
      r.put("rr", new JSONArray(rrRaw));
      r.put("rrFlag", new JSONArray(rrFlag));
      r.put("meanRr", Math.round(h[2]));
      r.put("from", recFromWall);
      r.put("to", toWall);
    } catch (Exception ignored){}
    put("strap", r);
    add(text(String.valueOf(hr), 56, TEXT, true), 22);
    add(text("bpm — average over the 3 minutes", 14, MUTED, false), 0);
    add(text(String.valueOf(rmssd), 56, GOOD, true), 20);
    add(text("ms — HRV (RMSSD) over " + n + " beat-to-beat intervals"
      + (dropped > 0 ? ", " + dropped + " left out as lost contact" : "")
      + (ectopic > 0 ? ", " + ectopic + " left out as extra or missed beats" : ""), 14, MUTED, false), 0);
    if (poor){
      add(text("Unreliable: " + (dropped + ectopic) + " of " + rrFlag.size()
        + " intervals were left out (over 5%), so this HRV isn't counted. "
        + "Wet the electrodes, sit still and run it again.", 15, WARN, false), 14);
      if (!manual) button("Run it again", false, this::showStrap);
    }
    if (manual){
      saveManual(r, poor);
      showSitting();
      button("Run again", true, this::showStrap);
      button("Done", false, this::finishCheck);
      return;
    }
    button(index + 1 < steps.size() ? "Next" : "Done", true, this::next);
  }

  // ---- Connect sensor: scan, pick, connect

  private void pickSensor(){
    ArrayList<String> need = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31){
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        need.add(Manifest.permission.BLUETOOTH_SCAN);
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        need.add(Manifest.permission.BLUETOOTH_CONNECT);
    } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
      need.add(Manifest.permission.ACCESS_FINE_LOCATION);     // BLE scans need it up to Android 11
    }
    if (!need.isEmpty()){ requestPermissions(need.toArray(new String[0]), REQ_BT); return; }

    BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
    if (ba == null){ hintView.setText("This phone has no Bluetooth."); return; }
    if (!ba.isEnabled()){
      hintView.setText("Bluetooth is off — turn it on, then tap Connect sensor again.");
      try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored){}
      return;
    }
    scanner = ba.getBluetoothLeScanner();
    if (scanner == null){ hintView.setText("Bluetooth is not ready — try again in a moment."); return; }

    final ArrayList<String> names = new ArrayList<>(), addrs = new ArrayList<>();
    final ArrayAdapter<String> list = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
    stopScan();
    picker = new AlertDialog.Builder(this)
      .setTitle("Looking for sensors — put the strap on…")
      .setAdapter(list, (d, which) -> chooseSensor(addrs.get(which)))
      .setNegativeButton("Cancel", null)
      .setOnDismissListener(d -> stopScan())
      .create();
    picker.show();
    scanCb = new ScanCallback(){
      @Override public void onScanResult(int type, ScanResult r){
        BluetoothDevice dev = r.getDevice();
        String name = null;
        try { name = dev.getName(); } catch (SecurityException ignored){}
        List<ParcelUuid> uuids = r.getScanRecord() == null ? null : r.getScanRecord().getServiceUuids();
        boolean hr = (uuids != null && uuids.contains(HR_UUID))
          || (name != null && name.toLowerCase(Locale.US).contains("polar"));
        if (!hr || addrs.contains(dev.getAddress())) return;
        addrs.add(dev.getAddress());
        names.add((name == null || name.isEmpty() ? "Heart-rate sensor" : name) + "  ·  " + dev.getAddress());
        list.notifyDataSetChanged();
        if (picker != null) picker.setTitle("Choose your sensor");
      }
      @Override public void onScanFailed(int code){
        if (picker != null) picker.setTitle("The scan failed (" + code + ") — cancel and try again.");
      }
    };
    try { scanner.startScan(scanCb); }
    catch (SecurityException e){ picker.dismiss(); hintView.setText("Bluetooth is not allowed for the app."); return; }
    final ScanCallback mine = scanCb;
    ui.postDelayed(() -> {
      if (scanCb != mine) return;                // already stopped, or a newer scan runs
      stopScan();
      if (picker != null && picker.isShowing() && addrs.isEmpty())
        picker.setTitle("No sensor found — wet the electrodes, put the strap on, and try again.");
    }, SCAN_MS);
  }

  private void chooseSensor(String address){
    stopScan();
    if (!strapLive) return;
    FatigueChecks.configureStrap(this, address);
    hintView.setText("");
    phaseView.setText("Connecting to the sensor — put it on, sit down.");
    HrService.setBeatListener((bpm, rrMs) -> ui.post(() -> onBeats(bpm, rrMs)));
    ui.removeCallbacks(tick);
    ui.postDelayed(tick, 500);
    // Nobody held the service: this test started it, so this test lets go of it.
    if (!HrService.isRunning()) ownsLink = true;
    connect(address);
  }

  private void stopScan(){
    if (scanner != null && scanCb != null){
      try { scanner.stopScan(scanCb); } catch (Exception ignored){}
    }
    scanCb = null;
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] res){
    super.onRequestPermissionsResult(code, perms, res);
    if (code != REQ_BT || !strapLive) return;
    for (int r : res) if (r != PackageManager.PERMISSION_GRANTED){
      hintView.setText("Bluetooth was not allowed — the strap test cannot reach the sensor without it.");
      return;
    }
    pickSensor();
  }

  /* A manual result is a record of its own, saved now: leaving the screen afterwards loses
     nothing, and each one is a line in the log and in the day's manual_test cell. */
  private void saveManual(JSONObject strap, boolean poor){
    long now = System.currentTimeMillis();
    long at = strap.optLong("from", now);
    try {
      JSONObject rec = new JSONObject();
      rec.put("id", UUID.randomUUID().toString());
      rec.put("checkId", "manual");
      rec.put("label", "Manual test");
      rec.put("anchor", "manual");
      rec.put("offsetMin", 0);
      rec.put("test", false);
      rec.put("dueAt", 0);
      rec.put("at", at);
      rec.put("endedAt", now);
      rec.put("localDay", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at)));
      JSONObject steps = new JSONObject();
      steps.put("strap", strap);
      rec.put("steps", steps);
      FatigueChecks.addResult(this, rec);
      stageUpload(rec);
    } catch (Exception ignored){}
    sitting.add(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)) + " — "
      + strap.optDouble("hr") + " bpm · " + strap.optDouble("rmssd") + " ms" + (poor ? " (unreliable)" : ""));
  }

  /** This sitting's results, oldest first. */
  private void showSitting(){
    if (sitting.isEmpty()) return;
    add(text("This sitting", 13, MUTED, false), 26);
    add(text(android.text.TextUtils.join("\n", sitting), 16, TEXT, false), 6);
  }

  /** Stop listening; and if this test asked for the strap, give it back. */
  private void endStrap(){
    stopScan();
    if (picker != null){ try { picker.dismiss(); } catch (Exception ignored){} picker = null; }
    strapLive = false; recording = false;
    ui.removeCallbacks(tick);
    HrService.setBeatListener(null);
    if (ownsLink){
      ownsLink = false;
      try {
        if (HrService.isRunning())
          startService(new Intent(this, HrService.class).setAction(HrService.ACTION_DISCONNECT));
      } catch (Exception ignored){}
    }
  }

  // ---- 2. PVT

  private void showPvt(){
    head("PVT");
    pvtShownAt = System.currentTimeMillis();
    add(text("Do the PVT on the computer now.\nTap Done when it is finished.", 17, MUTED, false), 22);
    button("Done", true, () -> {
      JSONObject r = status("done");
      try { r.put("askedAt", pvtShownAt); r.put("at", System.currentTimeMillis()); } catch (Exception ignored){}
      put("pvt", r); next();
    });
    button("Skip the PVT", false, () -> { put("pvt", status("skipped")); next(); });
  }

  // ---- 3. the fatigue question

  private void showQuestion(){
    head("");                        // the question brings its own title
    root.removeViewAt(root.getChildCount() - 1);
    boolean wake = !"sleep".equals(anchor);
    FatigueQuestion.buildQuestion(this, root, "How tired are you?",
      (label == null || label.isEmpty() ? "" : label + " — ") + "0 to 5.", FatigueQuestion.FATIGUE_LEVELS,
      wake ? "A note about the night (optional)" : "A note about the day (optional)",
      "Skip the question", new FatigueQuestion.Answer(){
        @Override public void onScore(int score, String note){
          JSONObject r = status("done");
          try { r.put("score", score); r.put("scale", FatigueQuestion.SCALE); r.put("note", note); r.put("at", System.currentTimeMillis()); } catch (Exception ignored){}
          put("question", r);
          if (!test){
            try {
              FatigueQuestion.stageRow(FatigueCheckActivity.this, score, note, day);
              UploadWorker.schedule(FatigueCheckActivity.this);
            } catch (Exception ignored){}   // staging failed: the record below still has it
          }
          next();
        }
        @Override public void onSkip(){ put("question", status("skipped")); next(); }
      });
  }

  // ---- 4. eyes

  private void showEyes(){
    head("");                        // the question brings its own title
    root.removeViewAt(root.getChildCount() - 1);
    FatigueQuestion.buildQuestion(this, root, "How are your eyes?",
      (label == null || label.isEmpty() ? "" : label + " — ") + "0 to 5.", FatigueQuestion.EYE_LEVELS,
      "A note about your eyes (optional)", "Skip the eyes", new FatigueQuestion.Answer(){
        @Override public void onScore(int score, String note){
          JSONObject r = status("done");
          try { r.put("score", score); r.put("scale", FatigueQuestion.SCALE); r.put("note", note); r.put("at", System.currentTimeMillis()); } catch (Exception ignored){}
          put("eyes", r);
          next();
        }
        @Override public void onSkip(){ put("eyes", status("skipped")); next(); }
      });
  }

  // ------------------------------------------------------------------ the record

  /* Straight into the upload outbox, due 2 hours from now: the page may not be opened again
     for hours, and the check must still reach the server on time. The same row the page
     sends (pushFatigueLog) — the page stages it again when it collects it, same id, same file.
     A test never leaves the phone. */
  private void stageUpload(JSONObject r){
    if (r.optBoolean("test") || Outbox.url(this).isEmpty()) return;
    try {
      long at = r.optLong("at"), ended = r.optLong("endedAt", at);
      JSONObject row = new JSONObject();
      row.put("id", r.optString("id"));
      row.put("started", iso(at));
      row.put("ended", iso(ended));
      row.put("localDay", r.optString("localDay"));
      row.put("checkId", r.optString("checkId"));
      row.put("label", r.optString("label"));
      row.put("anchor", r.optString("anchor"));
      row.put("offsetMin", r.optInt("offsetMin"));
      row.put("column", column(r.optString("anchor"), r.optInt("offsetMin")));
      row.put("test", false);
      row.put("dueAt", r.optLong("dueAt"));
      row.put("steps", r.optJSONObject("steps"));
      Outbox.put(this, row.optString("id"), row.toString(), "fatigue", "checks", "audio-timer",
                 ended + Outbox.HOLD_MS);
      UploadWorker.scheduleDue(this);
    } catch (Exception ignored){}                        // the page stages it when it collects it
  }

  private static String iso(long ms){
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return f.format(new Date(ms));
  }

  /* The page's fcColumn(): "+5min", "+1h", "+1h30", "-1h30 sleep". */
  static String column(String anchor, int offsetMin){
    if ("manual".equals(anchor)) return "manual_test";
    int m = Math.max(0, offsetMin);
    boolean sleep = "sleep".equals(anchor);
    String d = m < 60 ? m + "min" : (m / 60) + "h" + (m % 60 != 0 ? String.format(Locale.US, "%02d", m % 60) : "");
    return (sleep ? "-" : "+") + d + (sleep ? " sleep" : "");
  }

  /* Whatever was done is kept, whatever was not is said: a step never reached — the whole
     check skipped from the ring, or the screen closed half-way — reads "skipped". */
  private void finishCheck(){
    if (saved) return;
    saved = true;
    endStrap();
    if (manual){ finish(); return; }       // each result was saved as it came
    try {
      for (String s : steps) if (!results.has(s)) results.put(s, status("skipped"));
      record.put("steps", results);
      record.put("endedAt", System.currentTimeMillis());
      FatigueChecks.addResult(this, record);
      stageUpload(record);
      Toast.makeText(this, "Fatigue check saved — it is in the app's log.", Toast.LENGTH_SHORT).show();
    } catch (Exception ignored){}
    finish();
  }

  @Override
  public void onBackPressed(){
    // An alarm is answered, not backed out of — every screen has its Skip. A manual test is
    // no alarm: Back leaves it (a test in progress is dropped; finished ones are saved).
    if (manual) finishCheck();
  }

  @Override
  protected void onDestroy(){
    if (live == this) live = null;
    quiet();
    ui.removeCallbacksAndMessages(null);
    if (!saved && index >= 0) finishCheck();      // closed half-way: keep what was done
    else endStrap();
    super.onDestroy();
  }
}
