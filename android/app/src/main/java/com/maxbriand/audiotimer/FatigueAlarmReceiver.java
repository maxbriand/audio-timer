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
 * Fires at rise + 45 minutes and puts the question on the screen.
 *
 * A receiver may not start an activity from the background, so the ring is a full-screen
 * notification: on a locked or dark phone Android launches AlarmActivity itself — the
 * classic alarm experience — and on a phone in use it is a heads-up with the alarm sound,
 * one tap away. The continuous ringing lives in AlarmActivity; the channel's own sound
 * covers the heads-up case, where the activity never starts on its own.
 */
public class FatigueAlarmReceiver extends BroadcastReceiver {
  static final String CHANNEL = "fatigue-alarm";
  static final int NOTIF_ID = Ring.ID_FATIGUE_ALARM;   // ids live in Ring: one per ring

  @Override
  public void onReceive(Context c, Intent intent){
    FatigueAlarm.clear(c);                    // fired: nothing left for a reboot to re-arm
    show(c);
  }

  static void show(Context c){
    Intent full = new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent fullPi = PendingIntent.getActivity(c, 2, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // The ring's notification: Ring keeps it non-ongoing (MIUI drops ongoing ones), alarm sound.
    Notification.Builder b = Ring.builder(c, CHANNEL, "Fatigue check");
    b.setContentTitle("How tired are you?")
     .setContentText("Score the morning fatigue — 10 is the maximum.")
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true);
    Ring.post(c, NOTIF_ID, b);
  }
}
