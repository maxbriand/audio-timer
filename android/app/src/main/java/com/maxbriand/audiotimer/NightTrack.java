package com.maxbriand.audiotimer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Locale;

/*
 * Night tracking — heart rate, HRV and sleeping position, from the switch to night mode to
 * the wake-up, for the nights the question at that switch was answered yes.
 *
 * It lives with HrService because a night is eight hours of dark screen: the page is frozen
 * long before the first hour is over, and only the service — foreground, holding its wake
 * lock and the strap — is still there to listen. The service hands over what the strap
 * sends (beats, accelerometer frames); this class turns it into one line a minute:
 *
 *   hr     average of the rate the strap reported in that minute
 *   rmssd  over the minute's beat-to-beat intervals, by the fatigue check's own arithmetic
 *          (300–2000 ms kept; a dropped interval breaks the chain, it is not bridged)
 *   pos    the position held longest in the minute, from the gravity vector — the very rule
 *          of the Live page (H10 axes as worn: X along the body, Y lateral, +left side,
 *          Z front-to-back, +on the back), judged second by second
 *   sec    the seconds spent in each position, mov the seconds spent moving
 *
 * A minute in which the strap said nothing is written as such ("off"), never filled in.
 * Every line goes to disk as it is made — a JSON-lines file per night — so a night survives
 * the process being killed, the phone rebooting, the battery dying at 5 a.m.: whatever was
 * recorded is there, and the service picks the same file up again when it is restarted.
 * The file waits until the page has the night in its log and says so (read / ack).
 */
final class NightTrack {
  private static final String PREFS = "nighttrack";
  private static final String KEY_ON = "on", KEY_START = "start", KEY_ADDRESS = "address";
  private static final float RR_MIN = 300f, RR_MAX = 2000f;
  private static final long MINUTE = 60000L;

  static final String[] POS = { "back", "stomach", "left", "right", "upright", "headdown", "between" };

  private NightTrack(){}

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static boolean on(Context c){ return prefs(c).getBoolean(KEY_ON, false); }
  static long startedAt(Context c){ return prefs(c).getLong(KEY_START, 0); }
  static String address(Context c){ return prefs(c).getString(KEY_ADDRESS, ""); }

  private static File dir(Context c){
    File d = new File(c.getFilesDir(), "nights");
    if (!d.exists()) d.mkdirs();
    return d;
  }
  private static File file(Context c, long start){ return new File(dir(c), start + ".jsonl"); }

  /** A night begins. Starting one while one runs keeps the one that runs. */
  static long begin(Context c, String address){
    if (on(c)) return startedAt(c);
    long now = System.currentTimeMillis();
    prefs(c).edit().putBoolean(KEY_ON, true).putLong(KEY_START, now)
            .putString(KEY_ADDRESS, address == null ? "" : address).apply();
    return now;
  }

  /** The night is over; its file stays until the page has taken it. */
  static long end(Context c){
    long start = startedAt(c);
    prefs(c).edit().putBoolean(KEY_ON, false).putLong("endedAt:" + start, System.currentTimeMillis()).apply();
    return start;
  }

  // ------------------------------------------------------------------ one minute

  /* What a minute is made of, and nothing else — no clock of its own, no disk: it is told
     the time, so the arithmetic can be checked on its own. */
  static final class Minute {
    long hrSum; int hrN, dropped, moving;
    final ArrayList<Float> rr = new ArrayList<>();
    final int[] sec = new int[POS.length];
    // the second being judged
    long secondOf, sx, sy, sz; double dev; int sn;

    void beats(int bpm, float[] rrMs){
      if (bpm <= 0) return;                           // connected, no skin contact
      hrSum += bpm; hrN++;
      for (float v : rrMs){
        if (v >= RR_MIN && v <= RR_MAX) rr.add(v);
        else { dropped++; rr.add(Float.NaN); }
      }
    }

    /** One PMD accelerometer frame (type 0x02, frame 0x01: int16 x, y, z in mG). */
    void acc(byte[] v, long now){
      if (v == null || v.length < 16 || (v[0] & 0xff) != 0x02 || (v[9] & 0xff) != 0x01) return;
      long s = now / 1000;
      if (s != secondOf){ judge(); secondOf = s; }
      for (int i = 10; i + 5 < v.length; i += 6){
        int x = (short) ((v[i] & 0xff) | (v[i + 1] << 8));
        int y = (short) ((v[i + 2] & 0xff) | (v[i + 3] << 8));
        int z = (short) ((v[i + 4] & 0xff) | (v[i + 5] << 8));
        sx += x; sy += y; sz += z; sn++;
        dev += Math.abs(Math.sqrt((double) x * x + (double) y * y + (double) z * z) - 1000);
      }
    }

    /** The second just ended: moving, or held in one position — the Live page's rule. */
    void judge(){
      if (sn >= 5){
        int p = position(sx / (double) sn, sy / (double) sn, sz / (double) sn, dev / sn);
        if (p < 0) moving++; else sec[p]++;
      }
      sx = sy = sz = 0; dev = 0; sn = 0;
    }

    /** The minute as its line in the night's file. */
    String line(long t){
      judge();
      try {
        int best = -1, total = moving;
        for (int i = 0; i < POS.length; i++){ total += sec[i]; if (sec[i] > 0 && (best < 0 || sec[i] > sec[best])) best = i; }
        if (hrN == 0 && total == 0) return "{\"t\":" + t + ",\"off\":true}";
        JSONObject o = new JSONObject();
        o.put("t", t);
        if (hrN > 0) o.put("hr", Math.round(hrSum * 10.0 / hrN) / 10.0);
        double[] h = FatigueChecks.hrv(rr);
        // Under 20 intervals a minute is mostly holes: its RMSSD would be noise with a number on it.
        if (!Double.isNaN(h[1]) && h[0] >= 20) o.put("rmssd", Math.round(h[1] * 10.0) / 10.0);
        o.put("rr", (int) h[0]);
        if (dropped > 0) o.put("drop", dropped);
        if (total > 0){
          o.put("pos", best >= 0 && sec[best] >= moving ? POS[best] : "moving");
          JSONObject sj = new JSONObject();
          for (int i = 0; i < POS.length; i++) if (sec[i] > 0) sj.put(POS[i], sec[i]);
          o.put("sec", sj);
          if (moving > 0) o.put("mov", moving);
        }
        return o.toString();
      } catch (Exception e){ return "{\"t\":" + t + ",\"off\":true}"; }
    }
  }

  private static long minuteOf;                       // the minute being filled, 0 = none
  private static Minute cur = new Minute();

  private static void roll(Context c, long now){
    long m = now - now % MINUTE;
    if (minuteOf == 0){ minuteOf = m; return; }
    if (m == minuteOf) return;
    flush(c);
    // Minutes the strap skipped entirely: said, not guessed. Capped — a night is not a week.
    for (long g = minuteOf + MINUTE, n = 0; g < m && n < 720; g += MINUTE, n++) append(c, "{\"t\":" + g + ",\"off\":true}");
    minuteOf = m;
  }

  static synchronized void onBeats(Context c, int bpm, float[] rrMs){
    if (!on(c)) return;
    roll(c, System.currentTimeMillis());
    cur.beats(bpm, rrMs);
  }

  static synchronized void onAcc(Context c, byte[] v){
    if (!on(c)) return;
    long now = System.currentTimeMillis();
    roll(c, now);
    cur.acc(v, now);
  }

  /** Index into POS, or -1 for moving. */
  static int position(double gx, double gy, double gz, double meanDev){
    if (meanDev > 250) return -1;
    double ax = Math.abs(gx), ay = Math.abs(gy), az = Math.abs(gz), top = Math.max(ax, Math.max(ay, az));
    if (top < 700) return 6;
    if (top == ax) return gx < 0 ? 4 : 5;
    if (top == az) return gz > 0 ? 0 : 1;
    return gy > 0 ? 2 : 3;
  }

  /** Write the minute in hand and start a clean one. */
  static synchronized void flush(Context c){
    if (minuteOf == 0) return;
    append(c, cur.line(minuteOf));
    cur = new Minute();
  }

  /** Forget the minute in hand without writing it — the night ended, its file is closed. */
  static synchronized void reset(){ minuteOf = 0; cur = new Minute(); }

  private static void append(Context c, String line){
    long start = startedAt(c);
    if (start == 0) return;
    try (FileWriter w = new FileWriter(file(c, start), true)){ w.write(line); w.write('\n'); }
    catch (Exception ignored){}
  }

  // ------------------------------------------------------------------ handing a night over

  /** The nights that are finished and not yet taken by the page: [{start, end}]. */
  static JSONArray pending(Context c){
    JSONArray out = new JSONArray();
    File[] all = dir(c).listFiles();
    long running = on(c) ? startedAt(c) : 0;
    if (all != null) for (File f : all){
      try {
        long start = Long.parseLong(f.getName().replace(".jsonl", ""));
        if (start == running) continue;
        JSONObject o = new JSONObject();
        o.put("start", String.valueOf(start));
        o.put("end", String.valueOf(prefs(c).getLong("endedAt:" + start, f.lastModified())));
        out.put(o);
      } catch (Exception ignored){}
    }
    return out;
  }

  static JSONArray read(Context c, long start){
    JSONArray out = new JSONArray();
    try (BufferedReader r = new BufferedReader(new FileReader(file(c, start)))){
      String line;
      while ((line = r.readLine()) != null){
        try { out.put(new JSONObject(line)); } catch (Exception bad){ /* a line cut by a crash */ }
      }
    } catch (Exception ignored){}
    return out;
  }

  static void ack(Context c, long start){
    if (on(c) && start == startedAt(c)) return;
    file(c, start).delete();
    prefs(c).edit().remove("endedAt:" + start).apply();
  }

  static String clock(long at){
    java.util.Calendar k = java.util.Calendar.getInstance();
    k.setTimeInMillis(at);
    return String.format(Locale.US, "%02d:%02d", k.get(java.util.Calendar.HOUR_OF_DAY), k.get(java.util.Calendar.MINUTE));
  }
}
