package com.maxbriand.audiotimer;

import android.app.AppOpsManager;
import android.app.usage.EventStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/*
 * The 📱 page's handle on Android's own usage log — the one Digital Wellbeing reads.
 *
 * Nothing here records anything: the system already keeps the log for every app on the
 * phone, and this only reads it back, which is why it works for the days before the page
 * existed. The price is the "Usage access" switch in Settings, which only the user can flip
 * (status / openSettings, and openAppInfo for when Android greys the switch out), and the
 * system's own retention: the event log (events) reaches back about a week, the aggregated
 * buckets (totals) much further — days for a week, weeks for a month, months for half a
 * year, years for two.
 *
 * The page does the reading of the log (sessions, pickups, per-app time); this side only
 * fetches, because the rules for pairing events are easier to get right — and to change —
 * next to the screen that shows them.
 */
@CapacitorPlugin(name = "PhoneUsage")
public class PhoneUsagePlugin extends Plugin {

  /* The event types the page reads. Literal ints, not UsageEvents.Event constants: half of
     them arrived in API 28/29 and one (12) was never made public, though the log hands it
     to anyone with usage access. Everything else in the log is only counted, by type. */
  private static final int ACTIVITY_RESUMED = 1, ACTIVITY_PAUSED = 2, USER_INTERACTION = 7,
    SHORTCUT_INVOCATION = 8, NOTIFICATION = 12, SCREEN_ON = 15, SCREEN_OFF = 16,
    KEYGUARD_SHOWN = 17, KEYGUARD_HIDDEN = 18, ACTIVITY_STOPPED = 23,
    DEVICE_SHUTDOWN = 26, DEVICE_STARTUP = 27;

  private static boolean wanted(int type){
    switch (type){
      case ACTIVITY_RESUMED: case ACTIVITY_PAUSED: case ACTIVITY_STOPPED:
      case USER_INTERACTION: case SHORTCUT_INVOCATION: case NOTIFICATION:
      case SCREEN_ON: case SCREEN_OFF: case KEYGUARD_SHOWN: case KEYGUARD_HIDDEN:
      case DEVICE_SHUTDOWN: case DEVICE_STARTUP:
        return true;
      default:
        return false;
    }
  }

  private boolean granted(){
    Context c = getContext();
    AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
    if (ops == null) return false;
    int mode = Build.VERSION.SDK_INT >= 29
      ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
      : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
    return mode == AppOpsManager.MODE_ALLOWED;
  }

  @PluginMethod
  public void status(PluginCall call){
    JSObject r = new JSObject();
    r.put("granted", granted());
    r.put("sdk", Build.VERSION.SDK_INT);        // screen and keyguard events start at 28
    call.resolve(r);
  }

  /* Straight to this app's own switch where Settings knows how (the package: form), to the
     list of all apps where it does not. */
  @PluginMethod
  public void openSettings(PluginCall call){
    Context c = getContext();
    try {
      Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(i);
    } catch (Exception e){
      try {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
      } catch (Exception ignored) {}
    }
    call.resolve();
  }

  /* The way round a greyed-out switch. Android refuses this kind of access to an app that
     came from an APK until "Allow restricted settings" is ticked, and that entry lives in
     the ⋮ menu of the app's own info screen — this opens that screen. */
  @PluginMethod
  public void openAppInfo(PluginCall call){
    try {
      Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getContext().getPackageName()));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
    } catch (Exception ignored) {}
    call.resolve();
  }

  // Epoch ms travel as strings, as everywhere else on this bridge.
  private static long millis(PluginCall call, String key, long fallback){
    try { return Long.parseLong(call.getString(key, "")); }
    catch (NumberFormatException e){ return fallback; }
  }

  /* Package names are the log's language; the page wants "Instagram". A label needs the
     package to be visible to this app (the <queries> in the manifest: everything with a
     launcher icon, and the home screens) — the rest keep their package name. */
  private final Map<String, String> labels = new HashMap<>();
  private String label(String pkg){
    String l = labels.get(pkg);
    if (l != null) return l;
    try {
      PackageManager pm = getContext().getPackageManager();
      ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
      l = String.valueOf(pm.getApplicationLabel(ai));
    } catch (Exception e){ l = pkg; }
    labels.put(pkg, l);
    return l;
  }

  private Set<String> homes(){
    Set<String> out = new HashSet<>();
    try {
      Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
      for (ResolveInfo ri : getContext().getPackageManager().queryIntentActivities(home, 0))
        if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
    } catch (Exception ignored) {}
    return out;
  }

  /* The raw log between two moments, oldest first, as compact rows [ms since `from`, type,
     package index, class index] against two string tables — a busy day is thousands of rows
     and the package names would otherwise be most of the payload. `counts` covers every
     event by type, including the kinds that are not sent — from `countFrom` on, because the
     page asks for a few hours before its day (to know whether the screen was already on at
     midnight) and those hours are not the day's. */
  @PluginMethod
  public void events(PluginCall call){
    if (!granted()){ call.reject("no usage access"); return; }
    long now = System.currentTimeMillis();
    long from = millis(call, "from", now - 86400000L);
    long to = Math.min(millis(call, "to", now), now);
    long countFrom = millis(call, "countFrom", from);
    UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
    if (usm == null){ call.reject("usage stats unavailable"); return; }

    List<String> pkgs = new ArrayList<>(), clss = new ArrayList<>();
    Map<String, Integer> pkgAt = new HashMap<>(), clsAt = new HashMap<>();
    Map<Integer, Integer> counts = new TreeMap<>();
    JSONArray rows = new JSONArray();

    UsageEvents evs = usm.queryEvents(from, to);
    UsageEvents.Event e = new UsageEvents.Event();
    while (evs != null && evs.hasNextEvent()){
      evs.getNextEvent(e);
      int type = e.getEventType();
      if (e.getTimeStamp() >= countFrom){
        Integer n = counts.get(type);
        counts.put(type, n == null ? 1 : n + 1);
      }
      if (!wanted(type)) continue;
      String p = e.getPackageName(), k = e.getClassName();
      int pi = -1, ki = -1;
      if (p != null){
        Integer at = pkgAt.get(p);
        if (at == null){ at = pkgs.size(); pkgs.add(p); pkgAt.put(p, at); }
        pi = at;
      }
      if (k != null){
        Integer at = clsAt.get(k);
        if (at == null){ at = clss.size(); clss.add(k); clsAt.put(k, at); }
        ki = at;
      }
      JSONArray row = new JSONArray();
      row.put(e.getTimeStamp() - from); row.put(type); row.put(pi); row.put(ki);
      rows.put(row);
    }

    Set<String> homes = homes();
    JSArray pk = new JSArray();
    for (String p : pkgs){
      JSObject o = new JSObject();
      o.put("p", p);
      o.put("label", label(p));
      if (homes.contains(p)) o.put("home", true);
      pk.put(o);
    }
    JSArray ck = new JSArray();
    for (String k : clss) ck.put(k);
    JSObject cn = new JSObject();
    for (Map.Entry<Integer, Integer> en : counts.entrySet()) cn.put(String.valueOf(en.getKey()), en.getValue());

    JSObject r = new JSObject();
    r.put("from", String.valueOf(from));
    r.put("to", String.valueOf(to));
    r.put("pkgs", pk);
    r.put("cls", ck);
    r.put("ev", rows);
    r.put("counts", cn);
    call.resolve(r);
  }

  /* The system's own aggregates, which outlive the event log. One entry per bucket the
     system kept: how often and how long the screen was on and the phone unlocked (API 28+),
     and every app's foreground time inside it. The buckets are the system's, not the
     calendar's — a "day" starts whenever its file was opened — so each carries its real
     bounds and the page prints those rather than pretending to midnight. */
  @PluginMethod
  public void totals(PluginCall call){
    if (!granted()){ call.reject("no usage access"); return; }
    String kind = call.getString("interval", "day");
    int interval = "year".equals(kind) ? UsageStatsManager.INTERVAL_YEARLY
                 : "month".equals(kind) ? UsageStatsManager.INTERVAL_MONTHLY
                 : "week".equals(kind) ? UsageStatsManager.INTERVAL_WEEKLY
                 : UsageStatsManager.INTERVAL_DAILY;
    long now = System.currentTimeMillis();
    long from = millis(call, "from", now - 7 * 86400000L);
    long to = Math.min(millis(call, "to", now), now);
    UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
    if (usm == null){ call.reject("usage stats unavailable"); return; }

    // Keyed by the bucket's start: the screen stats and the app stats of one bucket share it.
    TreeMap<Long, JSObject> buckets = new TreeMap<>();
    Map<Long, JSArray> apps = new HashMap<>();

    List<UsageStats> stats = usm.queryUsageStats(interval, from, to);
    if (stats != null) for (UsageStats s : stats){
      long fg = s.getTotalTimeInForeground();
      if (fg <= 0) continue;
      long start = s.getFirstTimeStamp();
      JSObject b = buckets.get(start);
      if (b == null){
        b = new JSObject();
        b.put("from", String.valueOf(start));
        b.put("to", String.valueOf(s.getLastTimeStamp()));
        buckets.put(start, b);
        apps.put(start, new JSArray());
      }
      JSObject a = new JSObject();
      a.put("p", s.getPackageName());
      a.put("label", label(s.getPackageName()));
      a.put("ms", fg);
      a.put("last", String.valueOf(s.getLastTimeUsed()));
      if (Build.VERSION.SDK_INT >= 29){
        a.put("visibleMs", s.getTotalTimeVisible());
        a.put("fgsMs", s.getTotalTimeForegroundServiceUsed());
      }
      apps.get(start).put(a);
    }

    if (Build.VERSION.SDK_INT >= 28){
      List<EventStats> es = usm.queryEventStats(interval, from, to);
      if (es != null) for (EventStats s : es){
        int type = s.getEventType();
        String key = type == SCREEN_ON ? "screenOn" : type == SCREEN_OFF ? "screenOff"
                   : type == KEYGUARD_HIDDEN ? "unlocked" : type == KEYGUARD_SHOWN ? "locked" : null;
        if (key == null) continue;
        long start = s.getFirstTimeStamp();
        JSObject b = buckets.get(start);
        if (b == null){
          b = new JSObject();
          b.put("from", String.valueOf(start));
          b.put("to", String.valueOf(s.getLastTimeStamp()));
          buckets.put(start, b);
        }
        JSObject v = new JSObject();
        v.put("n", s.getCount());
        v.put("ms", s.getTotalTime());
        v.put("last", String.valueOf(s.getLastEventTime()));
        b.put(key, v);
      }
    }

    Set<String> homes = homes();
    JSArray hs = new JSArray();
    for (String h : homes) hs.put(h);
    JSArray out = new JSArray();
    for (Map.Entry<Long, JSObject> en : buckets.entrySet()){
      JSArray a = apps.get(en.getKey());
      en.getValue().put("apps", a == null ? new JSArray() : a);
      out.put(en.getValue());
    }
    JSObject r = new JSObject();
    r.put("interval", kind);
    r.put("buckets", out);
    r.put("homes", hs);
    call.resolve(r);
  }
}
