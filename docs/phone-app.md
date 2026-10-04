# The phone app

How `apps/phone/` is built, installed and laid out inside. What it does is in
[features.md](features.md); the server side is in [setup.md](setup.md).

## Files

All paths are under `apps/phone/` unless they say otherwise.

| File | Role |
|---|---|
| `index.html` | The whole app — UI, IndexedDB storage, player, sleep timer, server upload |
| `sw.js` | Service worker, precaches the shell so it opens offline |
| `manifest.webmanifest` | Makes it installable as a standalone app |
| `icon-*.png` | Launcher icons (generated, see below) |
| `native/ble.js` | The Live page's BLE bridge, bundled into `www/native-ble.js` |
| `android/…/LogUploadPlugin.java` | The page's handle on the outbox: stage a night, ask what landed |
| `android/…/Outbox.java` | The staged nights on disk, and where to send them |
| `android/…/UploadWorker.java` | Sends them when the phone next has a network, app closed |
| `capacitor.config.json` | Native shell config — app id, name, background colour |
| `android/` | Capacitor's generated Android project (committed; build output is not) |
| `scripts/build-www.mjs` | Copies the web assets into `www/` for the APK |
| `scripts/build-apk.mjs` | Gradle release build → `~/Downloads/audio-timer-standalone.apk` |
| `scripts/check-rings.mjs` | Stops the build on a ring that breaks the ring rules (see `CLAUDE.md`) |
| `scripts/make-icons.py` | The PWA icons |
| `scripts/make-android-icons.py` | Adaptive launcher icons from the same mark as the PWA |
| `scripts/bench.html` | Throwaway harness used to measure the storage writes (see below) |
| `apps/receiver/log-receiver.py` | The server end of the upload — files each night by day, stdlib only |

The web app itself has no build step and no dependencies — `index.html` is the whole thing.
The Capacitor tooling only exists to wrap that same file into an APK, and never edits it.

## Installing as an APK

The same app, wrapped by [Capacitor](https://capacitorjs.com) into an ordinary Android app —
its own launcher icon and no browser chrome, and nothing to install it from.

```bash
cd apps/phone && npm install && npm run apk
```

That rebuilds `www/`, syncs it into `android/`, runs a signed Gradle release build and leaves
`~/Downloads/audio-timer-standalone.apk`. Copy it to the phone and open it.

**What this does and does not change.** Capacitor renders in Android's WebView, which since
Android 10 is its own package (*Android System WebView*) and not part of Chrome — so Chrome can
be absent or disabled and the app still runs. What it does *not* do is ship a browser engine
inside the APK; the 3.6 MB APK is the web app plus the Capacitor bridge, and it uses whatever
WebView the phone has. Genuinely bundling an engine means GeckoView (~70 MB), which Capacitor
does not support.

**Keep the keystore.** `apps/phone/android/keystore.properties` and `apps/phone/android/audio-timer-release.keystore`
are gitignored and exist only on this Mac. Android refuses to install an upgrade signed by a
different key, so losing them means uninstalling the app — and its library — before the next
build will install.

## Installing as a PWA (retired)

Until October 2026 GitHub Pages served the app from the repo root at
https://maxbriand.github.io/audio-timer/, to be installed from Chrome (⋮ → **Install app**).
The app now lives in `apps/phone/`, so that URL no longer serves it; the APK above is the
way to install. Chrome still runs `index.html` as a plain web page for development (below),
where the BLE, rings and background upload are absent — they are native.

## How storage is laid out, and why

Three things live in IndexedDB, and the split between the first two is what keeps writes cheap:

| Store | Holds | Written |
|---|---|---|
| `tracks` | the audio blob, name, duration, size | once at import |
| `positions` | where each track is up to, keyed by track id | every 5 s of playback |
| `sessions` | the run log the ☾ sheet shows | at the end of each run |

Position used to live on the track record itself, which meant every 5-second save handed the
whole record — audio included — back to IndexedDB. There is no copy-on-write there: a 6 MB
chapter cost a measured 110–160 ms and a fresh 6 MB on disk *per save*, roughly 3 GB of flash
writes across a 45-minute sleep timer. Writing the position on its own costs ~6 ms. Tracks
imported before the split keep their old `position` field and are read back through it, so
nothing needed migrating.

Import reads each picked file **once**. A file from the Android picker is a handle to a
`content://` provider, not bytes in memory, and the old path pulled it through twice — once for
the media element to measure the duration, then again for IndexedDB. Reading it into memory
first and reusing that copy takes the duration probe from ~730 ms to ~30 ms on a 6 MB MP3.
Files above 96 MB skip this and stream from the handle, to stay off the heap.

The numbers above came from `scripts/bench.html` — drop it over
`apps/phone/android/app/src/main/assets/public/index.html`, build, and read the results with
`adb logcat | grep BENCH`.

## Local development

```bash
python3 -m http.server 4180 --directory ~/Projects/audio-timer/apps/phone
```

Then open http://127.0.0.1:4180 — localhost counts as a secure origin, so the service worker
and install prompt both work there.

Regenerate icons:

```bash
python3 apps/phone/scripts/make-icons.py
```

## Notes

- The page is served network-first with a 2.5 s timeout, so an update lands on the next open
  when online and the cache answers instantly when offline. Icons and the manifest stay
  cache-first. Bump `CACHE` in `sw.js` on release to drop the old entries.
- `load()` races `loadedmetadata` against `error` and a 10 s timeout. Waiting on
  `loadedmetadata` alone means one corrupt file hangs the queue forever.
- The `ended` handler clears `current` before loading the next track, because `load()` saves
  the outgoing position and would otherwise write the end of the file over the reset.
- Long imports must never await `requestAnimationFrame` — it stops firing when the screen
  sleeps, which would stall the import silently.
- The IndexedDB open sets `onversionchange` (and handles `onblocked`). Without it, a second
  copy of the app open elsewhere holds the old version and a schema upgrade hangs forever
  with no error — the app just never finishes booting.
- `sessionStart()` closes every session row that has no `endedAt`, not just the newest.
  Android can kill the app before `pagehide` writes, and older orphans would dangle.
- Fade-in and the sleep timer's fade-out both restore volume to `TARGET_VOL`, never to
  "whatever it was". Fading out from a volume the fade-in was still raising, then restoring
  that captured value, leaves playback permanently quiet.
- `startFadeIn()` runs only when a *new* session begins, so chapters do not each fade in
  during auto-advance.
- The sync token lives only in IndexedDB and is never written back into the DOM — reopening
  the ⚙ sheet leaves the field blank, and blank on save means "keep the stored one".
- `putSession()` stamps `updatedAt` on every ordinary write; `putSessionRaw()` deliberately
  does not. The upload layer stamps what it delivered via the raw put, so marking a run as
  uploaded cannot itself make the run look modified again. In-flight runs (no `endedAt`)
  are never staged.
- The service worker ignores cross-origin requests entirely, so the upload calls to the
  receiver are never cached or served from cache.
- The server upload cannot be a page-level `online` listener. The SIM comes out at night —
  while the app is open and recording — and goes back in during the day with the app closed,
  so the page is never running at the moment connectivity returns. The page only stages
  nights into a native outbox; `UploadWorker` sends them under a WorkManager network
  constraint, app closed, surviving reboots.
- A night is deleted locally on the strength of the server's answer, never of having sent it.
  The receiver replies with the ids it wrote and only those are stamped; the row then has to
  outlive `UPLOAD_KEEP_DAYS` before the sweep touches it. A 2xx that accepts nothing leaves
  everything queued, on purpose.
- `stampUploaded()` stamps the revision that was *staged*, and skips a row that changed while
  it sat in the outbox. Stamping the current revision instead would mark a stale copy as
  delivered, and the sweep would eventually delete the run the server never received.
- `LogUploadPlugin.configure()` only touches the work queue when the config actually changed.
  The page pushes it on every boot so the two copies cannot drift, and without that test each
  app open would reset the backoff of a job patiently waiting out a server outage.
- The receiver answers CORS preflight. Native uploads never see it, but the app also runs as
  an ordinary web page, and there the upload is a cross-origin fetch the browser blocks
  outright without it.
  count — background tabs throttle timers, but media playback keeps firing `timeupdate`, so
  the stop lands on time with the screen off.
- Positions are saved every 5 s while playing, and forced on pause, on timer stop, when the
  app is backgrounded, and on close.
- `savePos()` refuses to write a position of 0. Swapping the `<audio>` source fires `pause`
  with `currentTime` back at 0, which otherwise erases the resume point at the exact moment
  it is being loaded.
