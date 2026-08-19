package com.maxbriand.audiotimer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;

/*
 * Shake-to-resume, the part that survives the screen turning off.
 *
 * With the screen dark Android suspends the WebView, so the page's own devicemotion listener
 * goes deaf exactly when it is needed. This service is the native stand-in: while a selected
 * track is paused it holds a partial wake lock and watches the accelerometer itself, with no
 * deadline (the page passes windowMs 0) — the watch is a state owned by the page, closed by
 * the ✕, by day mode, or by the app going away. A shake is reported back to the page
 * (ShakeWatchPlugin), which resumes playback.
 *
 * Lifecycle is shaped by one Android rule: a foreground service cannot be STARTED from the
 * background (Android 12+), but a RUNNING one may be talked to freely. So the page starts the
 * service while it is still visible — the moment the timer is armed — and from then on the
 * service is only ever updated or stopped, never restarted cold at night. For the same reason
 * a shake or a resume sends it back to idle with a short grace period instead of killing it:
 * the auto-armed next timer re-uses it seconds later, which a fresh start could not do.
 */
public class ShakeService extends Service implements SensorEventListener {
  static final String ACTION_ARM  = "arm";    // timer set: stand by until the deadline
  static final String ACTION_OPEN = "open";   // timer fired: watch the sensor now
  static final String ACTION_STOP = "stop";   // nothing left to wait for
  static final String EXTRA_DEADLINE = "deadline";   // epoch ms of the timer's end
  static final String EXTRA_WINDOW   = "window";     // how long the shake window stays open, ms

  private static final String CHANNEL = "shake";
  private static final int NOTIF_ID = 7;
  private static final long GRACE_MS = 2 * 60 * 1000;   // idle lifetime before giving up
  private static final long LOCK_TAIL_MS = 5 * 1000;    // CPU kept up after a shake, for the page

  // Mirrors the web-layer detector in index.html — keep the two in step.
  private static final double SHAKE_FORCE = 18;         // m/s² beyond gravity
  private static final int    SHAKE_PEAKS = 3;
  private static final long   SHAKE_PEAK_GAP_MS = 150;
  private static final long   SHAKE_SPAN_MS = 1200;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable backupOpenR = this::startWatching;
  private final Runnable windowOverR = this::quit;
  private final Runnable quitR = this::quit;
  private final Runnable dropLockR = this::dropLock;

  private SensorManager sensors;
  private PowerManager.WakeLock lock;
  // The page passes the real window with arm()/open(); this only covers a missing extra.
  private long windowMs = 60 * 1000;
  private boolean watching = false;
  private final ArrayDeque<Long> peaks = new ArrayDeque<>();
  private long lastPeak = 0;

  @Override public IBinder onBind(Intent i){ return null; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId){
    String action = intent == null ? ACTION_STOP : intent.getAction();
    if (ACTION_ARM.equals(action)){
      windowMs = intent.getLongExtra(EXTRA_WINDOW, windowMs);
      long deadline = intent.getLongExtra(EXTRA_DEADLINE, 0);
      stopWatching();
      handler.removeCallbacks(quitR);
      show("Shake-to-resume is standing by");
      // The page reports the timer firing itself (it is still running then, since it just
      // faded the audio out). This is only the fallback for a page that got frozen mid-run.
      handler.removeCallbacks(backupOpenR);
      if (deadline > 0){
        handler.postDelayed(backupOpenR, Math.max(0, deadline - System.currentTimeMillis()) + 30 * 1000);
      }
    } else if (ACTION_OPEN.equals(action)){
      windowMs = intent.getLongExtra(EXTRA_WINDOW, windowMs);
      startWatching();
    } else {
      quit();
    }
    return START_NOT_STICKY;
  }

  private void startWatching(){
    handler.removeCallbacks(backupOpenR);
    handler.removeCallbacks(quitR);
    if (watching) return;
    sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    if (acc == null){ quit(); return; }
    watching = true;
    peaks.clear(); lastPeak = 0;
    show("Shake the phone to resume playback");
    // The accelerometer keeps reporting with the screen off only while the CPU is up.
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    handler.removeCallbacks(dropLockR);
    lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiotimer:shake");
    handler.removeCallbacks(windowOverR);
    if (windowMs > 0){
      // Timed window (legacy behaviour, still supported if the page asks for one).
      lock.acquire(windowMs + 60 * 1000);
      handler.postDelayed(windowOverR, windowMs);
    } else {
      // windowMs 0: no deadline — the watch lives until the page says stop (selection
      // closed, day mode, app closed) or Android kills the task. The page owns the state.
      lock.acquire();
    }
    sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
  }

  private void stopWatching(){
    if (sensors != null) sensors.unregisterListener(this);
    watching = false;
    peaks.clear();
    handler.removeCallbacks(windowOverR);
  }

  @Override
  public void onSensorChanged(SensorEvent e){
    if (!watching) return;
    float x = e.values[0], y = e.values[1], z = e.values[2];
    double force = Math.abs(Math.sqrt(x*x + y*y + z*z) - SensorManager.GRAVITY_EARTH);
    long now = System.currentTimeMillis();
    if (force < SHAKE_FORCE || now - lastPeak < SHAKE_PEAK_GAP_MS) return;
    lastPeak = now;
    peaks.addLast(now);
    while (!peaks.isEmpty() && now - peaks.peekFirst() > SHAKE_SPAN_MS) peaks.removeFirst();
    if (peaks.size() >= SHAKE_PEAKS) shaken();
  }
  @Override public void onAccuracyChanged(Sensor s, int a){}

  private void shaken(){
    stopWatching();
    show("Resuming…");
    ShakeWatchPlugin.onShake();
    // Hold the CPU a few seconds longer so the page can actually start the audio; once it
    // plays, the audio pipeline keeps things awake on its own.
    handler.removeCallbacks(dropLockR);
    handler.postDelayed(dropLockR, LOCK_TAIL_MS);
    // Idle rather than die: the auto-armed next timer will re-arm this same service, which
    // a background start could not replace. If nothing claims it, it goes away quietly.
    handler.removeCallbacks(quitR);
    handler.postDelayed(quitR, GRACE_MS);
  }

  private void dropLock(){
    if (lock != null && lock.isHeld()) lock.release();
    lock = null;
  }

  private void quit(){
    stopWatching();
    dropLock();
    handler.removeCallbacksAndMessages(null);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  // The whole point of the service dies with the page, so go down with the task.
  @Override public void onTaskRemoved(Intent rootIntent){ quit(); }
  @Override public void onDestroy(){ stopWatching(); dropLock(); handler.removeCallbacksAndMessages(null); }

  private void show(String text){
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null){
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Shake to resume",
                                                       NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
    PendingIntent tap = PendingIntent.getActivity(this, 0,
        new Intent(this, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    android.app.Notification n = new NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Audio Timer")
        .setContentText(text)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(tap)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build();
    if (Build.VERSION.SDK_INT >= 34){
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
      startForeground(NOTIF_ID, n);
    }
  }
}
