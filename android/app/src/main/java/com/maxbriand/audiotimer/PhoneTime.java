package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/*
 * The screen sessions of Android's usage log, sent to the server as the Body asset's
 * phone-time.csv — one row per session: when the screen came on, how long it stayed on —
 * and each closed day's total, kept here for the ☀️ Day log.
 *
 * Nothing is recorded or staged here, because Android already keeps the log (about a week
 * and a half of events): every run reads the whole of it again and sends every finished
 * session it holds, and the server upserts by (start, end). So a run that fails loses
 * nothing, a phone that was off at midnight catches up at boot, and one offline for a few
 * days sends all of them at once — as long as it gets a network before the system drops
 * the oldest events.
 *
 * The pairing is the 📱 page's Timeline, kept to what a row needs: screen on → off; an off
 * with no on before it (the session began before the log does) is dropped rather than
 * guessed, since a guessed start would be a new key on every run; a startup closes whatever
 * the shutdown did not log at the last event before it; the session still on right now is
 * left for the next run. A session across midnight is cut there, so the rows of a date add
 * up to that date's screen time.
 */
final class PhoneTime {
  private static final String PREFS = "phonetime";
  private static final String KEY_OK_AT = "lastOkAt";

  private static final int SCREEN_ON = 15, SCREEN_OFF = 16, KEYGUARD_SHOWN = 17,
    KEYGUARD_HIDDEN = 18, DEVICE_SHUTDOWN = 26, DEVICE_STARTUP = 27;
  private static final long REACH = 12 * 86400000L;   // past what the system keeps anyway
  static final int MIDNIGHT_MIN = 5;                  // 00:05: the day just closed is complete

  private PhoneTime(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /* When the server last took the sessions: the log says "on the server" for the days
     that closed before it. */
  static void sent(Context c){ prefs(c).edit().putLong(KEY_OK_AT, System.currentTimeMillis()).apply(); }
  static long lastOkAt(Context c){ return prefs(c).getLong(KEY_OK_AT, 0); }

  static boolean granted(Context c){
    AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
    if (ops == null) return false;
    int mode = Build.VERSION.SDK_INT >= 29
      ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
      : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
    return mode == AppOpsManager.MODE_ALLOWED;
  }

  /* Every finished screen session the log still holds, as rows for the receiver. */
  static JSONArray rows(Context c) throws Exception {
    UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
    JSONArray out = new JSONArray();
    if (usm == null) return out;
    long now = System.currentTimeMillis();
    UsageEvents evs = usm.queryEvents(now - REACH, now);
    UsageEvents.Event e = new UsageEvents.Event();

    long on = -1, prevT = -1;
    boolean unlocked = false;
    Boolean locked = null;                 // unknown until the log says
    while (evs != null && evs.hasNextEvent()){
      evs.getNextEvent(e);
      long t = e.getTimeStamp();
      switch (e.getEventType()){
        case SCREEN_ON:
          if (on >= 0) add(out, on, t, unlocked);          // the log lost an "off"
          on = t; unlocked = Boolean.FALSE.equals(locked);
          break;
        case SCREEN_OFF:
          if (on >= 0) add(out, on, t, unlocked);
          on = -1;
          break;
        case KEYGUARD_HIDDEN:
          locked = false;
          if (on >= 0) unlocked = true;
          break;
        case KEYGUARD_SHOWN:
          locked = true;
          break;
        case DEVICE_SHUTDOWN:
          if (on >= 0) add(out, on, t, unlocked);
          on = -1; locked = true;
          break;
        case DEVICE_STARTUP:
          if (on >= 0 && prevT >= 0) add(out, on, prevT, unlocked);
          on = -1; locked = true;
          break;
      }
      prevT = t;
    }
    return out;
  }

  private static void add(JSONArray out, long on, long off, boolean unlocked) throws Exception {
    SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    long from = on;
    while (from < off){
      Calendar m = Calendar.getInstance();
      m.setTimeInMillis(from);
      m.set(Calendar.HOUR_OF_DAY, 0); m.set(Calendar.MINUTE, 0);
      m.set(Calendar.SECOND, 0); m.set(Calendar.MILLISECOND, 0);
      m.add(Calendar.DAY_OF_MONTH, 1);
      long to = Math.min(off, m.getTimeInMillis());
      JSONObject r = new JSONObject();
      r.put("date", day.format(new Date(from)));
      r.put("start", iso.format(new Date(from)));
      r.put("end", iso.format(new Date(to)));
      r.put("seconds", Math.round((to - from) / 1000.0));
      r.put("unlocked", unlocked);
      out.put(r);
      from = to;
    }
  }

  // ---- the day's total, for the ☀️ Day log. Kept here, not recomputed, because the log
  // behind it is gone after about ten days and the log page keeps every day.

  private static final String KEY_DAYS = "days";

  /* Every closed day the log covers gets its total: screen-on seconds (the rows of that
     date, cut at midnight) and how many times the screen came on. Today is not closed yet;
     the oldest date is not whole — the log starts part-way through it — so neither is kept.
     A day already kept is written again with the same numbers: harmless, and it means a run
     that missed a midnight (phone off) catches up by itself. */
  static void recordDays(Context c){
    if (!granted(c)) return;
    try {
      JSONArray rows = rows(c);
      TreeMap<String, long[]> byDay = new TreeMap<>();
      for (int i = 0; i < rows.length(); i++){
        JSONObject r = rows.getJSONObject(i);
        long[] t = byDay.get(r.getString("date"));
        if (t == null){ t = new long[2]; byDay.put(r.getString("date"), t); }
        t[0] += r.getLong("seconds");
        if (!r.getString("start").endsWith("T00:00:00")) t[1]++;   // a continuation is not a new "on"
      }
      if (byDay.isEmpty()) return;
      byDay.remove(byDay.firstKey());
      byDay.remove(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
      synchronized (PhoneTime.class){
        JSONObject days = days(c);
        for (Map.Entry<String, long[]> e : byDay.entrySet()){
          JSONObject o = new JSONObject();
          o.put("seconds", e.getValue()[0]);
          o.put("sessions", e.getValue()[1]);
          days.put(e.getKey(), o);
        }
        prefs(c).edit().putString(KEY_DAYS, days.toString()).apply();
      }
    } catch (Exception ignored){}
  }

  static JSONObject days(Context c){
    try { return new JSONObject(prefs(c).getString(KEY_DAYS, "{}")); }
    catch (Exception e){ return new JSONObject(); }
  }

  // ---- the nightly run: 00:05, re-armed at every fire, at boot and at every app start.

  private static PendingIntent tick(Context c){
    Intent i = new Intent(c, PhoneTimeReceiver.class);
    return PendingIntent.getBroadcast(c, 40, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void armMidnight(Context c){
    Calendar next = Calendar.getInstance();
    next.set(Calendar.HOUR_OF_DAY, 0); next.set(Calendar.MINUTE, MIDNIGHT_MIN);
    next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
    if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_MONTH, 1);
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return;
    // No alarm-clock icon for a silent job; a few minutes late under doze is fine.
    if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), tick(c));
    else
      am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), tick(c));
  }
}
