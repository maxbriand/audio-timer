// Native bridge for the embedded zone-alarm app, bundled by esbuild into
// www/native-ble.js — zone-alarm's own bridge.js, carried over as is.
//
// Only activates inside the Capacitor shell; on the hosted PWA this file is absent
// and zone-alarm's page talks navigator.bluetooth itself. The shell does not run
// the cardio session — HrService does. This file is only a wire: the picker on the
// way in, commands down, state up. Anything that must survive the screen going off
// belongs in the service, not here.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { BleClient, numberToUUID } from '@capacitor-community/bluetooth-le';

if (Capacitor.isNativePlatform()) {
  const ZoneService = registerPlugin('ZoneService');

  const HR_SERVICE = numberToUUID(0x180d);
  const BATT_SERVICE = numberToUUID(0x180f);

  // The strap's address outlives the page: the app now connects by itself (Start session,
  // 🫀), and a picker on every connection would be a tax. It is forgotten when a connection
  // to it fails, so a changed or re-paired strap gets the picker back.
  const DEVICE_KEY = 'za-device';
  let deviceId = null;
  try { deviceId = localStorage.getItem(DEVICE_KEY) || null; } catch (_) {}

  window.NativeBLE = {
    // Picker only — it scans and returns the strap's address. The GATT
    // connection itself is made natively, so there is never a second client
    // holding the H10.
    async requestDevice() {
      await BleClient.initialize({ androidNeverForLocation: true });
      const device = await BleClient.requestDevice({
        services: [HR_SERVICE],
        optionalServices: [BATT_SERVICE]
      });
      deviceId = device.deviceId;
      try { localStorage.setItem(DEVICE_KEY, deviceId); } catch (_) {}
    },

    hasDevice() { return !!deviceId; },

    forgetDevice() {
      deviceId = null;
      try { localStorage.removeItem(DEVICE_KEY); } catch (_) {}
    },

    // Hands the address to the service, which connects, subscribes, and keeps
    // reconnecting on its own. Resolves once the command is delivered; the
    // connection itself is reported through the state stream.
    async connect() {
      if (!deviceId) throw new Error('no device');
      await ZoneService.connect({ deviceId });
    },

    async disconnect() {
      await ZoneService.disconnect().catch(() => {});
    },

    vibrate(pattern) {
      ZoneService.vibrate({ pattern: Array.isArray(pattern) ? pattern : [pattern] }).catch(() => {});
    },

    cancelVibrate() {
      ZoneService.vibrate({ pattern: [] }).catch(() => {});
    },

    keepAwake(on) {
      ZoneService.keepAwake({ on }).catch(() => {});
    },

    // Live-data page: the service subscribes/unsubscribes the extra GATT
    // streams (PMD ECG + accelerometer, device info) and forwards raw bytes;
    // the page does all the decoding.
    liveMode(on) {
      ZoneService.liveMode({ on }).catch(() => {});
    }
  };

  window.NativeLive = {
    on(cb) { ZoneService.addListener('live', cb); }
  };

  window.NativeSession = {
    // ~1/s while the engine runs; the page renders whatever it last received.
    onState(cb) {
      ZoneService.addListener('state', cb);
    },
    start() { ZoneService.session({ start: true }).catch(() => {}); },
    end() { ZoneService.session({ start: false }).catch(() => {}); },
    // Cool down pressed (or taken back): the below-range alarm goes quiet and the next
    // drop under the range low opens the cool down instead of another part.
    cooldown(on) { ZoneService.cooldown({ on }).catch(() => {}); },
    settings(s) { ZoneService.settings(s).catch(() => {}); }
  };
}
