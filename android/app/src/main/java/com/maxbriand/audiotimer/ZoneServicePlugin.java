package com.maxbriand.audiotimer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.WindowManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * The WebView's remote control for {@link HrService}: commands down, state up.
 * It carries no session logic of its own — anything decided here would stop
 * being decided the moment the page is throttled.
 */
@CapacitorPlugin(
    name = "ZoneService",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class ZoneServicePlugin extends Plugin {

    @Override
    public void load() {
        HrService.setListener(new HrService.Listener() {
            @Override public void onState(org.json.JSONObject state) {
                try {
                    notifyListeners("state", JSObject.fromJSONObject(state));
                } catch (Exception e) {
                    // The page is gone or mid-reload; the engine carries on regardless.
                }
            }
            @Override public void onLive(org.json.JSONObject ev) {
                try {
                    notifyListeners("live", JSObject.fromJSONObject(ev));
                } catch (Exception e) { /* same: display-only traffic */ }
            }
        });
    }

    /** Live-data page on/off — the service starts/stops the PMD streams and DIS reads. */
    @PluginMethod
    public void liveMode(PluginCall call) {
        Intent i = new Intent(getContext(), HrService.class)
            .setAction(HrService.ACTION_LIVE)
            .putExtra("on", Boolean.TRUE.equals(call.getBoolean("on", false)));
        send(i);
        call.resolve();
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "connectAfterPermission");
            return;
        }
        doConnect(call);
    }

    @PermissionCallback
    private void connectAfterPermission(PluginCall call) {
        // Start even if the user denied the notification — the service still
        // runs, its notification just stays hidden.
        doConnect(call);
    }

    private void doConnect(PluginCall call) {
        String deviceId = call.getString("deviceId");
        if (deviceId == null) { call.reject("no deviceId"); return; }
        Intent i = new Intent(getContext(), HrService.class)
            .setAction(HrService.ACTION_CONNECT)
            .putExtra("deviceId", deviceId);
        startService(i, true);
        call.resolve();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        send(new Intent(getContext(), HrService.class).setAction(HrService.ACTION_DISCONNECT));
        call.resolve();
    }

    @PluginMethod
    public void session(PluginCall call) {
        boolean start = Boolean.TRUE.equals(call.getBoolean("start", false));
        Intent i = new Intent(getContext(), HrService.class)
            .setAction(HrService.ACTION_SESSION)
            .putExtra("start", start);
        // Starting a session is reason enough to run the engine; ending one is not.
        startService(i, start);
        call.resolve();
    }

    @PluginMethod
    public void settings(PluginCall call) {
        Intent i = new Intent(getContext(), HrService.class)
            .setAction(HrService.ACTION_SETTINGS)
            .putExtra("min", call.getInt("min", 80))
            .putExtra("max", call.getInt("max", 170))
            .putExtra("delay", call.getInt("delay", 10))
            .putExtra("hrmax", call.getInt("hrmax", 0))
            .putExtra("resting", call.getInt("resting", 0))
            .putExtra("vib", Boolean.TRUE.equals(call.getBoolean("vib", true)))
            .putExtra("snd", Boolean.TRUE.equals(call.getBoolean("snd", false)));
        send(i);
        call.resolve();
    }

    /** Fire-and-forget buzz for the toggle preview — not part of the alert path. */
    @PluginMethod
    public void vibrate(PluginCall call) {
        try {
            JSArray arr = call.getArray("pattern");
            Alerts alerts = new Alerts(getContext());
            if (arr == null || arr.length() == 0) { alerts.cancel(); call.resolve(); return; }
            // Web Vibration API pattern [on, off, on, …] → Android waveform
            // needs a leading delay entry.
            long[] pattern = new long[arr.length() + 1];
            pattern[0] = 0;
            for (int j = 0; j < arr.length(); j++) pattern[j + 1] = arr.getLong(j);
            alerts.vibrate(pattern);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    /** Only start the engine for commands that warrant it; never resurrect it for the rest. */
    private void startService(Intent i, boolean mayStart) {
        if (!mayStart && !HrService.isRunning()) return;
        try {
            Context c = getContext();
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Android 12+ refuses a foreground start from the background; the
            // page is only ever driving this while it is on screen, so a refusal
            // means the command simply does not apply.
        }
    }

    private void send(Intent i) { startService(i, false); }
}
