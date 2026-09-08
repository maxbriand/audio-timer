package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/*
 * The morning-light reminder behind the day-mode switch — the walk's safety net.
 *
 * Morning light is the day's first zeitgeber, and the ☀️ Morning walk tap is its log. This
 * alarm exists for the mornings the tap doesn't happen: armed at the wake-up row (only when
 * the ⚙ toggle is on and no walk is logged yet), it rings 30 minutes after the rise with a
 * single answer — Done — and Done writes the very same marker row the manual tap would
 * have, stamped at the moment of the press. Logging the walk by hand inside the window
 * cancels the ring; so does going back to bed (a reminder ringing into a nap is noise).
 *
 * Same alarm-clock machinery as the fatigue check: setAlarmClock for exactness under doze,
 * the moment kept in prefs so BootReceiver can re-arm across a reboot.
 */
final class WalkAlarm {
  private static final String PREFS = "walkalarm";
  private static final String KEY_AT = "at";

  private WalkAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static long at(Context c){ return prefs(c).getLong(KEY_AT, 0); }

  private static PendingIntent ring(Context c){
    Intent i = new Intent(c, WalkAlarmReceiver.class);
    return PendingIntent.getBroadcast(c, 20, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* Re-scheduling replaces: rising twice in a morning means one reminder, 30 minutes
     after the LAST rise. */
  static void schedule(Context c, long atMillis){
    prefs(c).edit().putLong(KEY_AT, atMillis).apply();
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    Intent open = new Intent(c, MainActivity.class);
    PendingIntent show = PendingIntent.getActivity(c, 21, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMillis, show), ring(c));
  }

  static void cancel(Context c){
    clear(c);
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    am.cancel(ring(c));
  }

  /* Fired, answered, or overtaken by the manual tap: the stored schedule has served. */
  static void clear(Context c){
    prefs(c).edit().remove(KEY_AT).apply();
  }

  /* "Done": the same zero-length morning-walk marker the day screen's tap stages, stamped
     now — the walk IS the daylight log, whichever surface recorded it. */
  static void done(Context c){
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
      o.put("stopReason", "morning-walk");
      o.put("trackStart", "");
      o.put("trackEnd", "");
      o.put("stopPositionSeconds", 0);
      o.put("note", "");
      o.put("minutesUntouchedBeforeStop", 0);
      Outbox.put(c, id, o.toString());
      UploadWorker.schedule(c);
    } catch (Exception ignored){}    // an unrecorded walk must not leave the alarm stuck
    clear(c);
  }
}
