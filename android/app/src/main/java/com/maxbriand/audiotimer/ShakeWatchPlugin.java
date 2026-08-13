package com.maxbriand.audiotimer;

import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/*
 * The page's handle on ShakeService. arm() is called while the app is still visible (the
 * moment a sleep timer is set), which is the only time Android lets a foreground service
 * start; open() and stop() just talk to the already-running one. A detected shake comes
 * back as the "shake" event, retained until the page consumes it.
 */
@CapacitorPlugin(name = "ShakeWatch")
public class ShakeWatchPlugin extends Plugin {
  private static ShakeWatchPlugin instance;

  @Override
  public void load(){ instance = this; }

  static void onShake(){
    ShakeWatchPlugin p = instance;
    if (p != null) p.notifyListeners("shake", new JSObject(), true);
  }

  private Intent intent(String action, PluginCall call){
    Intent i = new Intent(getContext(), ShakeService.class);
    i.setAction(action);
    Double deadline = call.getDouble("deadline");
    Double window = call.getDouble("windowMs");
    if (deadline != null) i.putExtra(ShakeService.EXTRA_DEADLINE, deadline.longValue());
    if (window != null) i.putExtra(ShakeService.EXTRA_WINDOW, window.longValue());
    return i;
  }

  // Never rejected: shake-to-resume is a bonus on top of the timer, and the timer must not
  // care whether Android happened to refuse the service this time.
  private void send(String action, PluginCall call){
    try { ContextCompat.startForegroundService(getContext(), intent(action, call)); }
    catch (Exception ignored) {}
    call.resolve();
  }

  @PluginMethod public void arm(PluginCall call){ send(ShakeService.ACTION_ARM, call); }
  @PluginMethod public void open(PluginCall call){ send(ShakeService.ACTION_OPEN, call); }

  @PluginMethod
  public void stop(PluginCall call){
    try { getContext().stopService(new Intent(getContext(), ShakeService.class)); }
    catch (Exception ignored) {}
    call.resolve();
  }
}
