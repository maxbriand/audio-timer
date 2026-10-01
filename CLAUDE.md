# audio-timer — rules for working on this app

## Rings (alarms, reminders, fatigue checks)

The phone is a **Redmi Note 10S on MIUI 14 (Android 13)**. Stock Android and the emulator do not
behave like it, so a ring that works on the emulator proves nothing on its own.

- **Every ring goes through `Ring.java`**: `Ring.builder(c, channel, name)` to build the
  notification, `Ring.post(c, id, b)` to post it. Never `setOngoing(true)` on a ring: MIUI's
  SystemUI deletes an ongoing notification from this app 0.23 s after it is posted, and the
  full-screen screen that rings goes with it (2026-10-01 — checks fired on time, never rang).
- **One notification id per ring**, declared in `Ring.java` (`ID_*`). Two rings sharing an id
  cancel each other.
- `scripts/check-rings.mjs` enforces both on every `npm run apk` and stops the build otherwise.
  Do not weaken it to get a build through — fix the ring.
- **A Test button for a ring goes through the real path**: an alarm a few seconds ahead, the
  receiver, `Ring.post()` — as `FatigueChecks.ringTest()` does. Never start the ring's activity
  directly from a Test: that skips the notification, which is exactly where MIUI blocked the
  real rings while every test passed.
- On the phone, these must stay allowed (Settings → Apps → Audio Timer → Other permissions):
  "Open new windows while running in the background" and "Show on Lock screen".

## When something doesn't ring or doesn't show on the phone

Read the phone before changing code. Ask Maxime to plug it in (USB debugging is on), then:

```
adb logcat -b all                                  # what happened at the ring time
adb shell dumpsys alarm | grep -A3 audiotimer      # what is armed, what fired
adb shell dumpsys notification --noredact          # channels, posted/cancelled notifications
adb shell cmd appops get com.maxbriand.audiotimer  # MIUI's own permissions (MIUIOP …)
```

`adb` is at `~/Library/Android/sdk/platform-tools/adb`. A notification cancelled right after
`notification_enqueue`, or `filter out ongoing notif`, is MIUI, not the app's logic.

## Delivering a build

`npm run apk` builds the signed release into `~/Downloads/audio-timer-standalone.apk`. A build
is delivered when it is on the phone: copy it over the Drive file and cut an
`audio-timer-builds` release (see the README); with the phone plugged in,
`adb install -r` installs it in place, data kept.
