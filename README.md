# Audio Timer

Offline audio player for the phone (Redmi Note 10S / Chrome) with a custom sleep timer
that saves the exact moment it stops.

It grew into the phone side of the Body asset: night and fatigue tracking with the Polar
H10, alarms and reminders, and an upload of every night to a receiver on the Mac.

## Layout

| Folder | What is in it |
|---|---|
| `apps/phone/` | The app — `index.html` (the whole web app) wrapped by Capacitor into the APK (`android/`), with its build scripts |
| `apps/receiver/` | `log-receiver.py`, the server end of the upload |
| `deploy/` | The Mac's launchd jobs (receiver, tunnel, nightly sync, computer time) and the sync script they run |
| `scripts/` | Data tools over the uploaded nights: CSV roll-up, daily record, exercise log, computer time, manual Body import |
| `docs/` | Everything else written down |

## Docs

- [features.md](docs/features.md) — what the app does
- [phone-app.md](docs/phone-app.md) — building the APK, storage design, implementation notes
- [setup.md](docs/setup.md) — one-time setup for the server upload, phone and Mac
- [body-import.md](docs/body-import.md) — how the sessions reach `~/Documents/Body/`

## Build and install

```bash
cd apps/phone && npm install && npm run apk
```

Leaves the signed APK in `~/Downloads/audio-timer-standalone.apk`. Delivering it to the
phone is in [CLAUDE.md](CLAUDE.md).
