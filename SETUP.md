# Private sync setup

The phone pushes its Nights log to **your own private GitHub repo**; the Mac pulls that repo
every 30 minutes and files the sessions into the Body asset. Nothing about your nights ever
touches this repo — `audio-timer` is public and only ever holds the app itself.

```
phone (IndexedDB)  ──PUT──▶  github.com/<you>/body-data (PRIVATE)
                                        │
                                    git pull, every 30 min
                                        ▼
                             ~/Documents/Sources/body-data/
                                        │  copy
                                        ▼
                    ~/Documents/Assets/Body/sources/audio-sessions/
```

Four steps, about ten minutes, done once.

---

## 1. Create the private repo

<https://github.com/new>

- **Name**: `body-data`
- **Visibility**: **Private** — this is the whole point; check it twice.
- Tick **Add a README file** so the repo has a `main` branch to push onto. An empty repo has
  no branch and the first push from the phone will fail with *repo not found*.

## 2. Generate a fine-grained token

<https://github.com/settings/personal-access-tokens/new>

| Field | Value |
|---|---|
| Token name | `audio-timer phone` |
| Expiration | 1 year (put a reminder in your calendar — the sync stops dead when it expires) |
| Resource owner | your own account |
| Repository access | **Only select repositories** → `body-data` |
| Permissions → Repository → **Contents** | **Read and write** |

Leave every other permission alone. This token can touch exactly one private repo and
nothing else in your account — that is why it is worth the extra clicks over a classic token.

Copy the token (`github_pat_…`) when it is shown. GitHub never displays it again.

## 3. Enter it in the app

On the phone, open Audio Timer → **⚙** → scroll to **Private sync**.

1. **Repo**: `yourname/body-data` (pasting the full `https://github.com/…` URL also works —
   it gets normalised).
2. **Token**: paste it.
3. **Save**. It pushes immediately and the line underneath turns into
   `yourname/body-data · 12 synced`.

That is the last time you touch it. From then on every finished run is pushed automatically —
when the run ends, and again on app open for anything the phone was killed before finishing.
No prompts, no buttons.

The token lives only in this phone's IndexedDB. It is never written back into the page, so
reopening the sheet does not expose it; the field stays blank and blank means *keep the
stored one*. **Forget** wipes both.

**If something is wrong** the status line says so plainly and keeps retrying:

| Line | Meaning |
|---|---|
| `token rejected` | Typo in the token, or it expired |
| `token lacks Contents write` | Permission set to read-only in step 2 |
| `repo not found` | Name typo, repo not created, or the token is scoped to a different repo |
| `Offline — it will retry on its own.` | Nothing to do; it goes out on the next open |

Nothing is marked as synced unless it actually landed, so a failed night is simply retried.

## 4. Set up the Mac

Clone the private repo. **SSH is the right choice here** — an HTTPS clone would need the
token again, and would break the day it expires:

```bash
git clone git@github.com:yourname/body-data.git ~/Documents/Sources/body-data
```

If you have no SSH key on GitHub, use HTTPS and let the keychain hold the credentials
(`git config --global credential.helper osxkeychain`), then clone with the `https://` URL and
paste the token as the password at the first prompt.

Check the script runs by hand before automating it:

```bash
~/Projects/audio-timer/mac/body-data-sync.sh && tail -3 ~/Library/Logs/body-data-sync.log
```

You should get a line like `no change · 3 day files · 7 sessions`.

Then install the LaunchAgent. `launchd` does not expand `~`, so the home path is substituted
in as the template is copied:

```bash
sed "s|__HOME__|$HOME|g" ~/Projects/audio-timer/mac/com.maxbriand.body-data-sync.plist > ~/Library/LaunchAgents/com.maxbriand.body-data-sync.plist
```

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.maxbriand.body-data-sync.plist
```

It runs once immediately, then every 30 minutes.

```bash
launchctl print gui/$(id -u)/com.maxbriand.body-data-sync | head -20
```

### What lands on the Mac

`~/Documents/Assets/Body/sources/audio-sessions/`

- `YYYY-MM-DD.json` — one file per night, exactly as the phone pushed it
- `sessions.csv` — all nights in one flat table, rebuilt on every run

The JSON files are the record; the CSV is derived and can be deleted at any time.

### Managing it

```bash
tail -20 ~/Library/Logs/body-data-sync.log
```

```bash
launchctl kickstart -k gui/$(id -u)/com.maxbriand.body-data-sync
```

```bash
launchctl bootout gui/$(id -u)/com.maxbriand.body-data-sync
```

(run it now · force a run · turn it off)

---

## Notes

- **The phone is always on mobile data.** A day file is a few kB, so a night costs
  essentially nothing, and a day whose content did not change costs no request at all — the
  app compares against what is already in the repo before writing.
- **The unit of sync is the day file, not the run.** Each push reads that day's file, merges
  the runs it already holds by id, and writes it back. A night reopened later updates in
  place instead of duplicating, and a second device editing the same day is merged rather
  than clobbered.
- **Rotating the token**: generate a new one, paste it in the ⚙ sheet, **Save**. Nothing else
  changes — the Mac uses SSH and is unaffected.
- **If the phone is wiped**, the repo is the backup. The app's own **Back up positions** JSON
  (☾ sheet) still covers where you were in each file; that is separate and stays manual.

---

# Server upload setup

A second, independent destination: the phone sends each finished night to **a server of your
own**, and — unlike the GitHub sync — clears it from the phone once the server has confirmed
it. Set up alongside the sync above, or instead of it; they do not know about each other.

```
phone (IndexedDB)  ──stage──▶  native outbox  ──POST, when there is a network──▶  your server
                                                                                      │
                             ◀── {"accepted":[ids]} ────────────────────────────────────
                                        │
                          stamped, then deleted from the phone 14 days later
```

## Why this is not the GitHub sync with a different URL

The GitHub sync uploads from the page, when the page is open and online. That works if the
phone has data at night. **It does not work if you take the SIM out at bedtime**: the app is
open and recording exactly while there is no network, and the network comes back during the
day with the app closed. Nothing in the page is running at that moment.

So on the APK the page only *stages* a finished night into a native outbox, and Android's
WorkManager does the sending under a "needs a network" constraint. It fires when connectivity
returns with the app closed, retries with backoff, and is rescheduled after a reboot.
Uninstalling the app or force-stopping it from Settings is the only thing that stops it.

Run as an ordinary web page there is no such mechanism, so the page falls back to posting
while it is open and online. Useful for trying it out; not what the phone relies on.

## 1. Put the receiver on the server

[`tools/log-receiver.py`](tools/log-receiver.py) — stdlib only, no dependencies.

```bash
scp ~/Projects/audio-timer/tools/log-receiver.py you@your-server:/opt/audio-timer/
```

Generate a token and keep it somewhere you can paste from:

```bash
openssl rand -hex 32
```

Run it as a service (systemd shown; anything that keeps a process up will do):

```ini
[Unit]
Description=audio-timer log receiver
After=network.target

[Service]
Environment=AUDIO_TIMER_TOKEN=<the token you just generated>
Environment=AUDIO_TIMER_DIR=/var/lib/audio-timer
ExecStart=/usr/bin/python3 /opt/audio-timer/log-receiver.py
Restart=always
DynamicUser=yes
StateDirectory=audio-timer

[Install]
WantedBy=multi-user.target
```

It listens on `127.0.0.1:8787` and refuses to start without a token.

## 2. Give it TLS

The token travels in an `Authorization` header, so the app refuses any URL that is not
`https://` (localhost excepted, for trying it out). Put it behind whatever already terminates
TLS on that box:

```nginx
location /audio-timer {
    proxy_pass http://127.0.0.1:8787/;
    client_max_body_size 8m;
}
```

Check it from your Mac before touching the phone — a wrong token must be a 401:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Authorization: Bearer wrong' -d '{"sessions":[]}' https://your-server/audio-timer
```

## 3. Enter it in the app

Phone → **⚙** → **Server upload**.

1. **URL**: `https://your-server/audio-timer`
2. **Token**: the one from step 1.
3. **Save**.

The line underneath becomes `your-server · 12 sent · cleared after 14 days`. Like the sync
token, it is never written back into the page — the field stays blank and blank means *keep
the stored one*.

| Line | Meaning |
|---|---|
| `12 sent · 3 waiting` | Three nights are staged; they go out at the next network |
| `server rejected the token` | Typo, or a different token on the server |
| `server refused the log (HTTP 4xx)` | The URL points at something that is not the receiver |
| `could not reach the server — will retry` | Normal at night, and at any bad-signal moment |

## What gets deleted, and when

Deleting is deliberately several steps behind sending:

1. the server answers `2xx` with the ids it wrote, and only those are marked as sent;
2. a marked night must also be **older than 14 days**;
3. and it must have reached **every destination that is configured** — so if the GitHub sync
   above is set up and stuck, nothing is cleared at all.

Until then the ☾ sheet and **Export CSV** show it as usual. After that the server is the only
copy of that night, which is the point: the phone stops accumulating. Change the window by
editing `UPLOAD_KEEP_DAYS` in `index.html`.

**Forget** stops the sending and stops the clearing; nights already on the server stay there.

## What lands on the server

`/var/lib/audio-timer/YYYY-MM-DD.json` — the same shape the GitHub sync writes, one file per
night, sessions sorted by start time, upserted by id. The day a night is filed under is the
one the *phone* says it is, not the server's own date, so a run starting at 00:30 lands where
you would look for it even if the server sits in another timezone.

`tools/sessions-json-to-csv.py` flattens these into one CSV exactly as it does for the pulled
GitHub files.

## Getting the server files into the Body asset

The Mac pulls the day files off the VPS every 30 minutes — the server-upload counterpart of
step 4 of the GitHub route, and the piece that makes the Mac (not the VPS) the durable record
once the phone starts clearing uploaded nights after 14 days.

```bash
sed "s|__HOME__|$HOME|g" ~/Projects/audio-timer/mac/com.maxbriand.audio-server-sync.plist > ~/Library/LaunchAgents/com.maxbriand.audio-server-sync.plist
```

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.maxbriand.audio-server-sync.plist
```

**One manual step, once: Full Disk Access.** The destination is under `~/Documents`, which
macOS denies to background agents — silently, no prompt. System Settings → Privacy &
Security → Full Disk Access → **+** → ⌘⇧G → `/bin/zsh` → toggle on. Until then every run
logs `Operation not permitted` and retries harmlessly.

What lands, same as the GitHub route would have: `~/Documents/Assets/Body/sources/audio-sessions/`
— one `YYYY-MM-DD.json` per night plus a derived `sessions.csv`, rebuilt on every run.

```bash
tail -5 ~/Library/Logs/audio-server-sync.log
```

```bash
launchctl kickstart gui/$(id -u)/com.maxbriand.audio-server-sync
```

(check on it · force a run)
