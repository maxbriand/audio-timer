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

  @PluginMethod
  public void schedule(PluginCall call){
    Integer minutes = call.getInt("minutes", 30);
    WalkAlarm.schedule(getContext(),
      System.currentTimeMillis() + (minutes == null ? 30 : minutes) * 60000L);
    call.resolve();
  }

  @PluginMethod
  public void cancel(PluginCall call){
    WalkAlarm.cancel(getContext());
    call.resolve();
  }
}
