package com.maxbriand.audiotimer;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
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
import org.json.JSONObject;

/*
 * 🔔 — every ring the app has armed, and whether the phone will let each one ring
 * (Maxime, 2026-10-12).
 *
 * Android does not let an app list its own alarms, so each one is read where it was armed
 * (the time kept beside every setAlarmClock) and then checked against the system:
 *   - its PendingIntent must still exist: AlarmManager holds every armed one;
 *   - the earliest is also matched against getNextAlarmClock(), the system's own next
 *     alarm clock — when it is ours and at the same minute, the system confirms it;
 * and against what silenced rings on this phone before: notifications off, a channel turned
 * down, the alarm volume at 0, total-silence Do Not Disturb, and MIUI's own permissions
 * "Show on Lock screen" (op 10020) and "Open new windows while running in the background"
 * (op 10021) — without those the full-screen ring never opens (2026-10-01). MIUI's ops are
 * read through a hidden method; where that fails they read "unknown", never "allowed".
 */
@CapacitorPlugin(name = "ArmedAlarms")
public class ArmedAlarmsPlugin extends Plugin {
  private static final int MIUI_SHOW_WHEN_LOCKED = 10020, MIUI_BACKGROUND_START = 10021;

  @PluginMethod
  public void list(PluginCall call){
    Context c = getContext();
    long now = System.currentTimeMillis();
    JSArray alarms = new JSArray();

    if (!WakeAlarm.goal(c).isEmpty())
      alarms.put(alarm(c, "wake", WakeAlarm.snoozed(c) ? "⏰ Wake-up — snoozed" : "⏰ Wake-up",
        WakeAlarm.nextAt(c), WakeAlarm.intentAlive(c), WakeAlarmReceiver.CHANNEL, true, now));
    if (!MelatoninAlarm.bedtime(c).isEmpty())
      alarms.put(alarm(c, "melatonin", "💊 Melatonin", MelatoninAlarm.nextAt(c),
        MelatoninAlarm.intentAlive(c), MelatoninReceiver.CHANNEL, true, now));
    if (WalkAlarm.at(c) > 0)
      alarms.put(alarm(c, "walk", "🌅 Morning walk", WalkAlarm.at(c),
        WalkAlarm.intentAlive(c), WalkAlarmReceiver.CHANNEL, false, now));
    JSONArray checks = FatigueChecks.checks(c);
    for (int i = 0; i < checks.length() && i < FatigueChecks.MAX; i++){
      JSONObject k = checks.optJSONObject(i);
      if (k == null) continue;
      long at = FatigueChecks.armedAt(c, k.optString("id"));
      if (at == 0) continue;            // not armed for this anchor (rung, or no anchor yet)
      alarms.put(alarm(c, "fatigue", "🩺 Fatigue check — " + FatigueChecks.label(k), at,
        FatigueChecks.intentAlive(c, i), FatigueCheckReceiver.CHANNEL, true, now));
    }

    // The system's own next alarm clock, if it is ours.
    long sysNext = 0;
    try {
      AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      AlarmManager.AlarmClockInfo info = am == null ? null : am.getNextAlarmClock();
      if (info != null && info.getShowIntent() != null
          && c.getPackageName().equals(info.getShowIntent().getCreatorPackage()))
        sysNext = info.getTriggerTime();
    } catch (Exception ignored){}
    for (int i = 0; i < alarms.length(); i++){
      JSONObject a = alarms.optJSONObject(i);
      try { a.put("systemConfirmed", sysNext != 0 && Math.abs(a.optLong("at") - sysNext) < 60000); }
      catch (Exception ignored){}
    }

    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    JSObject phone = new JSObject();
    phone.put("notifications", nm == null || nm.areNotificationsEnabled());
    phone.put("alarmVolume", audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_ALARM));
    phone.put("alarmVolumeMax", audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_ALARM));
    phone.put("totalSilence", nm != null && nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE);
    phone.put("exactAlarms", Build.VERSION.SDK_INT < 31
      || ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms());
    phone.put("fullScreenIntent", Build.VERSION.SDK_INT < 34 || nm == null || nm.canUseFullScreenIntent());
    phone.put("miuiLockScreen", miuiOp(c, MIUI_SHOW_WHEN_LOCKED));
    phone.put("miuiBackgroundWindows", miuiOp(c, MIUI_BACKGROUND_START));
    phone.put("miui", isMiui());

    JSObject out = new JSObject();
    out.put("now", now);
    out.put("systemNext", sysNext);
    out.put("alarms", alarms);
    out.put("phone", phone);
    call.resolve(out);
  }

  @PluginMethod
  public void openAppInfo(PluginCall call){
    try {
      Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + getContext().getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e){ call.reject(e.getMessage()); }
  }

  private static JSObject alarm(Context c, String kind, String title, long at, boolean alive,
                                String channel, boolean fullScreen, long now){
    JSObject a = new JSObject();
    a.put("kind", kind);
    a.put("title", title);
    a.put("at", at);
    a.put("ahead", at > now);
    a.put("intentAlive", alive);
    a.put("fullScreen", fullScreen);
    a.put("channel", channel);
    // -1: the channel is made at the first ring, at high importance.
    int imp = -1;
    if (Build.VERSION.SDK_INT >= 26){
      NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(channel);
      if (ch != null) imp = ch.getImportance();
    }
    a.put("importance", imp);
    return a;
  }

  /** "allowed", "denied", or "unknown" (not MIUI, or the hidden method is out of reach). */
  private static String miuiOp(Context c, int op){
    if (!isMiui()) return "unknown";
    try {
      AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
      java.lang.reflect.Method m = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
      int mode = (Integer) m.invoke(ops, op, Process.myUid(), c.getPackageName());
      return mode == AppOpsManager.MODE_ALLOWED ? "allowed" : "denied";
    } catch (Throwable t){ return "unknown"; }
  }

  private static boolean isMiui(){
    try {
      Class<?> sp = Class.forName("android.os.SystemProperties");
      String v = (String) sp.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
      return v != null && !v.isEmpty();
    } catch (Throwable t){ return false; }
  }
}
