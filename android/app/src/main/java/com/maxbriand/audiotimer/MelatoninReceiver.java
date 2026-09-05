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
 * Fires at bedtime − 5 h, and owns the two buttons.
 *
 * The notification cannot be swiped away (ongoing) and nothing but TAKEN removes it: that
 * is the "not closable before I click Taken" contract. Snooze silences it for 10 minutes
 * and it comes back. Both buttons live on the notification AND on the full-screen
 * MelatoninActivity, so the answer is one tap whichever surface the ring landed on.
 */
public class MelatoninReceiver extends BroadcastReceiver {
  static final String CHANNEL = "melatonin";
  static final int NOTIF_ID = 46;
  static final String ACTION_TAKEN = "com.maxbriand.audiotimer.MELATONIN_TAKEN";
  static final String ACTION_SNOOZE = "com.maxbriand.audiotimer.MELATONIN_SNOOZE";

  @Override
  public void onReceive(Context c, Intent intent){
    String a = intent.getAction();
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (ACTION_TAKEN.equals(a)){
      nm.cancel(NOTIF_ID);
      MelatoninAlarm.taken(c);
      return;
    }
    if (ACTION_SNOOZE.equals(a)){
      nm.cancel(NOTIF_ID);
      MelatoninAlarm.snooze(c);
      return;
    }
    show(c);                                    // the alarm itself
  }

  static void show(Context c){
    MelatoninAlarm.markShown(c);              // this ring happened — it was not swallowed
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null){
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Melatonin reminder",
        NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
      ch.enableVibration(true);
      nm.createNotificationChannel(ch);
    }
    Intent full = new Intent(c, MelatoninActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent fullPi = PendingIntent.getActivity(c, 12, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent takenPi = PendingIntent.getBroadcast(c, 13,
      new Intent(c, MelatoninReceiver.class).setAction(ACTION_TAKEN),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent snoozePi = PendingIntent.getBroadcast(c, 14,
      new Intent(c, MelatoninReceiver.class).setAction(ACTION_SNOOZE),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
      ? new Notification.Builder(c, CHANNEL)
      : new Notification.Builder(c).setPriority(Notification.PRIORITY_MAX);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
     .setContentTitle("Melatonin — 0.5 mg now")
     .setContentText("5 h before your " + MelatoninAlarm.bedtime(c) + " bedtime.")
     .setCategory(Notification.CATEGORY_ALARM)
     .setOngoing(true)
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true)
     .addAction(new Notification.Action.Builder(null, "Taken ✓", takenPi).build())
     .addAction(new Notification.Action.Builder(null, "Snooze 10 min", snoozePi).build());
    nm.notify(NOTIF_ID, b.build());
  }
}
