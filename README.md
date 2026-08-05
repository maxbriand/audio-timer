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
- Playback settings (the ⚙ button, saved on the device): rewind on resume, fade-in at the
  start of a session, speed, and auto-arming the last sleep timer when you press play.
- **Private sync** (also under ⚙): finished runs are pushed automatically to a *private*
  GitHub repo of your own, one JSON file per night, and a LaunchAgent on the Mac pulls them
  into the Body asset every 30 minutes. Set up once — see [SETUP.md](SETUP.md).

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

No build step, no dependencies.

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

## Local development

```bash
python3 -m http.server 4180 --directory ~/Documents/Sources/audio-timer
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
