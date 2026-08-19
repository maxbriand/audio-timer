package com.maxbriand.audiotimer;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the melatonin reminder: one call with the bedtime ("HH:MM") arms
 * the daily cycle at bedtime − 5 h, an empty string turns it off. The page pushes the
 * stored value on every boot, same as the upload config, so the two sides cannot drift.
 */
@CapacitorPlugin(name = "MelatoninAlarm")
public class MelatoninAlarmPlugin extends Plugin {

  @PluginMethod
  public void configure(PluginCall call){
    MelatoninAlarm.configure(getContext(), call.getString("bedtime", ""));
    call.resolve();
  }
}
