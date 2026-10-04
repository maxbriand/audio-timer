# Getting the sessions into the Body asset

**Automatically** — the server upload. The phone posts each finished night to the receiver
running on the Mac (reached through the VPS relay), which writes it straight into
`~/Documents/Body/sources/audio-sessions/`. Nothing to run. [setup.md](setup.md)
covers the one-time setup.

**By hand**, still there for a phone that is not set up:

```bash
python3 scripts/sleep-log-to-body.py            # preview what would be added
python3 scripts/sleep-log-to-body.py --write    # append
```

Reads `~/Downloads/audio-timer-sessions.csv` and appends new rows to
`~/Documents/Body/sources/sleep/audio-sessions.csv`, de-duplicating on the `started`
timestamp. It writes nothing without `--write`. The two routes write to different files and
do not interfere.
- The countdown is a wall-clock deadline checked on every `timeupdate`, not a `setInterval`
