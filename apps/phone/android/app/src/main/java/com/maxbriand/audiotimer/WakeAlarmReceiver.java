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
 * Fires at the wake-up time. The first ring offers "I'm up" and one snooze; the ring that
 * comes back after that snooze offers only "I'm up" — one postponement is the whole
 * allowance, and after it the single way out is declaring the rise.
 *
 * "I'm up" is a PendingIntent.getActivity straight to MainActivity, never a broadcast that
 * then starts one: Android 12 forbids that notification trampoline. The activity records
 * the rise and leaves the page a note to switch into day mode.
 */
public class WakeAlarmReceiver extends BroadcastReceiver {
  static final String CHANNEL = "wake-alarm";
  static final int NOTIF_ID = Ring.ID_WAKE;   // ids live in Ring: one per ring
  static final String ACTION_SNOOZE = "com.maxbriand.audiotimer.WAKE_SNOOZE";

  @Override
  public void onReceive(Context c, Intent intent){
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (ACTION_SNOOZE.equals(intent.getAction())){
      nm.cancel(NOTIF_ID);
      WakeAlarm.snooze(c);
      return;
    }
    show(c);
  }

  static void show(Context c){
    boolean snoozed = WakeAlarm.snoozed(c);          // already postponed once: no second offer
    Intent full = new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent fullPi = PendingIntent.getActivity(c, 32, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Intent up = new Intent(c, MainActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(MainActivity.EXTRA_WAKE_UP, true);
    PendingIntent upPi = PendingIntent.getActivity(c, 33, up,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // The ring's notification: Ring keeps it non-ongoing (MIUI drops ongoing ones), alarm sound.
    Notification.Builder b = Ring.builder(c, CHANNEL, "Wake-up alarm");
    b.setContentTitle("Wake up")
     .setContentText(snoozed ? "Snoozed once already — time to get up."
                             : "It's " + WakeAlarm.clock(System.currentTimeMillis()) + " — your wake-up time.")
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true)
     .addAction(new Notification.Action.Builder(null, "I'm up", upPi).build());
    if (!snoozed){
      PendingIntent snoozePi = PendingIntent.getBroadcast(c, 34,
        new Intent(c, WakeAlarmReceiver.class).setAction(ACTION_SNOOZE),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      b.addAction(new Notification.Action.Builder(null, "Snooze 10 min", snoozePi).build());
    }
    Ring.post(c, NOTIF_ID, b);
  }
}
