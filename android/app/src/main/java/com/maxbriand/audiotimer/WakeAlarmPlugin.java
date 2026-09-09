package com.maxbriand.audiotimer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the wake-up alarm. configure() arms the daily cycle from the goal
 * and the accepted delay (an empty goal turns it off); wokeAt() reports a rise so tomorrow
 * is recomputed by the same rule the "I'm up" button uses; status() hands back the armed
 * moment so the page can show the next wake-up; consumeUp() is how the page learns that
 * "I'm up" was pressed while it was not running, and answers with the moment it happened.
 */
@CapacitorPlugin(name = "WakeAlarm")
public class WakeAlarmPlugin extends Plugin {

  @PluginMethod
  public void configure(PluginCall call){
    Integer delay = call.getInt("delayMin", 30);
    WakeAlarm.configure(getContext(), call.getString("goal", ""), delay == null ? 30 : delay);
    status(call);
  }

  @PluginMethod
  public void wokeAt(PluginCall call){
    String at = call.getString("at", "");
    long millis;
    try { millis = at.isEmpty() ? System.currentTimeMillis() : Long.parseLong(at); }
    catch (NumberFormatException e){ millis = System.currentTimeMillis(); }
    WakeAlarm.wokeAt(getContext(), millis);
    status(call);
  }

  @PluginMethod
  public void status(PluginCall call){
    JSObject ret = new JSObject();
    ret.put("goal", WakeAlarm.goal(getContext()));
    ret.put("delayMin", WakeAlarm.delayMin(getContext()));
    ret.put("nextAt", String.valueOf(WakeAlarm.nextAt(getContext())));
    ret.put("snoozed", WakeAlarm.snoozed(getContext()));
    call.resolve(ret);
  }

  @PluginMethod
  public void consumeUp(PluginCall call){
    long at = WakeAlarm.consumeUp(getContext());
    JSObject ret = new JSObject();
    ret.put("at", String.valueOf(at));           // 0 = nothing pending
    call.resolve(ret);
  }
}
