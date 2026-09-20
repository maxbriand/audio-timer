package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Fatigue tracking — any number of checks a day, each hung on one of the day's two fixed
 * points (⚙ → Day):
 *
 *   wake   →  rings at the last logged wake-up + its delay      ("5 min after wake-up")
 *   sleep  →  rings at the next bedtime − its delay              ("1 h before sleep"),
 *             bedtime being the next wake-up alarm − the sleep duration set in ⚙ → Sleep
 *
 * and each made of up to three steps, always in this order: the strap test (resting HR and
 * HRV), the PVT (done on the computer — the phone only asks when it is over), the fatigue
 * question. This replaces the single question that used to ring 45 minutes after the rise:
 * that one is now simply the first check of the list, and can be edited like any other.
 *
 * The list lives in the page, which pushes it here with everything the two anchors need;
 * from then on the clocks are native, like every other alarm of the app — they must ring
 * with the page long gone. Both anchors move without the page too ("I'm up" on the wake-up
 * ring is a rise; every wake-up alarm armed is a new bedtime), so WakeAlarm calls in here
 * rather than waiting for the app to be opened.
 *
 * A check rings once per anchor: the anchor it last rang for is remembered, so the re-arm
 * that follows every app launch cannot make this morning's check ring a second time.
 *
 * What a check collected is one record. It waits here until the page has written it into
 * its log and said so (results / ack) — a record handed over and then lost to a failed
 * write would be a test that never happened.
 */
final class FatigueChecks {
  private static final String PREFS = "fatiguechecks";
  private static final String KEY_CHECKS = "checks";      // JSON array, as the page sent it
  private static final String KEY_LAST_WAKE = "lastWake"; // epoch ms
  private static final String KEY_SLEEP_MIN = "sleepMin"; // the night's length, minutes
  private static final String KEY_STRAP = "strap";        // the H10's address, for the strap test
  private static final String KEY_RESULTS = "results";    // JSON array of records not yet acked
  private static final String FIRED = "firedFor:";        // + id → the anchor it last rang for
  private static final String ARMED_AT = "armedAt:";      // + id → the armed moment
  private static final String ARMED_FOR = "armedFor:";    // + id → the anchor it is armed for
  static final int MAX = 20;                              // alarm slots; the page enforces it too
  private static final int REQ_BASE = 200;
  private static final long LATE_MS = 30 * 60000L;        // a ring missed by less still rings

  private FatigueChecks(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static JSONArray checks(Context c){
    try { return new JSONArray(prefs(c).getString(KEY_CHECKS, "[]")); }
    catch (Exception e){ return new JSONArray(); }
  }

  static JSONObject check(Context c, String id){
    JSONArray all = checks(c);
    for (int i = 0; i < all.length(); i++){
      JSONObject k = all.optJSONObject(i);
      if (k != null && id != null && id.equals(k.optString("id"))) return k;
    }
    return null;
  }

  static String strap(Context c){ return prefs(c).getString(KEY_STRAP, ""); }

  /** "5 min after wake-up", "1 h 30 before sleep" — what the ring and the log call a check. */
  static String label(JSONObject k){
    int m = Math.max(0, k.optInt("offsetMin", 0));
    String d = m == 0 ? "" : m < 60 ? m + " min " : (m / 60) + " h " + (m % 60 == 0 ? "" : (m % 60 < 10 ? "0" : "") + (m % 60) + " ");
    boolean wake = !"sleep".equals(k.optString("anchor"));
    if (m == 0) return wake ? "at wake-up" : "at sleep time";
    return d + (wake ? "after wake-up" : "before sleep");
  }

  /* The next bedtime: the armed wake-up alarm minus the night's length. A snooze is this
     morning still ringing, not tomorrow's alarm — no bedtime can be read off it. */
  static long bedtime(Context c){
    long wake = WakeAlarm.nextAt(c);
    if (wake == 0 || WakeAlarm.goal(c).isEmpty() || WakeAlarm.snoozed(c)) return 0;
    return wake - prefs(c).getInt(KEY_SLEEP_MIN, 540) * 60000L;
  }

  private static long anchor(Context c, JSONObject k){
    return "sleep".equals(k.optString("anchor")) ? bedtime(c) : prefs(c).getLong(KEY_LAST_WAKE, 0);
  }

  /** When a check rings for the anchor given — after a wake-up, before a bedtime. */
  private static long fireAt(JSONObject k, long anchor){
    long off = Math.max(0, k.optInt("offsetMin", 0)) * 60000L;
    return "sleep".equals(k.optString("anchor")) ? anchor - off : anchor + off;
  }

  private static PendingIntent ring(Context c, int slot, String id){
    Intent i = new Intent(c, FatigueCheckReceiver.class)
      .setData(Uri.parse("fatiguecheck://slot/" + slot))
      .putExtra(FatigueCheckActivity.EXTRA_CHECK_ID, id == null ? "" : id);
    return PendingIntent.getBroadcast(c, REQ_BASE + slot, i,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /* Everything the page knows, in one call — on every launch, on save, and whenever the
     sleep schedule is pushed. A last wake-up of 0 means the page's log has none. */
  static void configure(Context c, String checksJson, long lastWake, int sleepMin, String strap){
    SharedPreferences.Editor e = prefs(c).edit();
    try { e.putString(KEY_CHECKS, new JSONArray(checksJson == null ? "[]" : checksJson).toString()); }
    catch (Exception bad){ /* an unreadable list keeps the one already here */ }
    // The page's log is the authority on the last rise (a wake-up entry can be deleted),
    // but 0 is "could not tell", never "there is none": a failed read must not cancel a
    // check that is counting down. A rise the page has not caught up with yet ("I'm up"
    // with the app closed) is handed back by the page itself a moment later, via wokeAt.
    if (lastWake > 0) e.putLong(KEY_LAST_WAKE, lastWake);
    e.putInt(KEY_SLEEP_MIN, sleepMin);
    if (strap != null && !strap.isEmpty()) e.putString(KEY_STRAP, strap);
    e.apply();
    rearm(c);
  }

  /** The strap's address alone — the Test button may run before any check is saved. */
  static void configureStrap(Context c, String strap){
    prefs(c).edit().putString(KEY_STRAP, strap).apply();
  }

  /** A rise — from the page's day-mode switch, or from "I'm up" with the page gone. */
  static void wokeAt(Context c, long at){
    prefs(c).edit().putLong(KEY_LAST_WAKE, at).apply();
    rearm(c);
  }

  /* Back to bed: the morning is over, and a check still counting down from the rise has
     nothing left to ask about. Marked as rung for that rise, so no re-arm brings it back. */
  static void cancelWake(Context c){
    long wake = prefs(c).getLong(KEY_LAST_WAKE, 0);
    JSONArray all = checks(c);
    SharedPreferences.Editor e = prefs(c).edit();
    for (int i = 0; i < all.length(); i++){
      JSONObject k = all.optJSONObject(i);
      if (k == null || "sleep".equals(k.optString("anchor"))) continue;
      e.putLong(FIRED + k.optString("id"), wake);
    }
    e.apply();
    rearm(c);
  }

  /* Cancel every slot, then arm each check whose moment is still ahead and which has not
     already rung for this anchor. Called from everywhere an anchor can move. */
  static void rearm(Context c){
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return;
    for (int s = 0; s < MAX; s++) am.cancel(ring(c, s, null));
    JSONArray all = checks(c);
    long now = System.currentTimeMillis();
    SharedPreferences p = prefs(c);
    SharedPreferences.Editor e = p.edit();
    for (int i = 0; i < all.length() && i < MAX; i++){
      JSONObject k = all.optJSONObject(i);
      if (k == null) continue;
      String id = k.optString("id");
      e.remove(ARMED_AT + id).remove(ARMED_FOR + id);
      long a = anchor(c, k);
      if (a == 0) continue;
      long at = fireAt(k, a);
      if (at <= now || p.getLong(FIRED + id, 0) == a) continue;
      e.putLong(ARMED_AT + id, at).putLong(ARMED_FOR + id, a);
      Intent open = new Intent(c, MainActivity.class);
      PendingIntent show = PendingIntent.getActivity(c, REQ_BASE + MAX + i, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), ring(c, i, id));
    }
    e.apply();
  }

  /** The armed moment of a check, 0 when it is not armed — what ⚙ shows under each one. */
  static long armedAt(Context c, String id){ return prefs(c).getLong(ARMED_AT + id, 0); }

  /** It rang: never again for this anchor. */
  static void fired(Context c, String id){
    SharedPreferences p = prefs(c);
    p.edit().putLong(FIRED + id, p.getLong(ARMED_FOR + id, 0))
            .remove(ARMED_AT + id).remove(ARMED_FOR + id).apply();
  }

  /* A reboot drops every alarm. Re-arm what is still ahead; a check whose moment passed
     while the phone was off rings now if that was under half an hour ago — a late check is
     a check, but an evening one at breakfast is not. */
  static void onBoot(Context c){
    JSONArray all = checks(c);
    long now = System.currentTimeMillis();
    for (int i = 0; i < all.length(); i++){
      JSONObject k = all.optJSONObject(i);
      if (k == null) continue;
      String id = k.optString("id");
      long at = armedAt(c, id);
      if (at != 0 && at <= now && now - at < LATE_MS){
        fired(c, id);
        FatigueCheckReceiver.show(c, id, at);
      }
    }
    rearm(c);
  }

  // ------------------------------------------------------------------ the strap test's arithmetic

  /* RMSSD — the root of the mean squared difference between successive beat-to-beat
     intervals — over a chain where NaN marks a hole: a difference is only taken between two
     intervals that really followed each other. Returns {intervals kept, RMSSD in ms (NaN
     when not one pair was left), mean interval in ms}. */
  static double[] hrv(java.util.List<Float> chain){
    double sq = 0, sum = 0; int pairs = 0, n = 0; float prev = Float.NaN;
    for (float v : chain){
      if (!Float.isNaN(v)){
        n++; sum += v;
        if (!Float.isNaN(prev)){ double d = v - prev; sq += d * d; pairs++; }
      }
      prev = v;
    }
    return new double[]{ n, pairs == 0 ? Double.NaN : Math.sqrt(sq / pairs), n == 0 ? Double.NaN : sum / n };
  }

  // ------------------------------------------------------------------ results

  static JSONArray results(Context c){
    try { return new JSONArray(prefs(c).getString(KEY_RESULTS, "[]")); }
    catch (Exception e){ return new JSONArray(); }
  }

  static synchronized void addResult(Context c, JSONObject record){
    JSONArray all = results(c);
    all.put(record);
    prefs(c).edit().putString(KEY_RESULTS, all.toString()).apply();
  }

  /** The page has these in its log now. */
  static synchronized void ack(Context c, JSONArray ids){
    JSONArray keep = new JSONArray(), all = results(c);
    for (int i = 0; i < all.length(); i++){
      JSONObject r = all.optJSONObject(i);
      if (r == null) continue;
      boolean gone = false;
      for (int j = 0; j < ids.length(); j++) if (r.optString("id").equals(ids.optString(j))) gone = true;
      if (!gone) keep.put(r);
    }
    prefs(c).edit().putString(KEY_RESULTS, keep.toString()).apply();
  }
}
