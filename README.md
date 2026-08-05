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

## Files

| File | Role |
|---|---|
| `index.html` | The whole app — UI, IndexedDB storage, player, sleep timer |
| `sw.js` | Service worker, precaches the shell so it opens offline |
| `manifest.webmanifest` | Makes it installable as a standalone app |
| `icon-*.png` | Launcher icons (generated, see below) |

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
- The countdown is a wall-clock deadline checked on every `timeupdate`, not a `setInterval`
  count — background tabs throttle timers, but media playback keeps firing `timeupdate`, so
  the stop lands on time with the screen off.
- Positions are saved every 5 s while playing, and forced on pause, on timer stop, when the
  app is backgrounded, and on close.
- `savePos()` refuses to write a position of 0. Swapping the `<audio>` source fires `pause`
  with `currentTime` back at 0, which otherwise erases the resume point at the exact moment
  it is being loaded.
