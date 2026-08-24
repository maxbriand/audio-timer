package com.maxbriand.audiotimer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

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
    String label = call.getString("label");
    if (deadline != null) i.putExtra(ShakeService.EXTRA_DEADLINE, deadline.longValue());
    if (window != null) i.putExtra(ShakeService.EXTRA_WINDOW, window.longValue());
    if (label != null) i.putExtra(ShakeService.EXTRA_LABEL, label);
    return i;
  }

  // Never rejected: shake-to-resume is a bonus on top of the timer, and the timer must not
  // care whether Android happened to refuse the service this time.
  private void send(String action, PluginCall call){
    try { ContextCompat.startForegroundService(getContext(), intent(action, call)); }
    catch (Exception ignored) {}
    call.resolve();
  }

  /* Doze ignores app wake locks, which is how the shake watch went deaf on a phone lying
     still with the screen off. The system exemption dialog is the documented way out, and
     arming a timer — the app visible, the feature being engaged — is the honest moment to
     ask. Once: a refusal is an answer, and nagging every night is worse than the bug. */
  private void askDozeExemptionOnce(){
    try {
      Context c = getContext();
      PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
      if (pm.isIgnoringBatteryOptimizations(c.getPackageName())) return;
      SharedPreferences p = c.getSharedPreferences("shakewatch", Context.MODE_PRIVATE);
      if (p.getBoolean("askedDoze", false)) return;
      p.edit().putBoolean("askedDoze", true).apply();
      Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + c.getPackageName()));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(i);
    } catch (Exception ignored) {}
  }

  @PluginMethod public void arm(PluginCall call){
    askDozeExemptionOnce();
    send(ShakeService.ACTION_ARM, call);
  }
  @PluginMethod public void open(PluginCall call){ send(ShakeService.ACTION_OPEN, call); }

  /* What the night watch is actually working with, for the ⚙ health row: whether doze can
     silence it (no battery exemption) and whether the hardware has the wake-up accelerometer
     that keeps reporting through CPU suspend. Diagnosis, not control — reading it changes
     nothing. */
  @PluginMethod
  public void status(PluginCall call){
    Context c = getContext();
    JSObject r = new JSObject();
    PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
    r.put("exempt", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
    SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
    r.put("wakeSensor", sm != null && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) != null);
    call.resolve(r);
  }

  /* The once-only rule above is for unprompted nagging; a tap on the ⚙ health row is the
     user asking, so the dialog may be shown again. */
  @PluginMethod
  public void askExemption(PluginCall call){
    try {
      Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getContext().getPackageName()));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
    } catch (Exception ignored) {}
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call){
    try { getContext().stopService(new Intent(getContext(), ShakeService.class)); }
    catch (Exception ignored) {}
    call.resolve();
  }
}
