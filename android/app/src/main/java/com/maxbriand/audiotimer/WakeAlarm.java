package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/*
 * The wake-up alarm — a clock that rings at the goal unless the last rise was too late.
 *
 * Two numbers are set in ⚙: the goal wake-up time, and the accepted delay, the number of
 * minutes past it that still count as an acceptable rise. The alarm is derived from the
 * last wake-up in the log, by one rule (Maxime, 2026-09-20):
 *
 *   last wake-up later than goal + delay  →  rings at that wake-up's hour − delay
 *   anything else, or no wake-up at all   →  rings at the goal
 *
 * So with 09:00 and 30 min: a rise at 11:00 gives 10:30, 10:35 gives 10:05, 09:40 gives
 * 09:10, and 09:30 sharp — still accepted — gives 09:00. The pulled-back hour is always
 * past the goal, because the rise it comes from was more than the delay past it. Naps are
 * not part of the protocol, so the last wake-up is taken as it is, whatever its hour.
 *
 * The log lives in the page, so the page hands the last wake-up over with every
 * configure() — on each launch, on save, and when a wake-up entry is deleted — and the
 * alarm is recomputed from it every time: same log, same alarm. It is kept here too, for
 * the two moments the page is not running: "I'm up" on the ring, and the re-arm on boot.
 * The alarm always falls on a day AFTER the wake-up it is derived from — a rise at 08:00
 * must not make today's 09:00 ring an hour later.
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
  private static final String KEY_LAST_WAKE = "lastWake";// epoch ms of the last logged wake-up
  static final long SNOOZE_MS = 10 * 60 * 1000L;

  private WakeAlarm(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String goal(Context c){ return prefs(c).getString(KEY_GOAL, ""); }
  static int delayMin(Context c){ return prefs(c).getInt(KEY_DELAY, 30); }
  static long nextAt(Context c){ return prefs(c).getLong(KEY_NEXT, 0); }
  static boolean snoozed(Context c){ return prefs(c).getBoolean(KEY_SNOOZED, false); }

  /** "HH:MM" of a moment — what the ring and the page call the alarm. */
  static String clock(long at){
    Calendar k = Calendar.getInstance();
    k.setTimeInMillis(at);
    return String.format(java.util.Locale.US, "%02d:%02d",
      k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
  }

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

  /* The page pushes goal + delay + the last wake-up of its log on every launch, on save and
     when a wake-up entry is deleted; an empty goal is the off switch. While an "I'm up" is
     still waiting to be written into the log, the page's last wake-up is one rise behind,
     so the one kept here stands until the page has caught up. */
  static void configure(Context c, String goal, int delay, long lastWake){
    if (goal == null || goal.isEmpty() || minutesOf(goal) < 0){
      prefs(c).edit().clear().apply();
      ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(ring(c));
      return;
    }
    SharedPreferences.Editor e = prefs(c).edit().putString(KEY_GOAL, goal).putInt(KEY_DELAY, delay);
    if (!prefs(c).getBoolean(KEY_PENDING, false)) e.putLong(KEY_LAST_WAKE, lastWake);
    e.apply();
    schedule(c);
  }

  /* Arm the alarm the rule gives for the last wake-up kept here. A snooze still counting
     down is this morning's ring, not tomorrow's alarm, and is armed again as it is — an app
     launch or a reboot in those ten minutes must not swallow it. */
  static long schedule(Context c){
    int goalMin = minutesOf(goal(c));
    if (goalMin < 0) return 0;
    long now = System.currentTimeMillis();
    if (snoozed(c) && nextAt(c) > now){
      arm(c, nextAt(c), true);
      return nextAt(c);
    }
    long lastWake = prefs(c).getLong(KEY_LAST_WAKE, 0);
    int target = goalMin;
    Calendar cal = Calendar.getInstance();
    if (lastWake > 0){
      Calendar w = Calendar.getInstance();
      w.setTimeInMillis(lastWake);
      int wokeMin = w.get(Calendar.HOUR_OF_DAY) * 60 + w.get(Calendar.MINUTE);
      int acceptedMin = (goalMin + delayMin(c)) % 1440;
      // Strictly later than the accepted limit: a rise ON the limit is still accepted.
      if (circDiff(wokeMin, acceptedMin) > 0) target = (wokeMin - delayMin(c) + 1440) % 1440;
      // Never the day of the wake-up itself; a wake-up in the future (a clock change) is
      // ignored for the date rather than pushing the alarm days away.
      if (lastWake <= now){ cal.setTimeInMillis(lastWake); cal.add(Calendar.DAY_OF_YEAR, 1); }
    }
    cal.set(Calendar.HOUR_OF_DAY, target / 60);
    cal.set(Calendar.MINUTE, target % 60);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    while (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1);
    arm(c, cal.getTimeInMillis(), false);
    return cal.getTimeInMillis();
  }

  /* A rise, reported by the "I'm up" button or by the page's day-mode switch: it is the
     last wake-up now, and the alarm follows from it by the same rule as everywhere else. */
  static long wokeAt(Context c, long wokeMillis){
    if (minutesOf(goal(c)) < 0) return 0;
    // The rise ends the morning: a snooze still pending has nothing left to ring for.
    prefs(c).edit().putLong(KEY_LAST_WAKE, wokeMillis).putBoolean(KEY_SNOOZED, false).apply();
    return schedule(c);
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
