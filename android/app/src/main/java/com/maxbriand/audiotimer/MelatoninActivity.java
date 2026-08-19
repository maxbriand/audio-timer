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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/*
 * The melatonin alarm screen: rings until Taken or Snooze, over the lock screen if that
 * is where it lands. Back does nothing on purpose — the contract is that only "Taken ✓"
 * closes the reminder for the day, and "Snooze 10 min" merely postpones it. Leaving the
 * screen any other way leaves the un-swipeable notification behind, still asking.
 */
public class MelatoninActivity extends Activity {
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

  @Override
  public void onBackPressed(){
    // Deliberately nothing: Taken or Snooze are the only exits.
  }

  private int dp(int v){
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
      getResources().getDisplayMetrics()));
  }

  private Button pill(String label, int color){
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(18);
    b.setTextColor(color);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(SURFACE);
    bg.setCornerRadius(dp(16));
    b.setBackground(bg);
    b.setPadding(dp(24), dp(16), dp(24), dp(16));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, dp(8), 0, 0);
    b.setLayoutParams(lp);
    return b;
  }

  private void buildUi(){
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setGravity(Gravity.CENTER);
    root.setPadding(dp(28), dp(28), dp(28), dp(28));

    TextView title = new TextView(this);
    title.setText("Melatonin — 0.5 mg");
    title.setTextColor(TEXT);
    title.setTextSize(26);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    TextView sub = new TextView(this);
    String bt = MelatoninAlarm.bedtime(this);
    sub.setText(bt.isEmpty() ? "Time to take it."
                             : "5 h before your " + bt + " bedtime — take it now.");
    sub.setTextColor(MUTED);
    sub.setTextSize(15);
    sub.setGravity(Gravity.CENTER);
    sub.setPadding(0, dp(10), 0, dp(28));
    root.addView(sub);

    Button taken = pill("Taken ✓", ACCENT);
    taken.setOnClickListener(v -> {
      quiet();
      MelatoninAlarm.taken(this);
      finish();
    });
    root.addView(taken);

    Button snooze = pill("Snooze 10 min", MUTED);
    snooze.setOnClickListener(v -> {
      quiet();
      MelatoninAlarm.snooze(this);
      finish();
    });
    root.addView(snooze);

    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(BG);
    sv.setFillViewport(true);
    sv.addView(root);
    setContentView(sv);
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
    } catch (Exception ignored){}
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

  /* Stops the noise only — the notification is TAKEN's to cancel (or Snooze's), so an
     activity that dies any other way leaves the reminder standing, as intended. */
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
    nm.cancel(MelatoninReceiver.NOTIF_ID);
  }

  @Override
  protected void onDestroy(){
    if (player != null){
      try { player.stop(); } catch (Exception ignored){}
      player.release();
      player = null;
    }
    if (vibrator != null){
      vibrator.cancel();
      vibrator = null;
    }
    super.onDestroy();
  }
}
