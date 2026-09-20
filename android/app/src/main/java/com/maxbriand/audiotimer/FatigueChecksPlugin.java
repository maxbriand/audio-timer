package com.maxbriand.audiotimer;

import android.content.Context;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/*
 * The page's handle on fatigue tracking (⚙ → Day).
 *
 * configure() is the whole schedule in one call — the list of checks, the last wake-up of
 * the page's log, the night's length, the strap's address — and answers with what it armed,
 * so ⚙ can show each check's next ring rather than assume it. wokeAt() and cancelWake() are
 * the two moments the morning's anchor moves from the page: the rise, and going back to
 * bed. test() runs a check now, exactly as its ring would. results()/ack() are how a
 * finished check reaches the page's log: handed over, written, and only then forgotten here.
 */
@CapacitorPlugin(name = "FatigueChecks")
public class FatigueChecksPlugin extends Plugin {

  // Epoch ms travel as strings, as everywhere else on this bridge.
  private static long millis(PluginCall call, String key){
    try { return Long.parseLong(call.getString(key, "0")); }
    catch (NumberFormatException e){ return 0; }
  }

  @PluginMethod
  public void configure(PluginCall call){
    Integer sleep = call.getInt("sleepMin", 540);
    FatigueChecks.configure(getContext(), call.getString("checks", "[]"), millis(call, "lastWake"),
      sleep == null ? 540 : sleep, call.getString("strap", ""));
    status(call);
  }

  @PluginMethod
  public void wokeAt(PluginCall call){
    long at = millis(call, "at");
    FatigueChecks.wokeAt(getContext(), at == 0 ? System.currentTimeMillis() : at);
    status(call);
  }

  @PluginMethod
  public void cancelWake(PluginCall call){
    FatigueChecks.cancelWake(getContext());
    status(call);
  }

  /** Each check's armed moment ("0" = not armed) and the bedtime the evening ones hang on. */
  @PluginMethod
  public void status(PluginCall call){
    Context c = getContext();
    JSObject armed = new JSObject();
    JSONArray all = FatigueChecks.checks(c);
    for (int i = 0; i < all.length(); i++){
      JSONObject k = all.optJSONObject(i);
      if (k != null) armed.put(k.optString("id"), String.valueOf(FatigueChecks.armedAt(c, k.optString("id"))));
    }
    JSObject r = new JSObject();
    r.put("armed", armed);
    r.put("bedtime", String.valueOf(FatigueChecks.bedtime(c)));
    r.put("strapKnown", !FatigueChecks.strap(c).isEmpty());
    call.resolve(r);
  }

  /* The Test button: the same screen, the same ring, the same record — started now, from
     the steps given rather than from a saved check, so a check can be tried before it is
     kept. */
  @PluginMethod
  public void test(PluginCall call){
    try {
      JSONObject k = new JSONObject();
      k.put("id", call.getString("id", "test"));
      k.put("anchor", call.getString("anchor", "wake"));
      k.put("offsetMin", call.getInt("offsetMin", 0));
      k.put("strap", Boolean.TRUE.equals(call.getBoolean("strap", false)));
      k.put("pvt", Boolean.TRUE.equals(call.getBoolean("pvt", false)));
      k.put("question", Boolean.TRUE.equals(call.getBoolean("question", false)));
      String strap = call.getString("strapAddress", "");
      if (strap != null && !strap.isEmpty())
        FatigueChecks.configureStrap(getContext(), strap);
      getContext().startActivity(FatigueCheckActivity.intent(getContext(), k, 0, true));
      call.resolve();
    } catch (Exception e){
      call.reject(e.getMessage());
    }
  }

  @PluginMethod
  public void results(PluginCall call){
    JSObject r = new JSObject();
    try { r.put("results", new JSArray(FatigueChecks.results(getContext()).toString())); }
    catch (Exception e){ r.put("results", new JSArray()); }
    call.resolve(r);
  }

  @PluginMethod
  public void ack(PluginCall call){
    JSArray ids = call.getArray("ids");
    if (ids != null) FatigueChecks.ack(getContext(), ids);
    call.resolve();
  }
}
