#!/bin/zsh
# Pull the Nights-log day files from the VPS receiver into the Body asset.
#
# Run every 30 minutes by ~/Library/LaunchAgents/com.maxbriand.audio-server-sync.plist.
# Safe to run by hand at any time; it is idempotent and takes a lock, so two copies can never
# fight over the destination.
#
#   VPS          contabo:~/audio-timer/data/YYYY-MM-DD.json   (POSTed by the phone, see DEPLOY.md there)
#   Body asset   ~/Documents/Assets/Body/sources/audio-sessions/   (what this writes)
#
# This replaces the never-built GitHub route (body-data-sync.sh): same destination, same CSV
# roll-up, but the source is the server upload rather than a private repo. The phone deletes
# uploaded nights after 14 days, so this copy — not the phone — is the durable record, and the
# VPS stops being the only one.
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

REMOTE="${AUDIO_SYNC_REMOTE:-contabo:audio-timer/data/}"
DEST_DIR="${AUDIO_SYNC_DEST:-$HOME/Documents/Assets/Body/sources/audio-sessions}"
LOG_FILE="${AUDIO_SYNC_LOG:-$HOME/Library/Logs/audio-server-sync.log}"
LOCK_DIR="${TMPDIR:-/tmp}/audio-server-sync.lock"
# The CSV roll-up lives next to this script, in the audio-timer checkout.
TO_CSV="${0:A:h}/../tools/sessions-json-to-csv.py"

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

# BatchMode: under launchd there is no one to answer a prompt, so fail instead of hanging.
# -i (itemize) is the change detector: no output means nothing new.
# No --delete — a day file withdrawn on the server must not silently vanish from the record
# here; this directory is the archive, the server is just the relay.
if ! changes="$(rsync -ai --include='*.json' --exclude='*' \
      -e 'ssh -o BatchMode=yes -o ConnectTimeout=15' \
      "$REMOTE" "$DEST_DIR/" 2>&1)"; then
  # The Mac being offline, or the VPS rebooting, is the normal case — log it and let the
  # next run in 30 minutes handle it.
  log "pull failed (will retry): $(print -r -- "$changes" | tail -1)"
  # The last line is often just rsync's generic "code 12"; the line that names the real
  # cause (e.g. macOS denying ~/Documents to a background agent) is above it. Keep it all.
  print -r -- "$changes" > "${TMPDIR:-/tmp}/audio-server-sync.debug"
  exit 0
fi
changed=$(print -r -- "$changes" | grep -c '^>' || true)

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

if (( changed == 0 )); then
  log "no change · $days day files · $rows sessions"
else
  log "pulled $changed file(s) · $days day files · $rows sessions"
fi

# Stamped only after a pull that worked — a failed one leaves the run owed, so the next
# trigger (a login, or tomorrow's 16:00) retries instead of skipping.
touch "$STAMP"

# Keep the log from growing without bound.
if [[ -f "$LOG_FILE" ]] && (( $(wc -l < "$LOG_FILE") > 2000 )); then
  tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi
