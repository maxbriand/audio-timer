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
 */
public class AlarmActivity extends Activity {
  private static final int BG = Color.parseColor("#10141a");
  private static final int SURFACE = Color.parseColor("#1b222c");
  private static final int TEXT = Color.parseColor("#e8ecf2");
  private static final int MUTED = Color.parseColor("#8a94a3");
  private static final int ACCENT = Color.parseColor("#7cc4ff");

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

  private int dp(int v){
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
      getResources().getDisplayMetrics()));
  }

  private void buildUi(){
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setGravity(Gravity.CENTER);
    root.setPadding(dp(28), dp(28), dp(28), dp(28));

    TextView title = new TextView(this);
    title.setText("How tired are you?");
    title.setTextColor(TEXT);
    title.setTextSize(26);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    TextView sub = new TextView(this);
    sub.setText("45 minutes since you got up — 10 is the maximum fatigue.");
    sub.setTextColor(MUTED);
    sub.setTextSize(15);
    sub.setGravity(Gravity.CENTER);
    sub.setPadding(0, dp(10), 0, dp(28));
    root.addView(sub);

    GridLayout grid = new GridLayout(this);
    grid.setColumnCount(5);
    for (int score = 1; score <= 10; score++){
      final int s = score;
      Button b = new Button(this);
      b.setText(String.valueOf(score));
      b.setTextSize(20);
      b.setTextColor(score >= 8 ? ACCENT : TEXT);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(SURFACE);
      bg.setCornerRadius(dp(14));
      b.setBackground(bg);
      GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
      lp.width = dp(56);
      lp.height = dp(56);
      lp.setMargins(dp(5), dp(5), dp(5), dp(5));
      b.setLayoutParams(lp);
      b.setOnClickListener(v -> answer(s));
      grid.addView(b);
    }
    LinearLayout.LayoutParams glp =
      new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
    glp.gravity = Gravity.CENTER_HORIZONTAL;
    grid.setLayoutParams(glp);
    root.addView(grid);

    Button skip = new Button(this);
    skip.setText("Not now");
    skip.setTextColor(MUTED);
    skip.setBackgroundColor(Color.TRANSPARENT);
    skip.setPadding(0, dp(30), 0, 0);
    skip.setOnClickListener(v -> dismiss());
    root.addView(skip);

    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(BG);
    sv.setFillViewport(true);
    sv.addView(root);
    setContentView(sv);
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

  private void answer(int score){
    quiet();
    try {
      stageRow(score);
      UploadWorker.schedule(this);
    } catch (Exception ignored){}    // staging failed: better a lost score than a stuck alarm
    finish();
  }

  private void dismiss(){
    quiet();
    finish();
  }

  /* The same shape the page's uploadBody() sends, so the receiver files it like any other
     row — plus the one new field. localDay is the wake-up's own day, carried through prefs
     from the moment of scheduling, so the score lands in the same day file as its night. */
  private void stageRow(int score) throws Exception {
    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    iso.setTimeZone(TimeZone.getTimeZone("UTC"));
    String now = iso.format(new Date());
    String day = FatigueAlarm.nightDay(this);
    if (day.isEmpty()){
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
    o.put("note", "");
    o.put("minutesUntouchedBeforeStop", 0);
    o.put("fatigueScore", score);
    Outbox.put(this, id, o.toString());
  }

  @Override
  protected void onDestroy(){
    quiet();
    super.onDestroy();
  }
}
