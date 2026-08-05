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
~/Documents/Sources/audio-timer/mac/body-data-sync.sh && tail -3 ~/Library/Logs/body-data-sync.log
```

You should get a line like `no change · 3 day files · 7 sessions`.

Then install the LaunchAgent. `launchd` does not expand `~`, so the home path is substituted
in as the template is copied:

```bash
sed "s|__HOME__|$HOME|g" ~/Documents/Sources/audio-timer/mac/com.maxbriand.body-data-sync.plist > ~/Library/LaunchAgents/com.maxbriand.body-data-sync.plist
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
