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
 * Fires at rise + 30 minutes when no morning walk was logged, and offers the one answer.
 *
 * Like the melatonin reminder the notification is ongoing — nothing but "Done ✓" removes
 * it — because the reminder's whole job is to not be swipeable into oblivion. Done stages
 * the same marker row the day screen's ☀️ tap would have.
 */
public class WalkAlarmReceiver extends BroadcastReceiver {
  static final String CHANNEL = "morning-walk";
  static final int NOTIF_ID = Ring.ID_WALK;   // ids live in Ring: one per ring
  static final String ACTION_DONE = "com.maxbriand.audiotimer.WALK_DONE";

  @Override
  public void onReceive(Context c, Intent intent){
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (ACTION_DONE.equals(intent.getAction())){
      nm.cancel(NOTIF_ID);
      WalkAlarm.done(c);
      return;
    }
    WalkAlarm.clear(c);                       // fired: nothing left for a reboot to re-arm
    show(c);
  }

  static void show(Context c){
    Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent openPi = PendingIntent.getActivity(c, 22, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent donePi = PendingIntent.getBroadcast(c, 23,
      new Intent(c, WalkAlarmReceiver.class).setAction(ACTION_DONE),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // The ring's notification: Ring keeps it non-ongoing (MIUI drops ongoing ones), alarm sound.
    Notification.Builder b = Ring.builder(c, CHANNEL, "Morning light reminder");
    b.setContentTitle("Morning light — go get it")
     .setContentText("30 minutes up and no walk logged. Done stamps it now.")
     .setContentIntent(openPi)
     .addAction(new Notification.Action.Builder(null, "Done ✓", donePi).build());
    Ring.post(c, NOTIF_ID, b);
  }
}
