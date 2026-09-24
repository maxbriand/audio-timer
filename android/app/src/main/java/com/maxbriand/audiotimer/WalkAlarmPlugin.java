package com.maxbriand.audiotimer;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the morning-light reminder: schedule() at the wake-up row (the page
 * checks the ⚙ toggle and whether a walk is already logged before calling), cancel() when
 * the walk gets logged by hand or the morning ends back in bed. The ringing and the Done
 * answer happen without the page.
 */
@CapacitorPlugin(name = "WalkAlarm")
public class WalkAlarmPlugin extends Plugin {

  /* `at` is the rise (epoch ms as a string, like WakeAlarm.wokeAt): the ring is 30 minutes
     after IT, so a rise reported late by the alarm's "I'm up" still rings on time. A ring
     whose moment already passed goes off right away. A walk already answered by Done today
     (a row the page never sees — it goes straight to the outbox) means nothing to remind. */
  @PluginMethod
  public void schedule(PluginCall call){
    Integer minutes = call.getInt("minutes", 30);
    long now = System.currentTimeMillis();
    String at = call.getString("at", "");
    long rise;
    try { rise = at.isEmpty() ? now : Long.parseLong(at); }
    catch (NumberFormatException e){ rise = now; }
    if (WalkAlarm.doneToday(getContext())){ call.resolve(); return; }
    long ring = rise + (minutes == null ? 30 : minutes) * 60000L;
    WalkAlarm.schedule(getContext(), Math.max(ring, now + 1000));
    call.resolve();
  }

  @PluginMethod
  public void cancel(PluginCall call){
    WalkAlarm.cancel(getContext());
    call.resolve();
  }
}
