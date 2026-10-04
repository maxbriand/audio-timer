package com.maxbriand.audiotimer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the wake-up alarm. configure() arms it from the goal, the accepted
 * delay and the last wake-up of the page's log (an empty goal turns it off); wokeAt()
 * reports a rise, the new last wake-up, so the alarm is recomputed by the same rule the
 * "I'm up" button uses; status() hands back the armed
 * moment so the page can show the next wake-up; consumeUp() is how the page learns that
 * "I'm up" was pressed while it was not running, and answers with the moment it happened.
 */
@CapacitorPlugin(name = "WakeAlarm")
public class WakeAlarmPlugin extends Plugin {

  @PluginMethod
  public void configure(PluginCall call){
    Integer delay = call.getInt("delayMin", 30);
    // Epoch ms travel as strings: a JS number that size does not survive getInt/getLong
    // on every bridge version. Empty or unreadable means the log has no wake-up.
    long lastWake;
    try { lastWake = Long.parseLong(call.getString("lastWake", "0")); }
    catch (NumberFormatException e){ lastWake = 0; }
    WakeAlarm.configure(getContext(), call.getString("goal", ""), delay == null ? 30 : delay, lastWake);
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
