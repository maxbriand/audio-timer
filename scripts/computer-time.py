#!/usr/bin/env python3
"""Write the Mac's work sessions, one row each, as computer-time.csv.

Cadence (the work-tracking app) records every session it runs in sessions.json: when it
started, when it ended, and the project it ran under. This lays them out the way
phone-time.csv lays out the phone's screen sessions — date, start, end and length to the
second, in local time — plus the session's category ("pro" or "personal"), which
projects.json gives each project. A session across local midnight comes as two rows, so
a date's rows add up to its computer time.

Before CATEGORY_FROM every session ran under Chess, Cadence's default project, whatever
it really was (Maxime, 2026-09-24), so earlier rows carry no category rather than a
wrong one. A session whose project is gone from projects.json has none either.

Cadence keeps its whole history, so the file is rebuilt whole on every run. An
unreadable or empty sessions.json leaves the file as it is.

Usage: computer-time.py <out.csv> [cadence-dir]
"""

import csv
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path

COLUMNS = ("date", "start", "end", "duration_s", "category")
CATEGORY_FROM = "2026-09-25"

if len(sys.argv) < 2:
    sys.exit("usage: computer-time.py <out.csv> [cadence-dir]")
OUT = Path(sys.argv[1]).expanduser()
CADENCE_DIR = (Path(sys.argv[2]).expanduser() if len(sys.argv) > 2
               else Path("~/Library/Application Support/Cadence").expanduser())


def local(ts):
    """Cadence's UTC timestamp as a naive local datetime, to the second."""
    return (datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone()
            .replace(tzinfo=None, microsecond=0))


def categories():
    try:
        projects = json.loads((CADENCE_DIR / "projects.json").read_text()).get("projects", [])
        return {p["path"]: p.get("category") or "" for p in projects}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {}


def rows(sessions, category):
    out = []
    for x in sessions:
        try:
            st, en = local(x["started_at"]), local(x["ended_at"])
        except (KeyError, TypeError, ValueError):
            continue
        cat = category.get(x.get("project_path"), "")
        while st < en:                       # split at local midnight
            cut = min(en, (st + timedelta(days=1)).replace(hour=0, minute=0, second=0))
            day = st.strftime("%Y-%m-%d")
            out.append({"date": day, "start": st.isoformat(), "end": cut.isoformat(),
                        "duration_s": int((cut - st).total_seconds()),
                        "category": cat if day >= CATEGORY_FROM else ""})
            st = cut
    return sorted(out, key=lambda r: (r["start"], r["end"]))


def main():
    try:
        sessions = json.loads((CADENCE_DIR / "sessions.json").read_text()).get("sessions", [])
    except (OSError, ValueError, AttributeError) as e:
        sys.exit(f"Cadence sessions unreadable ({e}); {OUT.name} untouched")
    out = rows(sessions, categories())
    if not out:
        sys.exit(f"no Cadence sessions; {OUT.name} untouched")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(OUT.parent), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=COLUMNS)
            w.writeheader()
            w.writerows(out)
        os.replace(tmp, OUT)
    except BaseException:
        os.unlink(tmp)
        raise
    print(f"computer-time — {len(out)} sessions → {OUT}")


if __name__ == "__main__":
    main()
