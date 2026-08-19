package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/*
 * The fatigue check behind the day-mode switch.
 *
 * Getting up starts a countdown, not a prompt: a score taken at the rise itself would mostly
 * measure sleep inertia, so the page schedules this at the moment of the wake-up row and the
 * question arrives 45 minutes later — ringing like an alarm clock whether or not the app is
 * still running. setAlarmClock is what makes it "exactly" an alarm: exact under doze, exempt
 * from battery optimisation, and it shows the alarm icon in the status bar like any other.
 *
 * The scheduled moment and the night it belongs to are also kept in prefs for one reason: a
 * phone that reboots inside the 45 minutes must still ask, which is BootReceiver's job.
 */
final class FatigueAlarm {
  private static final String PREFS = "fatiguealarm";
  private static final String KEY_AT = "at";
  private static final String KEY_DAY = "nightDay";

  private FatigueAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static long at(Context c){ return prefs(c).getLong(KEY_AT, 0); }
  static String nightDay(Context c){ return prefs(c).getString(KEY_DAY, ""); }

  private static PendingIntent ring(Context c){
    Intent i = new Intent(c, FatigueAlarmReceiver.class);
    return PendingIntent.getBroadcast(c, 0, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* Scheduling again before the previous alarm fired replaces it — rising twice in a
     morning means one question, 45 minutes after the LAST rise. */
  static void schedule(Context c, long atMillis, String nightDay){
    prefs(c).edit().putLong(KEY_AT, atMillis)
                   .putString(KEY_DAY, nightDay == null ? "" : nightDay).apply();
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    Intent open = new Intent(c, MainActivity.class);
    PendingIntent show = PendingIntent.getActivity(c, 1, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMillis, show), ring(c));
  }

  static void cancel(Context c){
    clear(c);
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    am.cancel(ring(c));
  }

  /* Answered, dismissed, or fired: the stored schedule has served. */
  static void clear(Context c){
    prefs(c).edit().remove(KEY_AT).remove(KEY_DAY).apply();
  }
}
