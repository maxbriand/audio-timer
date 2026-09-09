// Native BLE bridge for the Live page, bundled by esbuild into www/native-ble.js.
// Only activates inside the Capacitor shell: the WebView has no Web Bluetooth, so
// GATT goes through @capacitor-community/bluetooth-le instead. On the hosted PWA
// this file is absent and the page talks navigator.bluetooth itself — one decoder
// up in the page serves both paths (the same split zone-alarm uses).
//
// Everything is page-scoped on purpose: no service, no background stream. The Live
// page owns the connection while it is open and releases the strap when it closes.
import { Capacitor } from '@capacitor/core';
import { BleClient, numberToUUID } from '@capacitor-community/bluetooth-le';

if (Capacitor.isNativePlatform()) {
  const HR_SERVICE = numberToUUID(0x180d);
  const HR_MEASUREMENT = numberToUUID(0x2a37);
  const BATT_SERVICE = numberToUUID(0x180f);
  const BATT_LEVEL = numberToUUID(0x2a19);
  const DIS_SERVICE = numberToUUID(0x180a);
  const DIS = { model: numberToUUID(0x2a24), serial: numberToUUID(0x2a25), fw: numberToUUID(0x2a26) };
  const PMD_SERVICE = 'fb005c80-02e7-f387-1cad-8acd2d8df0c8';
  const PMD_CTRL = 'fb005c81-02e7-f387-1cad-8acd2d8df0c8';
  const PMD_DATA = 'fb005c82-02e7-f387-1cad-8acd2d8df0c8';

  let deviceId = null;

  const asView = bytes => new DataView(Uint8Array.from(bytes).buffer);

  window.NativeH10 = {
    async request() {
      await BleClient.initialize({ androidNeverForLocation: true });
      const device = await BleClient.requestDevice({
        services: [HR_SERVICE],
        optionalServices: [BATT_SERVICE, DIS_SERVICE, PMD_SERVICE]
      });
      deviceId = device.deviceId;
    },

    async connect(onDisconnect) {
      await BleClient.connect(deviceId, () => { if (onDisconnect) onDisconnect(); });
    },

    async disconnect() {
      if (deviceId) await BleClient.disconnect(deviceId).catch(() => {});
    },

    async notifyHr(cb) {
      await BleClient.startNotifications(deviceId, HR_SERVICE, HR_MEASUREMENT, cb);
    },

    async readBatt() {
      return (await BleClient.read(deviceId, BATT_SERVICE, BATT_LEVEL)).getUint8(0);
    },

    async readDis() {
      const dec = new TextDecoder();
      const out = {};
      for (const [k, ch] of Object.entries(DIS)) {
        try {
          out[k] = dec.decode((await BleClient.read(deviceId, DIS_SERVICE, ch)).buffer)
            .replace(/\0+$/, '');
        } catch (_) {}
      }
      return out;
    },

    // The page hands over the exact PMD "start measurement" commands it also uses on
    // the web path, so the H10 stream settings live in one place — the page.
    async startPmd(startCmds, cb) {
      await BleClient.startNotifications(deviceId, PMD_SERVICE, PMD_DATA, cb);
      for (const cmd of startCmds) {
        await BleClient.write(deviceId, PMD_SERVICE, PMD_CTRL, asView(cmd));
      }
    },

    async stopPmd(stopCmds) {
      try {
        for (const cmd of stopCmds) {
          await BleClient.write(deviceId, PMD_SERVICE, PMD_CTRL, asView(cmd));
        }
        await BleClient.stopNotifications(deviceId, PMD_SERVICE, PMD_DATA);
      } catch (_) { /* the stream dies with the connection anyway */ }
    }
  };
}
