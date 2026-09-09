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

/*
 * Fires at the wake-up time and owns the two answers. Tomorrow's alarm is armed FIRST,
 * before anything rings, so the cycle survives whatever happens to this ring. The
 * notification is a full-screen alarm (WakeActivity over the lock screen, heads-up with
 * the alarm sound on a phone in use) and stays until answered.
 */
public class WakeAlarmReceiver extends BroadcastReceiver {
  static final String CHANNEL = "wake-alarm";
  static final int NOTIF_ID = 48;
  static final String ACTION_UP = "com.maxbriand.audiotimer.WAKE_UP";
  static final String ACTION_SNOOZE = "com.maxbriand.audiotimer.WAKE_SNOOZE";

  @Override
  public void onReceive(Context c, Intent intent){
    String a = intent.getAction();
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (ACTION_UP.equals(a)){
      nm.cancel(NOTIF_ID);
      return;
    }
    if (ACTION_SNOOZE.equals(a)){
      nm.cancel(NOTIF_ID);
      WakeAlarm.snooze(c);
      return;
    }
    WakeAlarm.scheduleNext(c);                // tomorrow first, then today's ring
    show(c);
  }

  static void show(Context c){
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null){
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Wake-up alarm",
        NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
      ch.enableVibration(true);
      nm.createNotificationChannel(ch);
    }
    Intent full = new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent fullPi = PendingIntent.getActivity(c, 32, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent upPi = PendingIntent.getBroadcast(c, 33,
      new Intent(c, WakeAlarmReceiver.class).setAction(ACTION_UP),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent snoozePi = PendingIntent.getBroadcast(c, 34,
      new Intent(c, WakeAlarmReceiver.class).setAction(ACTION_SNOOZE),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
      ? new Notification.Builder(c, CHANNEL)
      : new Notification.Builder(c).setPriority(Notification.PRIORITY_MAX);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
     .setContentTitle("Wake up")
     .setContentText("It's " + WakeAlarm.time(c) + " — your wake-up time.")
     .setCategory(Notification.CATEGORY_ALARM)
     .setOngoing(true)
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true)
     .addAction(new Notification.Action.Builder(null, "I'm up", upPi).build())
     .addAction(new Notification.Action.Builder(null, "Snooze 10 min", snoozePi).build());
    nm.notify(NOTIF_ID, b.build());
  }
}
