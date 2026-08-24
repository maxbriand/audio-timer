package com.maxbriand.audiotimer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  // Set by ShakeService's full-screen intent: this launch exists to thaw a frozen WebView
  // so a queued night-time shake can land, so it must show over the keyguard. Every other
  // launch clears the flags again — the app has no business over the lock screen otherwise.
  static final String EXTRA_WAKE_FOR_SHAKE = "wakeForShake";

  private void applyWakeFlags(Intent intent){
    boolean wake = intent != null && intent.getBooleanExtra(EXTRA_WAKE_FOR_SHAKE, false);
    if (Build.VERSION.SDK_INT >= 27){
      setShowWhenLocked(wake);
      setTurnScreenOn(wake);
    }
  }

  @Override
  protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    applyWakeFlags(intent);
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    applyWakeFlags(getIntent());
    registerPlugin(ShakeWatchPlugin.class);
    registerPlugin(LogUploadPlugin.class);
    registerPlugin(FatigueAlarmPlugin.class);
    registerPlugin(MelatoninAlarmPlugin.class);
    super.onCreate(savedInstanceState);
    // Android 13+ hides the shake-watch notification unless this is granted. The service
    // runs either way — the notification is just how the night-time watch stays honest.
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
  }
}
