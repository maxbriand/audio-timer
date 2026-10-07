package com.maxbriand.audiotimer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/*
 * The fatigue check's questions: the screen's colours, the 0–5 question built into a screen
 * (fatigue, eyes), and the zero-length "fatigue" row the fatigue answer stages for the night
 * log — the same shape the page's uploadBody() sends, so the receiver, the day files and the
 * diary read the morning score where they always did.
 *
 * Until 2026-10-07 this lived in AlarmActivity, the screen of the single question that rang
 * 45 minutes after the rise (FatigueAlarm). The configurable checks replaced it, and it was
 * removed (Maxime): no fatigue alarm rings without a check made in ⚙ → Day.
 */
final class FatigueQuestion {
  private FatigueQuestion(){}

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

  static int dp(Context c, int v){
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
      c.getResources().getDisplayMetrics()));
  }

  /* The two scales a check asks on (Maxime, 2026-10-06): 0–5, each level a sentence, so a
     score means the same thing every day. They replaced the bare 1–10 grid; an answer on them
     is sent with its scale (5), so it is never read as a 1–10 score. */
  static final String[] FATIGUE_LEVELS = {
    "Full of energy",
    "Neutral, fresh",
    "A bit tired, no impact on what I do",
    "Tired; I can still work but it takes effort or extra breaks",
    "Struggling to keep focus on anything",
    "Exhausted; I have to stop and lie down" };
  static final String[] EYE_LEVELS = {
    "Nothing",
    "Aware of my eyes (dry, heavy), not uncomfortable",
    "Uncomfortable, but it doesn't change what I do",
    "I need extra eye breaks, but I can keep using screens",
    "I have to cut screen time or switch to non-screen tasks",
    "Painful; I have to stop screens and close my eyes" };
  static final int SCALE = 5;

  /* The question: the title, the optional note first (so it is typed before the level is
     tapped — the tap answers), one row per level, a way out. Filled into `root`, so whoever
     asks decides what surrounds it. */
  static void buildQuestion(final Context c, LinearLayout root, String heading, String subtitle,
                            String[] levels, String noteHint, String skipLabel, final Answer cb){
    TextView title = new TextView(c);
    title.setText(heading);
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
    sub.setPadding(0, dp(c, 10), 0, dp(c, 18));
    root.addView(sub);

    /* The night note lives here (moved from the wake-up sheet, 2026-09-05): the fatigue
       check is the morning's one question, so the free-text observation about the night
       rides the same answer. Optional — an empty field stays an empty diary cell. */
    final EditText note = new EditText(c);
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
    nlp.setMargins(0, 0, 0, dp(c, 14));
    note.setLayoutParams(nlp);
    root.addView(note);

    for (int score = 0; score < levels.length; score++){
      final int s = score;
      Button b = new Button(c);
      b.setText(score + "   " + levels[score]);
      b.setAllCaps(false);
      b.setTextSize(16);
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setTextColor(score >= 4 ? ACCENT : TEXT);
      b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(SURFACE);
      bg.setCornerRadius(dp(c, 14));
      b.setBackground(bg);
      LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                      LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, dp(c, 4), 0, dp(c, 4));
      b.setLayoutParams(lp);
      b.setOnClickListener(v -> cb.onScore(s,
        note.getText() == null ? "" : note.getText().toString().trim()));
      root.addView(b);
    }

    Button skip = new Button(c);
    skip.setText(skipLabel);
    skip.setTextColor(MUTED);
    skip.setBackgroundColor(Color.TRANSPARENT);
    skip.setPadding(0, dp(c, 24), 0, 0);
    skip.setOnClickListener(v -> cb.onSkip());
    root.addView(skip);
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
    o.put("fatigueScale", SCALE);     // 0–5 since 2026-10-06; a row without it is 1–10
    // Held like every other log (Outbox.HOLD_MS).
    Outbox.put(c, id, o.toString(), "", "sessions", "", System.currentTimeMillis() + Outbox.HOLD_MS);
  }
}
