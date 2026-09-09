package com.maxbriand.audiotimer;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the wake-up alarm: one call with the time ("HH:MM") arms the
 * daily cycle, an empty string turns it off. The page pushes a set time on every boot
 * and every save, so the two sides cannot drift.
 */
@CapacitorPlugin(name = "WakeAlarm")
public class WakeAlarmPlugin extends Plugin {

  @PluginMethod
  public void configure(PluginCall call){
    WakeAlarm.configure(getContext(), call.getString("time", ""));
    call.resolve();
  }
}
