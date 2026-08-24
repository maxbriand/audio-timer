# Audio Timer

Offline audio player for the phone (Redmi Note 10S / Chrome) with a custom sleep timer
that saves the exact moment it stops.

- Pick audio files from the phone — they're copied **into** the app (IndexedDB), so playback
  never touches the network.
- Set a stop time: presets (5/10/15/20/30/45/60/90 min) or any custom number of minutes.
- At zero the audio fades out over 8 s, pauses, and the position is written to disk.
- Every track remembers where it stopped. Tapping it resumes from there.
- Chapters auto-advance: when one ends the next starts, and the sleep timer keeps running
  across the handover. Files that will not open are skipped rather than ending the queue.
- Sorted in natural order (`Genesis 2` before `Genesis 10`), with a search box and a
  **Continue** card for the last thing you played once the library passes 12 files.
- Lock-screen / notification controls via the Media Session API (play, pause, ±30 s, scrub).
- Works with no internet at all after the first load — the service worker caches the app shell.
- Records a **session log** (the ☾ button): every run is kept, newest first — when audio
  started and ended, how long it actually played, what stopped it, the timer and playback
  settings in force, the chapters it ran from and to, and how long the phone went untouched
  before it stopped. The full history is shown, not just recent nights. Exports to CSV.
- **Back up / restore positions** as JSON, keyed by filename so a backup still applies after
  the audio is re-imported with new ids.
- **Shake the phone** to act without finding a button in the dark. Paused — including
  stopped by the sleep timer — a shake resumes, backing up by the rewind setting first;
  this is always on, and in the APK it works with the screen off (a foreground service
  watches the accelerometer, since the WebView is suspended by then). Playing, a shake
  skips to the next chapter instead, and that half is a ⚙ toggle, off by default: a phone
  that changed chapter every time you rolled over would be worse than no feature at all.
  Three distinct strong movements inside 1.2 s count as a shake — a pocket or a picked-up
  phone does not — and one shake is one action, so a long rattle cannot walk three chapters
  down the library. A skipped chapter keeps its position, unlike one that ran to its end.
  After ~30 min dark Android freezes the page, so a detected shake could sit undelivered
  until morning; if the page stays silent for 4 s after a shake, the service raises the app
  over the lock screen (the alarm mechanism), which thaws it and lets the shake land. The
  ⚙ **Night shake watch** row shows whether the watch can survive the phone going idle —
  battery exemption and low-power sensor — and re-opens the system dialog when it cannot.
- Playback settings (the ⚙ button, saved on the device): rewind on resume, fade-in at the
  start of a session, speed, auto-arming the last sleep timer when you press play, and
  shake-to-skip.
- **Private sync** (also under ⚙): finished runs are pushed automatically to a *private*
  GitHub repo of your own, one JSON file per night, and a LaunchAgent on the Mac pulls them
  into the Body asset every 30 minutes. Set up once — see [SETUP.md](SETUP.md).
- **Server upload** (also under ⚙): each finished night is sent to a server of your own the
  next time the phone has internet — **with the app closed**, which is what makes it work on
  a phone whose SIM comes out at night — and is then cleared from the phone once the server
  has confirmed it and two weeks have passed. Also in [SETUP.md](SETUP.md).
- **Fatigue alarm** (APK only): logging the wake-up arms a real alarm clock for 45 minutes
  later — it rings over the lock screen like the classic alarm, survives a reboot, and asks
  for a 1–10 fatigue score (10 = maximum). The answer rides the upload pipeline like any
  other row and becomes the diary's Fatigue column; dismissing it leaves the cell blank.
  Going back to night mode before it rings withdraws it.
- **Morning walk = the daylight log** (⚙ toggle): pins a ☀️ Morning walk event first on
  the day screen's event list. Logging it records the tap moment like any event, and also
  sends a marker row through the upload pipeline that becomes the diary's Morning light
  column — the walk is how daylight exposure starts, so one tap records both facts. First
  tap after sleep onset counts; a day without one leaves the cell blank. The note rides
  along; pictures, like all event photos, stay on the phone.
- **Melatonin reminder** (APK only): set your bedtime in ⚙ and every day, 5 hours before
  it — the chronobiotic timing for the 0.5 mg dose — an alarm rings that only "Taken ✓"
  can close: it snoozes in 10-minute steps, its notification cannot be swiped away, and
  each dose taken is logged through the upload pipeline like everything else.

## What the session log is and is not

It is not a sleep tracker and it measures nothing about your body. It records what the app
itself observed. The useful column is `minutes_untouched_before_stop`: if a 45-minute timer
ran out and the phone had not been touched for 38 of those minutes, you were almost certainly
asleep well before it stopped. Treat that as a rough sleep-onset proxy, not a measurement.

## Files

| File | Role |
|---|---|
| `index.html` | The whole app — UI, IndexedDB storage, player, sleep timer, private sync |
| `sw.js` | Service worker, precaches the shell so it opens offline |
| `manifest.webmanifest` | Makes it installable as a standalone app |
| `icon-*.png` | Launcher icons (generated, see below) |
| `SETUP.md` | One-time setup for the private sync, phone and Mac |
| `mac/body-data-sync.sh` | Pulls the private repo into the Body asset (run by launchd) |
| `mac/com.maxbriand.body-data-sync.plist` | LaunchAgent template, every 30 min |
| `tools/sessions-json-to-csv.py` | Flattens the pushed day files into one CSV |
| `tools/log-receiver.py` | The server end of the upload — files each night by day, stdlib only |
| `android/…/LogUploadPlugin.java` | The page's handle on the outbox: stage a night, ask what landed |
| `android/…/Outbox.java` | The staged nights on disk, and where to send them |
| `android/…/UploadWorker.java` | Sends them when the phone next has a network, app closed |
| `capacitor.config.json` | Native shell config — app id, name, background colour |
| `android/` | Capacitor's generated Android project (committed; build output is not) |
| `scripts/build-www.mjs` | Copies the root web assets into `www/` for the APK |
| `scripts/build-apk.mjs` | Gradle release build → `~/Downloads/audio-timer-standalone.apk` |
| `scripts/make-android-icons.py` | Adaptive launcher icons from the same mark as the PWA |
| `scripts/bench.html` | Throwaway harness used to measure the storage writes (see below) |

The web app itself still has no build step and no dependencies — `index.html` is the whole
thing, and the repo root is what GitHub Pages serves. The Capacitor tooling only exists to
wrap that same file into an APK, and never edits it.

## Installing on the phone

**https://maxbriand.github.io/audio-timer/** — served by GitHub Pages from `main`.

Chrome needs a **secure origin** (https, or localhost) to register a service worker, so the
app has to be loaded over https **once**. After that it runs entirely offline; the URL is
never contacted again.

1. On the phone, open that URL in Chrome.
2. Chrome menu (⋮) → **Install app** / *Add to Home screen*.
3. Open it from the launcher, tap **+ Add audio**, pick files. Done — you can go offline.

Pushing to `main` republishes the site.

Nothing is ever uploaded: only the app shell (HTML/JS/icons) is hosted; the audio and the
saved positions live on the phone.

## Installing as an APK

The same app, wrapped by [Capacitor](https://capacitorjs.com) into an ordinary Android app —
its own launcher icon and no browser chrome, and nothing to install it from.

```bash
npm install && npm run apk
```

That rebuilds `www/`, syncs it into `android/`, runs a signed Gradle release build and leaves
`~/Downloads/audio-timer-standalone.apk`. Copy it to the phone and open it.

**What this does and does not change.** Capacitor renders in Android's WebView, which since
Android 10 is its own package (*Android System WebView*) and not part of Chrome — so Chrome can
be absent or disabled and the app still runs. What it does *not* do is ship a browser engine
inside the APK; the 3.6 MB APK is the web app plus the Capacitor bridge, and it uses whatever
WebView the phone has. Genuinely bundling an engine means GeckoView (~70 MB), which Capacitor
does not support.

**Keep the keystore.** `android/keystore.properties` and `android/audio-timer-release.keystore`
are gitignored and exist only on this Mac. Android refuses to install an upgrade signed by a
different key, so losing them means uninstalling the app — and its library — before the next
build will install.

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
`android/app/src/main/assets/public/index.html`, build, and read the results with
`adb logcat | grep BENCH`.

## Local development

```bash
python3 -m http.server 4180 --directory ~/Projects/audio-timer
```

Then open http://127.0.0.1:4180 — localhost counts as a secure origin, so the service worker
and install prompt both work there.

Regenerate icons:

```bash
python3 make-icons.py
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
  does not. The sync layer stamps `syncedAt` with the exact revision it pushed via the raw
  put, so marking a run as synced cannot itself make the run look modified again.
- A run is only stamped after its day file actually lands, so a failed push simply retries.
  In-flight runs (no `endedAt`) are never pushed.
- The service worker ignores cross-origin requests entirely, so the `api.github.com` calls
  are never cached or served from cache.
- Base64 for the GitHub API goes through `TextEncoder`, not `btoa` directly — `btoa` only
  speaks latin-1 and would corrupt every accented chapter name.
- The server upload cannot be a page-level `online` listener, which is what the GitHub sync
  is. The SIM comes out at night — while the app is open and recording — and goes back in
  during the day with the app closed, so the page is never running at the moment connectivity
  returns. The page only stages nights into a native outbox; `UploadWorker` sends them under
  a WorkManager network constraint, app closed, surviving reboots.
- A night is deleted locally on the strength of the server's answer, never of having sent it.
  The receiver replies with the ids it wrote and only those are stamped; the row then has to
  outlive `UPLOAD_KEEP_DAYS` **and** have reached every other configured destination before
  the sweep touches it. A 2xx that accepts nothing leaves everything queued, on purpose.
- `stampUploaded()` stamps the revision that was *staged*, and skips a row that changed while
  it sat in the outbox. Stamping the current revision instead would mark a stale copy as
  delivered, and the sweep would eventually delete the run the server never received.
- `LogUploadPlugin.configure()` only touches the work queue when the config actually changed.
  The page pushes it on every boot so the two copies cannot drift, and without that test each
  app open would reset the backoff of a job patiently waiting out a server outage.
- The receiver answers CORS preflight. Native uploads never see it, but the app also runs as
  an ordinary web page, and there the upload is a cross-origin fetch the browser blocks
  outright without it.

## Getting the sessions into the Body asset

**Automatically** — the private sync. The phone pushes each finished night to a private repo
and a LaunchAgent pulls it into `~/Documents/Assets/Body/sources/audio-sessions/` every 30
minutes. Nothing to run. [SETUP.md](SETUP.md) covers the one-time setup.

**By hand**, still there for a phone that is not set up:

```bash
python3 tools/sleep-log-to-body.py            # preview what would be added
python3 tools/sleep-log-to-body.py --write    # append
```

Reads `~/Downloads/audio-timer-sessions.csv` and appends new rows to
`~/Documents/Assets/Body/sources/sleep/audio-sessions.csv`, de-duplicating on the `started`
timestamp. It writes nothing without `--write`. The two routes write to different files and
do not interfere.
- The countdown is a wall-clock deadline checked on every `timeupdate`, not a `setInterval`
  count — background tabs throttle timers, but media playback keeps firing `timeupdate`, so
  the stop lands on time with the screen off.
- Positions are saved every 5 s while playing, and forced on pause, on timer stop, when the
  app is backgrounded, and on close.
- `savePos()` refuses to write a position of 0. Swapping the `<audio>` source fires `pause`
  with `currentTime` back at 0, which otherwise erases the resume point at the exact moment
  it is being loaded.
