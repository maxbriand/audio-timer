package com.maxbriand.audiotimer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on the melatonin reminder: one call with the bedtime ("HH:MM") arms
 * the daily cycle at bedtime − 5 h, an empty string turns it off. The page pushes the
 * stored value on every boot, same as the upload config, so the two sides cannot drift.
 *
 * The call answers with missedAt: the epoch ms of an armed fire that passed without the
 * receiver ever showing it (0 when none) — read BEFORE re-arming, which would erase the
 * evidence. The page turns it into the visible missed-ring warning.
 */
@CapacitorPlugin(name = "MelatoninAlarm")
public class MelatoninAlarmPlugin extends Plugin {

  @PluginMethod
  public void configure(PluginCall call){
    long missed = MelatoninAlarm.missedFire(getContext());
    Integer lead = call.getInt("leadMin", MelatoninAlarm.LEAD_DEFAULT);
    MelatoninAlarm.configure(getContext(), call.getString("bedtime", ""),
      lead == null ? MelatoninAlarm.LEAD_DEFAULT : lead);
    JSObject ret = new JSObject();
    ret.put("missedAt", missed);
    call.resolve(ret);
  }

  /* The dose was logged on the day screen: silence a ring in progress and withdraw
     tonight's if it was still ahead. The page keeps the record; nothing is staged here. */
  @PluginMethod
  public void dosed(PluginCall call){
    ((android.app.NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE))
      .cancel(MelatoninReceiver.NOTIF_ID);
    MelatoninAlarm.dosed(getContext());
    call.resolve();
  }
}
