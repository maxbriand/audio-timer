#!/bin/zsh
# Pull the private body-data repo and fold the audio-timer session files into the Body asset.
#
# Run every 30 minutes by ~/Library/LaunchAgents/com.maxbriand.body-data-sync.plist.
# Safe to run by hand at any time; it is idempotent and takes a lock, so two copies can never
# fight over the working tree.
#
#   private repo   ~/Documents/Sources/body-data/audio-sessions/YYYY-MM-DD.json   (pushed by the PWA)
#   Body asset     ~/Documents/Assets/Body/sources/audio-sessions/                (what this writes)
#
# Override any path with an environment variable of the same name if the layout ever moves.

set -u

# launchd gives a process almost no PATH, so name the tools' real locations.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
# Never let git stop and wait for a password prompt no one is there to answer.
export GIT_TERMINAL_PROMPT=0
export GIT_ASKPASS=/usr/bin/true

REPO_DIR="${BODY_DATA_REPO:-$HOME/Documents/Sources/body-data}"
DEST_DIR="${BODY_DATA_DEST:-$HOME/Documents/Assets/Body/sources/audio-sessions}"
LOG_FILE="${BODY_DATA_LOG:-$HOME/Library/Logs/body-data-sync.log}"
LOCK_DIR="${TMPDIR:-/tmp}/body-data-sync.lock"
SRC_DIR="$REPO_DIR/audio-sessions"
# The CSV roll-up lives next to this script, in the audio-timer checkout.
TO_CSV="${0:A:h}/../tools/sessions-json-to-csv.py"

mkdir -p "$(dirname "$LOG_FILE")"
log(){ print -r -- "$(date '+%Y-%m-%d %H:%M:%S')  $*" >> "$LOG_FILE" }

# A previous run that is still going (a slow pull on a bad connection) wins; this one leaves.
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  log "skip: another run holds the lock"
  exit 0
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT INT TERM

if [[ ! -d "$REPO_DIR/.git" ]]; then
  log "stop: no git repo at $REPO_DIR — see SETUP.md"
  exit 1
fi

before="$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null)"

# --ff-only: this Mac only ever reads. If the two sides have genuinely diverged, stop and say
# so rather than opening a merge no one will see.
if ! pull_out="$(git -C "$REPO_DIR" pull --ff-only --quiet 2>&1)"; then
  # Being on mobile data, or the Mac being asleep at the wrong moment, is the normal case —
  # log it and let the next run in 30 minutes handle it.
  log "pull failed (will retry): ${pull_out:-unknown error}"
  exit 0
fi

after="$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null)"

if [[ ! -d "$SRC_DIR" ]]; then
  log "pull ok, but $SRC_DIR does not exist yet — nothing pushed from the phone so far"
  exit 0
fi

mkdir -p "$DEST_DIR"
# Copy rather than symlink: the Body asset stays readable even if the checkout is moved or
# removed. No --delete — a day file withdrawn upstream should not silently vanish from the
# record here.
if ! copy_out="$(rsync -a --include='*.json' --exclude='*' "$SRC_DIR/" "$DEST_DIR/" 2>&1)"; then
  log "copy failed: ${copy_out:-unknown error}"
  exit 1
fi

days=$(ls -1 "$DEST_DIR"/*.json 2>/dev/null | wc -l | tr -d ' ')

# One flat CSV alongside the JSON, so the sessions can be read without parsing 200 files.
# Keep its stderr out of the count — it warns there about day files it had to skip, and
# folding that into stdout would put prose where the number goes.
rows='?'
if [[ -f "$TO_CSV" ]] && command -v python3 >/dev/null 2>&1; then
  csv_err="${TMPDIR:-/tmp}/body-data-sync.csv-err"
  if csv_out="$(python3 "$TO_CSV" "$DEST_DIR" "$DEST_DIR/sessions.csv" 2>"$csv_err")"; then
    rows="$csv_out"
  else
    log "csv roll-up failed: $(tr '\n' ' ' < "$csv_err")"
  fi
  [[ -s "$csv_err" ]] && log "csv roll-up warnings: $(tr '\n' ' ' < "$csv_err")"
  rm -f "$csv_err"
fi

if [[ "$before" == "$after" ]]; then
  log "no change · $days day files · $rows sessions"
else
  log "updated ${before:0:7}..${after:0:7} · $days day files · $rows sessions"
fi

# Keep the log from growing without bound.
if [[ -f "$LOG_FILE" ]] && (( $(wc -l < "$LOG_FILE") > 2000 )); then
  tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi
