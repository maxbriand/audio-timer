package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/*
 * The daily melatonin reminder, anchored to the bedtime set in ⚙.
 *
 * The dose is chronobiotic, not hypnotic: 0.5 mg taken ~5 hours before bedtime is what
 * shifts the clock, so the reminder time is DERIVED (bedtime − 5 h) rather than set
 * directly — moving the bedtime in the settings moves the reminder with it.
 *
 * Unlike the fatigue alarm this one recurs: every fire re-arms the next day's, and only
 * clearing the bedtime in ⚙ stops the cycle. "Taken" is the sole way to silence a ring
 * for good (it also stages a zero-length "melatonin" row into the outbox, so the moment
 * of the dose reaches the day files like everything else); snooze is 10 minutes.
 */
final class MelatoninAlarm {
  private static final String PREFS = "melatonin";
  private static final String KEY_BEDTIME = "bedtime";   // "HH:MM", empty = off
  private static final String KEY_NEXT = "nextAt";       // epoch ms of the armed fire
  static final long LEAD_MIN = 5 * 60;                   // reminder sits 5 h before bed
  static final long SNOOZE_MS = 10 * 60 * 1000L;

  private MelatoninAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String bedtime(Context c){ return prefs(c).getString(KEY_BEDTIME, ""); }
  static long nextAt(Context c){ return prefs(c).getLong(KEY_NEXT, 0); }

  private static PendingIntent ring(Context c){
    Intent i = new Intent(c, MelatoninReceiver.class);
    return PendingIntent.getBroadcast(c, 10, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* The page pushes the bedtime on every boot and on every save; an empty one is the
     off switch. Idempotent, so the boot push cannot double-arm anything. */
  static void configure(Context c, String bedtime){
    if (bedtime == null || bedtime.isEmpty()){
      prefs(c).edit().clear().apply();
      ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(ring(c));
      return;
    }
    prefs(c).edit().putString(KEY_BEDTIME, bedtime).apply();
    scheduleNext(c);
  }

  /* Arm the next bedtime−5h that is still ahead — today's if it has not passed, else
     tomorrow's. A bedtime after midnight puts the reminder on the evening before it. */
  static void scheduleNext(Context c){
    String bt = bedtime(c);
    if (bt.isEmpty()) return;
    int h, m;
    try {
      String[] p = bt.split(":");
      h = Integer.parseInt(p[0]);
      m = Integer.parseInt(p[1]);
    } catch (Exception e){
      return;                                   // an unparseable bedtime arms nothing
    }
    long remindMin = ((h * 60L + m) - LEAD_MIN + 1440) % 1440;
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, (int)(remindMin / 60));
    cal.set(Calendar.MINUTE, (int)(remindMin % 60));
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    if (cal.getTimeInMillis() <= System.currentTimeMillis()){
      cal.add(Calendar.DAY_OF_YEAR, 1);
    }
    arm(c, cal.getTimeInMillis());
  }

  static void snooze(Context c){
    arm(c, System.currentTimeMillis() + SNOOZE_MS);
  }

  private static void arm(Context c, long at){
    prefs(c).edit().putLong(KEY_NEXT, at).apply();
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    Intent open = new Intent(c, MainActivity.class);
    PendingIntent show = PendingIntent.getActivity(c, 11, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), ring(c));
  }

  /* "Taken": record the moment as a row the pipeline already knows how to carry —
     stopReason "melatonin", zero-length, no new field needed — then arm tomorrow's. */
  static void taken(Context c){
    try {
      SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
      iso.setTimeZone(TimeZone.getTimeZone("UTC"));
      String now = iso.format(new Date());
      String id = UUID.randomUUID().toString();
      JSONObject o = new JSONObject();
      o.put("localDay", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
      o.put("id", id);
      o.put("started", now);
      o.put("ended", now);
      o.put("listenedMinutes", 0);
      o.put("timerMinutes", JSONObject.NULL);
      o.put("timerCancelled", false);
      o.put("timerAutoArmed", false);
      o.put("speed", 1);
      o.put("fadeInSeconds", 0);
      o.put("stopReason", "melatonin");
      o.put("trackStart", "");
      o.put("trackEnd", "");
      o.put("stopPositionSeconds", 0);
      o.put("note", "");
      o.put("minutesUntouchedBeforeStop", 0);
      Outbox.put(c, id, o.toString());
      UploadWorker.schedule(c);
    } catch (Exception ignored){}    // an unrecorded dose must not leave the alarm stuck
    scheduleNext(c);
  }
}
