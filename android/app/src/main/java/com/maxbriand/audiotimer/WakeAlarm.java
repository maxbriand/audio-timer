package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/*
 * The daily wake-up alarm, at the time set in ⚙ — a classic alarm clock, and the
 * anchor CBT-I asks for: one regular wake time, whatever the night was.
 *
 * It recurs on its own: every fire re-arms the next day's BEFORE ringing, so a ring
 * that goes unanswered (a phone left ringing, a swiped shade) can never cost the
 * following morning. "I'm up" only silences today's; snooze is 10 minutes and replaces
 * the pending fire, which the snoozed ring then re-arms for tomorrow again. Only
 * clearing the time in ⚙ stops the cycle. It records nothing — the rise is still the
 * day-mode switch in the app, the alarm merely brings the morning.
 */
final class WakeAlarm {
  private static final String PREFS = "wakealarm";
  private static final String KEY_TIME = "time";        // "HH:MM", empty = off
  private static final String KEY_NEXT = "nextAt";      // epoch ms of the armed fire
  static final long SNOOZE_MS = 10 * 60 * 1000L;

  private WakeAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String time(Context c){ return prefs(c).getString(KEY_TIME, ""); }
  static long nextAt(Context c){ return prefs(c).getLong(KEY_NEXT, 0); }

  private static PendingIntent ring(Context c){
    Intent i = new Intent(c, WakeAlarmReceiver.class);
    return PendingIntent.getBroadcast(c, 30, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* The page pushes the time on boot (when one is set) and on save; an empty one is the
     off switch. Idempotent, so the boot push cannot double-arm anything. */
  static void configure(Context c, String time){
    if (time == null || time.isEmpty()){
      prefs(c).edit().clear().apply();
      ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(ring(c));
      return;
    }
    prefs(c).edit().putString(KEY_TIME, time).apply();
    scheduleNext(c);
  }

  /* Arm the next occurrence still ahead — today's if it has not passed, else tomorrow's. */
  static void scheduleNext(Context c){
    String t = time(c);
    if (t.isEmpty()) return;
    int h, m;
    try {
      String[] p = t.split(":");
      h = Integer.parseInt(p[0]);
      m = Integer.parseInt(p[1]);
    } catch (Exception e){
      return;                                   // an unparseable time arms nothing
    }
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, h);
    cal.set(Calendar.MINUTE, m);
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
    PendingIntent show = PendingIntent.getActivity(c, 31, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), ring(c));
  }
}
