# Features

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
- **Server upload** (under ⚙): each finished night is sent to a server of your own the
  next time the phone has internet — **with the app closed**, which is what makes it work on
  a phone whose SIM comes out at night — and is then cleared from the phone once the server
  has confirmed it and two weeks have passed. Also in [setup.md](setup.md).
- **Fatigue tracking** (⚙ → Day, APK only): any number of checks a day, each a delay
  **after the last wake-up** or **before the next sleep time** (next alarm − sleep
  duration). A check is a real alarm clock — it rings over the lock screen, survives a
  reboot — and runs up to three steps, always in this order, each with its own Skip:
  the **strap test** (the H10's heart rate from its first beat, five calm minutes, the last
  three recorded: average HR, and RMSSD over every beat-to-beat interval), the **PVT**
  (done on the computer; the phone only asks when it is over), and the **fatigue question**
  (1–10, 10 = maximum). One check is one entry in the log's ☀️ Day part, and one row on
  the server: `POST /fatigue`, filed in `fatigue-checks/` beside the night log, never in
  it. Each row carries `anchor` + `offsetMin` and the column suffix made from them
  (`+5min`, `-1h sleep`), so a results column is "what + when" — `HRV +5min`,
  `Fatigue -1h sleep`. The question's answer also still rides the night pipeline as its
  own row and becomes the diary's Fatigue column (the first score after the night). Every
  check has a **Test** button that runs it now and logs it, marked as a test — a test's
  answer is not uploaded, so it can never become a morning's score. The list starts with
  the one check the app always had — the question, 45 minutes after the rise. Going back to
  night mode withdraws the morning checks still counting down.
- **Night tracking** (APK only): switching to night mode asks "track this night?". Yes
  connects the H10 and the strap's service — not the page, which Android freezes long before
  morning — records one line a minute until the wake-up is logged: average **HR**, **HRV**
  (RMSSD over the minute's beat-to-beat intervals) and **sleeping position** (back, stomach,
  left, right, upright… from the strap's accelerometer at 25 Hz, the Live page's own rule),
  with the seconds spent in each and the seconds spent moving. Every line goes to disk as it
  is made, and a service the system kills picks the same night up again; a minute the strap
  said nothing in is written as such, never filled in. The night shows in the log's 🌙 Night
  part (totals, then hour by hour) and goes to the server on its own route — `POST /night`,
  filed in `night-tracking/`.
- **Day-mode logging** in two categories — **Habits** (things done: walk, stretching) and
  **Issues** (things suffered: headache, back pain). Create a type once, log an occurrence
  with one tap plus an optional note and photos; the recorded moment is the tap, not the
  save. A type's ✕ deletes the button only — entries already logged are kept. All of it
  stays on the phone except the morning walk (below).
- **Morning walk = the daylight log** (⚙ toggle): pins a ☀️ Morning walk event first on
  the day screen's habit list. Logging it records the tap moment like any event, and also
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
