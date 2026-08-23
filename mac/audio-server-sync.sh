#!/bin/zsh
# Rebuild the derived views (CSV roll-up + sleep diary) over the Nights-log day files.
#
# Since 2026-08-19 the day files land here directly: log-receiver.py runs on this Mac
# (com.maxbriand.audio-receiver), published as audio.maximebriand.com through a reverse SSH
# tunnel held open by com.maxbriand.audio-tunnel — the VPS relays, it no longer stores.
# So there is nothing to pull any more; this script's job is only to keep the CSV and the
# sleep diary from drifting away from the day files.
#
#   Day files    ~/Documents/Assets/Body/sources/audio-sessions/YYYY-MM-DD.json
#                (written live by log-receiver.py as the phone uploads)
#   Derived      sessions.csv next to them, and the sleep diary (tools/sleep-diary.py)
#                one folder over, in ~/Documents/Assets/Body/sources/sleep/ — the diary is
#                a sleep record, filed with the PSGs and the ordonnances, not with the log
#
# Run by ~/Library/LaunchAgents/com.maxbriand.audio-server-sync.plist. Safe to run by hand
# at any time; it is idempotent and takes a lock, so two copies can never fight.
#
# Override any path with an environment variable of the same name if the layout ever moves.
#
# NEEDS FULL DISK ACCESS: the destination is under ~/Documents, which macOS (TCC) denies to
# launchd background agents — silently, with no prompt. Grant it once: System Settings →
# Privacy & Security → Full Disk Access → add /bin/zsh. Without it every run logs
# "Operation not permitted" (full error in $TMPDIR/audio-server-sync.debug).

set -u

# launchd gives a process almost no PATH, so name the tools' real locations.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

DEST_DIR="${AUDIO_SYNC_DEST:-$HOME/Documents/Assets/Body/sources/audio-sessions}"
DIARY_DIR="${AUDIO_SYNC_DIARY_DEST:-$HOME/Documents/Assets/Body/sources/sleep}"
LOG_FILE="${AUDIO_SYNC_LOG:-$HOME/Library/Logs/audio-server-sync.log}"
LOCK_DIR="${TMPDIR:-/tmp}/audio-server-sync.lock"
# The CSV roll-up lives next to this script, in the audio-timer checkout.
TO_CSV="${0:A:h}/../tools/sessions-json-to-csv.py"

REPO_DIR="${AUDIO_SYNC_REPO:-$HOME/Projects/audio-timer}"
STAMP="${AUDIO_SYNC_STAMP:-$HOME/Library/Logs/audio-server-sync.last-ok}"

mkdir -p "$(dirname "$LOG_FILE")"
log(){ print -r -- "$(date '+%Y-%m-%d %H:%M:%S')  $*" >> "$LOG_FILE" }

# Once a day, at or after 16:00 — never before. launchd fires this at 16:00 (or on wake if
# the Mac slept through it) and again at every login (RunAtLoad, for the powered-off case);
# this guard is what turns "runs at every trigger" into "runs once per 16:00 deadline".
# The most recent deadline is today's 16:00 if that has passed, otherwise yesterday's; if the
# last successful pull is newer than that, nothing is owed. --force (or a failed last pull)
# skips the guard, so testing by hand never has to wait for the clock.
if [[ "${1:-}" != "--force" ]]; then
  due=$(date -v16H -v0M -v0S +%s)
  (( $(date +%s) < due )) && due=$(( due - 86400 ))
  if [[ -f "$STAMP" ]] && (( $(stat -f %m "$STAMP") >= due )); then
    log "not due: already pulled since $(date -r $due '+%a %H:%M')"
    exit 0
  fi
fi

# A previous run that is still going (a slow transfer on a bad connection) wins; this one leaves.
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  log "skip: another run holds the lock"
  exit 0
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT INT TERM

mkdir -p "$DEST_DIR"

days=$(ls -1 "$DEST_DIR"/*.json 2>/dev/null | wc -l | tr -d ' ')

# One flat CSV alongside the JSON, so the sessions can be read without parsing 200 files.
# Keep its stderr out of the count — it warns there about day files it had to skip, and
# folding that into stdout would put prose where the number goes.
rows='?'
if [[ -f "$TO_CSV" ]] && command -v python3 >/dev/null 2>&1; then
  csv_err="${TMPDIR:-/tmp}/audio-server-sync.csv-err"
  if csv_out="$(python3 "$TO_CSV" "$DEST_DIR" "$DEST_DIR/sessions.csv" 2>"$csv_err")"; then
    rows="$csv_out"
  else
    log "csv roll-up failed: $(tr '\n' ' ' < "$csv_err")"
  fi
  [[ -s "$csv_err" ]] && log "csv roll-up warnings: $(tr '\n' ' ' < "$csv_err")"
  rm -f "$csv_err"
fi

log "rebuilt · $days day files · $rows sessions"

# The sleep diary is derived, like the CSV: rebuilt whole on every run so it can never
# drift from the day files. Its rules live in tools/sleep-diary.py. Read from the day
# files, written into the sleep folder.
mkdir -p "$DIARY_DIR"
python3 "$REPO_DIR/tools/sleep-diary.py" "$DEST_DIR" "$DIARY_DIR" >/dev/null 2>&1 || log "sleep-diary generation failed (data is safe; diary is derived)"

# Stamped only after a run that worked — a failed one leaves the run owed, so the next
# trigger (a login, or tomorrow's 16:00) retries instead of skipping.
touch "$STAMP"

# Keep the log from growing without bound.
if [[ -f "$LOG_FILE" ]] && (( $(wc -l < "$LOG_FILE") > 2000 )); then
  tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi
