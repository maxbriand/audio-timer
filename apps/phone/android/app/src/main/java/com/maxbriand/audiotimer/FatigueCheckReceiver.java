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
  static final int NOTIF_ID = Ring.ID_FATIGUE_CHECK;   // ids live in Ring: one per ring

  static final String EXTRA_TEST = "ringTest";

  @Override
  public void onReceive(Context c, Intent intent){
    if (intent.getBooleanExtra(EXTRA_TEST, false)){       // ⚙'s Test: FatigueChecks.ringTest
      JSONObject k = FatigueChecks.testCheck(c);
      if (k != null) show(c, k, System.currentTimeMillis(), true);
      return;
    }
    String id = intent.getStringExtra(FatigueCheckActivity.EXTRA_CHECK_ID);
    if (id == null || id.isEmpty()) return;
    long due = FatigueChecks.armedAt(c, id);
    FatigueChecks.fired(c, id);               // rung: never again for this wake-up / bedtime
    show(c, id, due == 0 ? System.currentTimeMillis() : due);
  }

  static void show(Context c, String id, long dueAt){
    JSONObject k = FatigueChecks.check(c, id);
    if (k == null) return;                    // deleted since it was armed
    show(c, k, dueAt, false);
  }

  static void show(Context c, JSONObject k, long dueAt, boolean test){
    Intent full = FatigueCheckActivity.intent(c, k, dueAt, test);
    PendingIntent fullPi = PendingIntent.getActivity(c, 3, full,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // The ring's notification: Ring keeps it non-ongoing (MIUI drops ongoing ones), alarm sound.
    Notification.Builder b = Ring.builder(c, CHANNEL, "Fatigue tracking");
    b.setContentTitle(test ? "Fatigue check — test" : "Fatigue check")
     .setContentText(FatigueChecks.label(k) + " — " + FatigueCheckActivity.stepsLine(k))
     .setContentIntent(fullPi)
     .setFullScreenIntent(fullPi, true);
    Ring.post(c, NOTIF_ID, b);
  }
}
