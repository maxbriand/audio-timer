package com.maxbriand.audiotimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONObject;

/*
 * A fatigue check's moment has come: put it on the screen.
 *
 * Same shape as the fatigue alarm it grew out of — a receiver may not start an activity
 * from the background, so the ring is a full-screen notification: on a locked or dark phone
 * Android opens FatigueCheckActivity itself, on a phone in use it is a heads-up with the
 * alarm sound, one tap away. The steps are read from the list at this moment, not from the
 * one the alarm was armed with, so an edit made in between is the check that rings.
 */
public class FatigueCheckReceiver extends BroadcastReceiver {
  static final String CHANNEL = "fatigue-check";
  static final int NOTIF_ID = 46;

  @Override
  public void onReceive(Context c, Intent intent){
    String id = intent.getStringExtra(FatigueCheckActivity.EXTRA_CHECK_ID);
    if (id == null || id.isEmpty()) return;
    long due = FatigueChecks.armedAt(c, id);
    FatigueChecks.fired(c, id);               // rung: never again for this wake-up / bedtime
    show(c, id, due == 0 ? System.currentTimeMillis() : due);
  }

  static void show(Context c, String id, long dueAt){
    JSONObject k = FatigueChecks.check(c, id);
    if (k == null) return;                    // deleted since it was armed
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null){
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Fatigue tracking",
        NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
      ch.enableVibration(true);
      nm.createNotificationChannel(ch);
    }
    Intent full = FatigueCheckActivity.intent(c, k, dueAt, false);
    PendingIntent fullPi = PendingIntent.getActivity(c, 3, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
      ? new Notification.Builder(c, CHANNEL)
      : new Notification.Builder(c).setPriority(Notification.PRIORITY_MAX);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
     .setContentTitle("Fatigue check")
     .setContentText(FatigueChecks.label(k) + " — " + FatigueCheckActivity.stepsLine(k))
     .setCategory(Notification.CATEGORY_ALARM)
     .setOngoing(true)
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true);
    nm.notify(NOTIF_ID, b.build());
  }
}
