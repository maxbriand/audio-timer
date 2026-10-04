package com.maxbriand.audiotimer;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/*
 * The alarm screen: rings until answered, over the lock screen if that is where it lands.
 *
 * The answer is one tap on 1–10 (10 = maximum fatigue). It becomes a zero-length "fatigue"
 * row staged straight into the native outbox — the same shape the page's uploadBody() sends,
 * so the receiver, the day files and the diary treat it like any other row. It goes through
 * native code because the WebView is usually long gone 45 minutes after the rise; the page
 * never needs to know this row exists.
 *
 * "Not now" stops the ringing and writes nothing: a skipped morning is a blank diary cell,
 * never a guessed one.
 *
 * The question itself (buildQuestion) and the row it stages (stageRow) are shared with
 * FatigueCheckActivity, whose last step is this very question — one question, one row
 * shape, wherever it is asked from.
 */
public class AlarmActivity extends Activity {
  static final int BG = Color.parseColor("#10141a");
  static final int SURFACE = Color.parseColor("#1b222c");
  static final int TEXT = Color.parseColor("#e8ecf2");
  static final int MUTED = Color.parseColor("#8a94a3");
  static final int ACCENT = Color.parseColor("#7cc4ff");

  /** What the question hands back: a score with its note, or nothing at all. */
  interface Answer {
    void onScore(int score, String note);
    void onSkip();
  }

  private MediaPlayer player;
  private Vibrator vibrator;

  @Override
  protected void onCreate(Bundle savedInstanceState){
    super.onCreate(savedInstanceState);
    if (Build.VERSION.SDK_INT >= 27){
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                         | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    buildUi();
    ring();
  }

  static int dp(Context c, int v){
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
      c.getResources().getDisplayMetrics()));
  }

  private void buildUi(){
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setGravity(Gravity.CENTER);
    root.setPadding(dp(this, 28), dp(this, 28), dp(this, 28), dp(this, 28));
    buildQuestion(this, root, "45 minutes since you got up — 10 is the maximum fatigue.",
      "A note about the night (optional)", "Not now", new Answer(){
        @Override public void onScore(int score, String n){ answer(score, n); }
        @Override public void onSkip(){ dismiss(); }
      });
    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(BG);
    sv.setFillViewport(true);
    sv.addView(root);
    setContentView(sv);
  }

  /* The question: a 1–10 grid, a free note, a way out. Filled into `root`, so whoever asks
     decides what surrounds it. */
  static void buildQuestion(final Context c, LinearLayout root, String subtitle, String noteHint,
                            String skipLabel, final Answer cb){
    TextView title = new TextView(c);
    title.setText("How tired are you?");
    title.setTextColor(TEXT);
    title.setTextSize(26);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    TextView sub = new TextView(c);
    sub.setText(subtitle);
    sub.setTextColor(MUTED);
    sub.setTextSize(15);
    sub.setGravity(Gravity.CENTER);
    sub.setPadding(0, dp(c, 10), 0, dp(c, 28));
    root.addView(sub);

    final EditText note = new EditText(c);
    GridLayout grid = new GridLayout(c);
    grid.setColumnCount(5);
    for (int score = 1; score <= 10; score++){
      final int s = score;
      Button b = new Button(c);
      b.setText(String.valueOf(score));
      b.setTextSize(20);
      b.setTextColor(score >= 8 ? ACCENT : TEXT);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(SURFACE);
      bg.setCornerRadius(dp(c, 14));
      b.setBackground(bg);
      GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
      lp.width = dp(c, 56);
      lp.height = dp(c, 56);
      lp.setMargins(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
      b.setLayoutParams(lp);
      b.setOnClickListener(v -> cb.onScore(s,
        note.getText() == null ? "" : note.getText().toString().trim()));
      grid.addView(b);
    }
    LinearLayout.LayoutParams glp =
      new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
    glp.gravity = Gravity.CENTER_HORIZONTAL;
    grid.setLayoutParams(glp);
    root.addView(grid);

    /* The night note lives here (moved from the wake-up sheet, 2026-09-05): the fatigue
       check is the morning's one question, so the free-text observation about the night
       rides the same answer. Optional — an empty field stays an empty diary cell. */
    note.setHint(noteHint);
    note.setHintTextColor(MUTED);
    note.setTextColor(TEXT);
    note.setTextSize(15);
    GradientDrawable nbg = new GradientDrawable();
    nbg.setColor(SURFACE);
    nbg.setCornerRadius(dp(c, 14));
    note.setBackground(nbg);
    note.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    note.setMaxLines(3);
    LinearLayout.LayoutParams nlp =
      new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
    nlp.setMargins(0, dp(c, 24), 0, 0);
    note.setLayoutParams(nlp);
    root.addView(note);

    Button skip = new Button(c);
    skip.setText(skipLabel);
    skip.setTextColor(MUTED);
    skip.setBackgroundColor(Color.TRANSPARENT);
    skip.setPadding(0, dp(c, 30), 0, 0);
    skip.setOnClickListener(v -> cb.onSkip());
    root.addView(skip);
  }

  /* Loud on the alarm stream, like the clock app: unaffected by the media volume the player
     uses at night, and looping until a finger stops it. */
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
    } catch (Exception ignored){}    // a silent alarm screen still asks the question
    try {
      vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      long[] pattern = {0, 600, 500};
      if (Build.VERSION.SDK_INT >= 26){
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
      } else {
        vibrator.vibrate(pattern, 0);
      }
    } catch (Exception ignored){}
  }

  private void quiet(){
    if (player != null){
      try { player.stop(); } catch (Exception ignored){}
      player.release();
      player = null;
    }
    if (vibrator != null){
      vibrator.cancel();
      vibrator = null;
    }
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    nm.cancel(FatigueAlarmReceiver.NOTIF_ID);
  }

  private void answer(int score, String n){
    quiet();
    try {
      stageRow(this, score, n, FatigueAlarm.nightDay(this));
      UploadWorker.schedule(this);
    } catch (Exception ignored){}    // staging failed: better a lost score than a stuck alarm
    finish();
  }

  private void dismiss(){
    quiet();
    finish();
  }

  /* The same shape the page's uploadBody() sends, so the receiver files it like any other
     row — plus the one new field. localDay is the day the score is filed under: for the
     morning's answer the wake-up's own day, so it lands in the same day file as its night;
     empty means today. */
  static void stageRow(Context c, int score, String noteText, String day) throws Exception {
    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    iso.setTimeZone(TimeZone.getTimeZone("UTC"));
    String now = iso.format(new Date());
    if (day == null || day.isEmpty()){
      day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
    String id = UUID.randomUUID().toString();
    JSONObject o = new JSONObject();
    o.put("localDay", day);
    o.put("id", id);
    o.put("started", now);
    o.put("ended", now);
    o.put("listenedMinutes", 0);
    o.put("timerMinutes", JSONObject.NULL);
    o.put("timerCancelled", false);
    o.put("timerAutoArmed", false);
    o.put("speed", 1);
    o.put("fadeInSeconds", 0);
    o.put("stopReason", "fatigue");
    o.put("trackStart", "");
    o.put("trackEnd", "");
    o.put("stopPositionSeconds", 0);
    o.put("note", noteText == null ? "" : noteText);
    o.put("minutesUntouchedBeforeStop", 0);
    o.put("fatigueScore", score);
    Outbox.put(c, id, o.toString());
  }

  @Override
  protected void onDestroy(){
    quiet();
    super.onDestroy();
  }
}
