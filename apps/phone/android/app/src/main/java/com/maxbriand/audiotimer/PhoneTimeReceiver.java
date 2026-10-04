package com.maxbriand.audiotimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* 00:05: the day just closed gets its total in the log, and its sessions go to the server
   — whenever the phone next has a network. The log is read off the main thread. */
public class PhoneTimeReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent){
    PhoneTime.armMidnight(c);
    PhoneTimeWorker.schedule(c);
    PendingResult done = goAsync();
    Context app = c.getApplicationContext();
    new Thread(() -> {
      try { PhoneTime.recordDays(app); } finally { done.finish(); }
    }).start();
  }
}
