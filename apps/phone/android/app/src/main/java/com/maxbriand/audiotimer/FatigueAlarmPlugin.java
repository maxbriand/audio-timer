package com.maxbriand.audiotimer;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the fatigue alarm.
 *
 * One call at the day-mode switch is the whole contract: schedule() arms the clock for
 * rise + N minutes and hands over the night's day key, so the score the alarm collects
 * later is filed with the night it describes. Everything after that call happens without
 * the page — the ringing, the answer, the staging into the outbox.
 */
@CapacitorPlugin(name = "FatigueAlarm")
public class FatigueAlarmPlugin extends Plugin {

  @PluginMethod
  public void schedule(PluginCall call){
    Integer minutes = call.getInt("minutes", 45);
    String nightDay = call.getString("nightDay", "");
    FatigueAlarm.schedule(getContext(),
      System.currentTimeMillis() + (minutes == null ? 45 : minutes) * 60000L, nightDay);
    call.resolve();
  }

  @PluginMethod
  public void cancel(PluginCall call){
    FatigueAlarm.cancel(getContext());
    call.resolve();
  }
}
