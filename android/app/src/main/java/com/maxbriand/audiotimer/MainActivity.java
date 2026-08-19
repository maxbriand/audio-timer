package com.maxbriand.audiotimer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
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
