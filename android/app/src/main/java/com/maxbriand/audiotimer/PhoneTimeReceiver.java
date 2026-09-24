package com.maxbriand.audiotimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* 00:05: the day just closed goes to the server — whenever the phone next has a network. */
public class PhoneTimeReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent){
    PhoneTime.armMidnight(c);
    PhoneTimeWorker.schedule(c, false);
  }
}
