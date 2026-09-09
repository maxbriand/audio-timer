#!/usr/bin/env python3
"""Fill sources/exercise-log.csv from the cardio sessions the phone uploaded.

Unlike daily.csv, this file is NOT derived: it holds hand-written rows (a session
logged from memory, a row recovered from a screenshot, a day whose data was lost)
alongside the ones zone-alarm syncs. Rewriting it wholesale would destroy those, so
this tool only ever:

  * ADDS a row for a date the file does not have, and
  * UPDATES a row it wrote itself — recognised by the [zone-alarm sync] marker its
    note carries. Any row without that marker is a human's and is left untouched,
    so a screenshot row is never overwritten by a thinner synced one.

Re-running is therefore idempotent and safe at any time.

One line per day is the file's rule. A day holding more than one session gets the
sums (total, bands) with the highest peak and the earliest start, and its per-part
columns stay blank — two sessions' parts cannot be merged into one numbering
without inventing something. Blank always means not measured, never zero.

Usage: exercise-log-fill.py <exercise-log.csv> [cardio-sessions-dir]
"""

import csv
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path

MARKER = "[zone-alarm sync]"
COLUMNS = ["date", "peak", "max_set", "start", "low_set", "total_min", "light_min",
           "mod_min", "vig_min", "over90_min"] + \
          [f"{k}{i}" for i in range(1, 7) for k in ("m", "r")] + ["rpe", "note"]

if len(sys.argv) < 2:
    sys.exit("usage: exercise-log-fill.py <exercise-log.csv> [cardio-sessions-dir]")
CSV_PATH = Path(sys.argv[1]).expanduser()
_env = os.environ.get("AUDIO_TIMER_CARDIO_DIR")
CARDIO = (Path(sys.argv[2]).expanduser() if len(sys.argv) > 2
          else Path(_env).expanduser() if _env
          else CSV_PATH.parent / "cardio-sessions")


def local(ts):
    return datetime.fromisoformat(str(ts).replace("Z", "+00:00")).astimezone()


def mmss(seconds):
    if seconds is None:
        return ""
    seconds = int(round(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


def minutes(seconds):
    return "" if seconds is None else round(seconds / 60, 1)


def load_sessions():
    """Every finished session the receiver has filed, grouped by its local day."""
    days = {}
    for f in sorted(CARDIO.glob("*.json")) if CARDIO.is_dir() else []:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for s in data.get("sessions", []):
            if not isinstance(s, dict) or not s.get("started") or not s.get("ended"):
                continue                      # a session still running is not a row yet
            try:
                start = local(s["started"])
            except ValueError:
                continue
            day = s.get("localDay") or start.strftime("%Y-%m-%d")
            days.setdefault(day, []).append((start, s))
    return {d: sorted(v, key=lambda x: x[0]) for d, v in days.items()}


def row_for(day, sessions):
    starts = [st for st, _ in sessions]
    ss = [s for _, s in sessions]
    one = ss[0] if len(ss) == 1 else None

    def total(field):
        vals = [s.get(field) for s in ss if isinstance(s.get(field), (int, float))]
        return sum(vals) if vals else None

    bands = [None] * 4
    have = [s.get("bands") for s in ss if isinstance(s.get("bands"), list) and len(s["bands"]) == 4]
    if have:
        bands = [sum(b[i] for b in have) for i in range(4)]

    peaks = [s.get("peakBpm") for s in ss if isinstance(s.get("peakBpm"), (int, float))]
    lows = [s.get("minBpm") for s in ss if isinstance(s.get("minBpm"), (int, float))]
    highs = [s.get("maxBpm") for s in ss if isinstance(s.get("maxBpm"), (int, float))]

    row = {c: "" for c in COLUMNS}
    row["date"] = day
    row["peak"] = max(peaks) if peaks else ""
    row["max_set"] = max(highs) if highs else ""
    row["low_set"] = min(lows) if lows else ""
    row["start"] = min(starts).strftime("%H:%M")
    row["total_min"] = minutes(total("durationSeconds"))
    for i, key in enumerate(("light_min", "mod_min", "vig_min", "over90_min")):
        row[key] = minutes(bands[i])

    # Per-part columns only when the day is a single session — see the module docstring.
    if one and isinstance(one.get("partsDetail"), list):
        for i, p in enumerate(one["partsDetail"][:6], start=1):
            if isinstance(p, dict):
                row[f"m{i}"] = mmss(p.get("toMax"))
                row[f"r{i}"] = mmss(p.get("recovery"))

    notes = []
    if len(ss) > 1:
        notes.append(f"{len(ss)} sessions this day — totals summed, per-part columns left blank")
    if one and isinstance(one.get("partsDetail"), list) and len(one["partsDetail"]) > 6:
        notes.append(f"{len(one['partsDetail'])} parts, beyond the m6/r6 columns")
    for s in ss:
        if s.get("note"):
            notes.append(str(s["note"]))
    rpes = [s.get("rpe") for s in ss if isinstance(s.get("rpe"), (int, float))]
    row["rpe"] = rpes[0] if rpes else ""
    notes.append(MARKER)
    row["note"] = "; ".join(notes)
    return row


def main():
    if not CSV_PATH.exists():
        sys.exit(f"no exercise log at {CSV_PATH}")
    existing = list(csv.DictReader(CSV_PATH.open()))
    by_date = {r["date"]: r for r in existing}

    added = updated = kept = 0
    for day, sessions in load_sessions().items():
        fresh = row_for(day, sessions)
        old = by_date.get(day)
        if old is None:
            by_date[day] = fresh
            added += 1
        elif MARKER in (old.get("note") or ""):
            by_date[day] = fresh
            updated += 1
        else:
            kept += 1                          # a human wrote this row; it wins

    rows = sorted(by_date.values(), key=lambda r: r["date"], reverse=True)
    with CSV_PATH.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in COLUMNS})
    print(f"exercise-log — {added} added, {updated} updated, {kept} left to their author "
          f"→ {CSV_PATH}")


if __name__ == "__main__":
    main()
