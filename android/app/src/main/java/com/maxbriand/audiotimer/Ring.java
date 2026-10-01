package com.maxbriand.audiotimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/*
 * Every ring of the app goes through here — the wake-up alarm, the melatonin and walk
 * reminders, the fatigue checks — so the rules below are kept in one place instead of
 * being copied into each receiver (which is how all five once carried the same bug).
 *
 * The rules, each paid for on Maxime's phone (Redmi Note 10S, MIUI 14):
 *   - NEVER ongoing. MIUI's SystemUI removes an ongoing notification from this app 0.23 s
 *     after it is posted ("filter out ongoing notif"), and the full-screen intent — the
 *     screen that actually rings — goes with it. Checks fired on time and never rang
 *     (2026-10-01). Ongoing is for foreground-service notifications only.
 *   - One id per ring, from the list below: two rings sharing an id cancel each other
 *     (the melatonin reminder used to clear a ringing fatigue check).
 *   - A high-importance channel with the alarm sound, category alarm: the sound and the
 *     heads-up when the full-screen screen cannot open.
 * scripts/check-rings.mjs enforces these on every `npm run apk`. A Test button for a ring
 * must go through the real path — an alarm a few seconds ahead, the receiver, post() —
 * never straight to the activity, or it proves nothing about the ring.
 */
final class Ring {
  // Notification ids. Every notification id of the app is unique (check-rings.mjs);
  // HrService (1) and ShakeService (7, 8) keep theirs, outside this list.
  static final int ID_FATIGUE_ALARM = 45;
  static final int ID_FATIGUE_CHECK = 46;
  static final int ID_WALK = 47;
  static final int ID_WAKE = 48;
  static final int ID_MELATONIN = 49;

  private Ring(){}

  /** A ring's notification, ready for its title, text, intents and actions. */
  static Notification.Builder builder(Context c, String channel, String channelName){
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null){
      NotificationChannel ch = new NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
      ch.enableVibration(true);
      nm.createNotificationChannel(ch);
    }
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
      ? new Notification.Builder(c, channel)
      : new Notification.Builder(c).setPriority(Notification.PRIORITY_MAX);
    return b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(false);
  }

  /** Post it. Ongoing is forced off again here, whatever the caller did with the builder. */
  static void post(Context c, int id, Notification.Builder b){
    Notification n = b.build();
    n.flags &= ~Notification.FLAG_ONGOING_EVENT;
    ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
  }

  static void cancel(Context c, int id){
    ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id);
  }
}
