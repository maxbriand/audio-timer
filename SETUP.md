# Setup

(The private GitHub sync this file used to open with was removed on 2026-08-27 —
the Mac's receiver, below, has been the one destination since the VPS stopped
storing, and nothing read the repo any more. If you still have the old private
repo and its fine-grained token, archive the repo and revoke the token.)

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

## Why the sending is native, not the page's

A page can only upload while it is open and online. **That does not work if you take the SIM
out at bedtime**: the app is open and recording exactly while there is no network, and the
network comes back during the day with the app closed. Nothing in the page is running at
that moment. (This is also why the old GitHub sync, which pushed from the page, was retired.)

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

Since 2026-08-19 there is nothing to pull: the receiver itself runs **on the Mac**
(`mac/com.maxbriand.audio-receiver.plist`), and the public URL reaches it through a reverse
SSH tunnel the Mac holds open into the VPS (`mac/com.maxbriand.audio-tunnel.plist`) — the
Mac's home connection is CGNAT'd, so the Mac dials out and the VPS only relays. Day files
land in the Body asset the moment the phone uploads; the Mac is the durable record directly,
which matters once the phone starts clearing uploaded nights after 14 days. The Mac being
off just means the phone's WorkManager retries later — same contract as ever.

The daily 16:00 job below remains, but its job is now only the derived views: it rebuilds
`sessions.csv` next to the day files, and the sleep diary into
`~/Documents/Assets/Body/sources/sleep/` — the diary is filed with the other sleep records,
not with the raw log. Asleep at 16:00, launchd runs the missed job on wake; powered off, it
catches up at the next login (the script knows whether a 16:00 run is still owed). Run it by
hand any time with `--force`.

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
