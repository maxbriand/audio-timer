package com.maxbriand.audiotimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/*
 * A reboot silently drops every scheduled alarm — the one thing a classic alarm clock is not
 * allowed to do. So both alarms are re-armed from their prefs. The fatigue check: still in
 * the future, schedule it again; missed while the phone was off, ring now rather than never
 * (a late answer is an answer; silence is a hole in the diary). The melatonin reminder:
 * missed by less than its 5-hour lead, ring now — the dose is still worth taking before
 * bed; missed by more, or not yet due, just arm the next occurrence.
 */
public class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent){
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

    long at = FatigueAlarm.at(c);
    if (at != 0){
      if (at > System.currentTimeMillis()){
        FatigueAlarm.schedule(c, at, FatigueAlarm.nightDay(c));
      } else {
        FatigueAlarm.clear(c);
        FatigueAlarmReceiver.show(c);
      }
    }

    if (!MelatoninAlarm.bedtime(c).isEmpty()){
      long next = MelatoninAlarm.nextAt(c);
      long missedBy = System.currentTimeMillis() - next;
      if (next != 0 && missedBy > 0 && missedBy < MelatoninAlarm.LEAD_MIN * 60000L){
        MelatoninReceiver.show(c);
      }
      MelatoninAlarm.scheduleNext(c);
    }
  }
}
