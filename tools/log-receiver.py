#!/usr/bin/env python3
"""Receives the phone's Nights log and files it by day.

The phone deletes a night from its own storage on the strength of what this answers, so the
two things that matter here are not the storing but the answering:

  * every write is an upsert keyed on the session id, so a retry after a dropped response
    (the phone's network dying mid-reply is the ordinary case) cannot duplicate a night;
  * the reply lists exactly the ids that reached disk, and the phone clears only those.

Fail loudly rather than accepting: a 5xx makes the phone keep the night and try again, which
is always the better outcome. Never answer 2xx for something not written.

The day files mirror the layout the GitHub sync already writes, so anything that reads those
reads these — one JSON file per local day, sessions sorted by start time.

Run it:

    AUDIO_TIMER_TOKEN=$(openssl rand -hex 32) \\
    AUDIO_TIMER_DIR=/var/lib/audio-timer \\
    python3 log-receiver.py

Listens on 127.0.0.1:8787 by default. Put it behind nginx/Caddy with TLS — the phone refuses
a plain-http URL for anything but localhost, because the token travels in a header.
"""

import hmac
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOKEN = os.environ.get("AUDIO_TIMER_TOKEN", "")
ROOT = Path(os.environ.get("AUDIO_TIMER_DIR", "./audio-sessions")).expanduser()
HOST = os.environ.get("AUDIO_TIMER_HOST", "127.0.0.1")
PORT = int(os.environ.get("AUDIO_TIMER_PORT", "8787"))
ORIGIN = os.environ.get("AUDIO_TIMER_ORIGIN", "*")
MAX_BODY = 4 * 1024 * 1024          # a night is ~400 bytes; this is 10k of them

# Only these are stored, whatever else the phone sends. Adding a field to the app must be a
# decision here too, not something that starts being kept by accident.
FIELDS = (
    "id", "started", "ended", "listenedMinutes", "timerMinutes", "timerCancelled",
    "timerAutoArmed", "speed", "fadeInSeconds", "stopReason", "trackStart", "trackEnd",
    "stopPositionSeconds", "minutesUntouchedBeforeStop", "note", "fatigueScore",
)

# POST /cardio files zone-alarm's sessions in their own folder with their own whitelist —
# the sleep extraction reads every session in an audio day file, so a cardio row landing
# there would be read as a play block and corrupt the night.
_cardio_env = os.environ.get("AUDIO_TIMER_CARDIO_DIR")
CARDIO_ROOT = Path(_cardio_env).expanduser() if _cardio_env else ROOT.parent / "cardio-sessions"
CARDIO_FIELDS = (
    "id", "started", "ended", "localDay", "minBpm", "maxBpm",
    "inRangeSeconds", "parts", "peakBpm",
)


def day_key(session: dict) -> str:
    """Which day file a night belongs in.

    The phone decides this, not the server. A run that starts at 23:40 belongs to that
    evening, and one that starts at 00:30 to the new date — which is a statement about the
    phone's timezone, not this machine's. `started` is UTC, so deriving the date here would
    file those two under the wrong day whenever the server sits in a different zone, and
    disagree with what the GitHub sync already wrote. Fall back to the server's own local
    date only for a row from a build that predates `localDay`.
    """
    day = session.get("localDay")
    if isinstance(day, str) and len(day) == 10:
        return day
    try:
        d = datetime.fromisoformat(str(session.get("started", "")).replace("Z", "+00:00"))
    except ValueError:
        return "undated"
    return d.astimezone().strftime("%Y-%m-%d")


def load(path: Path) -> dict:
    try:
        with path.open(encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def write_atomic(path: Path, payload: dict) -> None:
    """Rename over the old file, so a crash mid-write never leaves a truncated day."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=1, ensure_ascii=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        os.unlink(tmp)
        raise


def store(device: str, sessions: list, root: Path = None, fields: tuple = None,
          app: str = "audio-timer") -> list:
    """Upsert every session, and return the ids that are now on disk.

    Grouped by day so one request costs one write per day it touches, and so a day that a
    later run reopens is rewritten in place instead of gaining a second row.
    """
    root = ROOT if root is None else root
    fields = FIELDS if fields is None else fields
    by_day = {}
    for s in sessions:
        if not isinstance(s, dict):
            continue
        sid = s.get("id")
        if not isinstance(sid, str) or not sid:
            continue
        by_day.setdefault(day_key(s), []).append(s)

    accepted = []
    for day, rows in by_day.items():
        path = root / f"{day}.json"
        prev = load(path)
        merged = {
            r["id"]: r
            for r in prev.get("sessions", [])
            if isinstance(r, dict) and isinstance(r.get("id"), str)
        }
        for s in rows:
            kept = {k: s[k] for k in fields if k in s}
            kept["device"] = device
            merged[s["id"]] = kept
        payload = {
            "app": app,
            "date": day,
            "sessions": sorted(merged.values(), key=lambda r: str(r.get("started", ""))),
        }
        # Only claim the ids in a day that actually got written. A day that fails leaves its
        # sessions unacknowledged, and the phone keeps them and sends them again.
        try:
            write_atomic(path, payload)
        except OSError as e:
            print(f"! could not write {path}: {e}", file=sys.stderr, flush=True)
            continue
        accepted.extend(r["id"] for r in rows)
    return accepted


class Handler(BaseHTTPRequestHandler):
    server_version = "audio-timer-receiver"

    def cors(self) -> None:
        """The APK uploads from native code and never sees this, but the app also runs as an
        ordinary web page, and there the upload is a cross-origin fetch that the browser
        blocks outright without these. Allowing any origin is not a hole: there is no cookie
        or session to ride on, only the bearer token, which a hostile page does not have.
        Narrow it with AUDIO_TIMER_ORIGIN if you serve the app from one known address."""
        self.send_header("Access-Control-Allow-Origin", ORIGIN)
        self.send_header("Vary", "Origin")

    def reply(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        # An Authorization header plus a JSON content type means the browser always asks first.
        self.send_response(204)
        self.cors()
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler's naming
        # compare_digest, not ==, so a wrong token cannot be found one character at a time.
        sent = self.headers.get("Authorization", "")
        sent = sent[7:] if sent.startswith("Bearer ") else ""
        if not TOKEN or not hmac.compare_digest(sent, TOKEN):
            return self.reply(401, {"error": "bad token"})

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, {"error": "bad length"})
        if length <= 0 or length > MAX_BODY:
            return self.reply(413, {"error": "body too large"})

        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self.reply(400, {"error": "bad json"})

        sessions = body.get("sessions")
        if not isinstance(sessions, list):
            return self.reply(400, {"error": "no sessions"})

        device = body.get("device")
        device = device if isinstance(device, str) else ""

        cardio = self.path.rstrip("/") == "/cardio"
        try:
            accepted = (store(device, sessions, CARDIO_ROOT, CARDIO_FIELDS, "zone-alarm")
                        if cardio else store(device, sessions))
        except Exception as e:  # noqa: BLE001 — a 5xx is what keeps the night on the phone
            print(f"! {e}", file=sys.stderr, flush=True)
            return self.reply(500, {"error": "could not store"})

        print(f"{datetime.now(timezone.utc):%Y-%m-%dT%H:%M:%SZ} "
              f"{len(accepted)}/{len(sessions)} stored{' (cardio)' if cardio else ''}", flush=True)
        self.reply(200, {"accepted": accepted})

    def log_message(self, *args) -> None:
        pass                                    # the line above is the only log worth having


if __name__ == "__main__":
    if not TOKEN:
        sys.exit("AUDIO_TIMER_TOKEN is not set — refusing to accept anything without it")
    ROOT.mkdir(parents=True, exist_ok=True)
    print(f"audio-timer receiver on http://{HOST}:{PORT} → {ROOT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
