package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/*
 * The wake-up alarm — a clock that walks the rise back towards its goal.
 *
 * Two numbers are set in ⚙: the goal wake-up time, and the accepted delay, the number of
 * minutes past it that still count as an acceptable rise. Tomorrow's alarm is decided at
 * the moment of the rise, by one rule (Maxime, 2026-09-09):
 *
 *   rose within ±30 min of goal+delay  →  tomorrow rings 30 min before that rise
 *   anything else                      →  tomorrow rings at the goal
 *
 * So a morning that lands near the tolerated limit pulls the next one earlier, half an
 * hour at a time, and a morning far from it simply returns to the goal. The result is
 * clamped so it can never fall EARLIER than the goal: the goal is the destination, not a
 * floor to undercut, and without the clamp a rise at the goal itself would keep advancing
 * the alarm indefinitely.
 *
 * Snoozing is allowed once. The second ring offers only "I'm up", which is the rise: it
 * takes the app to day mode, where the wake-up row is written like any other.
 */
final class WakeAlarm {
  private static final String PREFS = "wakealarm";
  private static final String KEY_GOAL = "goal";        // "HH:MM", empty = off
  private static final String KEY_DELAY = "delayMin";   // accepted minutes past the goal
  private static final String KEY_NEXT = "nextAt";      // epoch ms of the armed fire
  private static final String KEY_SNOOZED = "snoozed";  // this ring has been snoozed once
  private static final String KEY_PENDING = "pendingUp";// "I'm up" pressed, page not told yet
  private static final String KEY_UP_AT = "upAt";       // when it was pressed
  static final long SNOOZE_MS = 10 * 60 * 1000L;
  static final int WINDOW_MIN = 30;                     // "more or less 30 minutes around"
  static final int ADVANCE_MIN = 30;                    // how much a near-limit rise pulls back

  private WakeAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String goal(Context c){ return prefs(c).getString(KEY_GOAL, ""); }
  static int delayMin(Context c){ return prefs(c).getInt(KEY_DELAY, 30); }
  static long nextAt(Context c){ return prefs(c).getLong(KEY_NEXT, 0); }
  static boolean snoozed(Context c){ return prefs(c).getBoolean(KEY_SNOOZED, false); }

  /** Minutes past midnight for "HH:MM", or -1 when it cannot be read. */
  private static int minutesOf(String hhmm){
    try {
      String[] p = hhmm.split(":");
      return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    } catch (Exception e){
      return -1;
    }
  }

  /** Signed shortest distance a → b on the 24 h circle, in minutes (-720…720). */
  private static int circDiff(int a, int b){
    return ((a - b + 720 + 1440) % 1440) - 720;
  }

  private static PendingIntent ring(Context c){
    Intent i = new Intent(c, WakeAlarmReceiver.class);
    return PendingIntent.getBroadcast(c, 30, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* The page pushes goal + delay on boot and on save; an empty goal is the off switch. */
  static void configure(Context c, String goal, int delay){
    if (goal == null || goal.isEmpty() || minutesOf(goal) < 0){
      prefs(c).edit().clear().apply();
      ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(ring(c));
      return;
    }
    prefs(c).edit().putString(KEY_GOAL, goal).putInt(KEY_DELAY, delay).apply();
    scheduleNext(c);
  }

  /** The plain schedule: the next goal time still ahead. */
  static void scheduleNext(Context c){
    int goalMin = minutesOf(goal(c));
    if (goalMin < 0) return;
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, goalMin / 60);
    cal.set(Calendar.MINUTE, goalMin % 60);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
    arm(c, cal.getTimeInMillis(), false);
  }

  /* The rise happened: decide tomorrow's alarm from it and arm that instead. Called from
     the "I'm up" button and from the page's day-mode switch, so whichever records the
     rise, the next morning is set by the same rule. */
  static long wokeAt(Context c, long wokeMillis){
    int goalMin = minutesOf(goal(c));
    if (goalMin < 0) return 0;
    Calendar w = Calendar.getInstance();
    w.setTimeInMillis(wokeMillis);
    int wokeMin = w.get(Calendar.HOUR_OF_DAY) * 60 + w.get(Calendar.MINUTE);
    int acceptedMin = (goalMin + delayMin(c) + 1440) % 1440;

    int target;
    if (Math.abs(circDiff(wokeMin, acceptedMin)) <= WINDOW_MIN){
      target = (wokeMin - ADVANCE_MIN + 1440) % 1440;
      if (circDiff(target, goalMin) < 0) target = goalMin;   // never earlier than the goal
    } else {
      target = goalMin;
    }

    w.set(Calendar.HOUR_OF_DAY, target / 60);
    w.set(Calendar.MINUTE, target % 60);
    w.set(Calendar.SECOND, 0);
    w.set(Calendar.MILLISECOND, 0);
    w.add(Calendar.DAY_OF_YEAR, 1);                          // the alarm clock of tomorrow
    arm(c, w.getTimeInMillis(), false);
    return w.getTimeInMillis();
  }

  /** Snooze — allowed once per ring; the flag is what makes the next ring offer one button. */
  static void snooze(Context c){
    prefs(c).edit().putBoolean(KEY_SNOOZED, true).apply();
    arm(c, System.currentTimeMillis() + SNOOZE_MS, true);
  }

  /* "I'm up": the rise. Tomorrow is computed from this moment, and the page is left a note
     so it can switch to day mode and write the wake-up row when it next runs. */
  static void up(Context c, long at){
    prefs(c).edit().putBoolean(KEY_PENDING, true).putLong(KEY_UP_AT, at).apply();
    wokeAt(c, at);
  }

  static long consumeUp(Context c){
    if (!prefs(c).getBoolean(KEY_PENDING, false)) return 0;
    long at = prefs(c).getLong(KEY_UP_AT, System.currentTimeMillis());
    prefs(c).edit().putBoolean(KEY_PENDING, false).apply();
    return at;
  }

  private static void arm(Context c, long at, boolean isSnooze){
    SharedPreferences.Editor e = prefs(c).edit().putLong(KEY_NEXT, at);
    if (!isSnooze) e.putBoolean(KEY_SNOOZED, false);        // a fresh morning, a fresh snooze
    e.apply();
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    Intent open = new Intent(c, MainActivity.class);
    PendingIntent show = PendingIntent.getActivity(c, 31, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), ring(c));
  }
}
