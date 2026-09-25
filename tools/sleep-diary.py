#!/usr/bin/env python3
"""Derive a CBT-I sleep diary from the audio-timer day files.

Reads the YYYY-MM-DD.json files the receiver writes and produces daily.csv —
one record for both reading and computing: durations as zero-padded HH:MM (readable
at a glance, sorts correctly as text, parsed as a duration by any spreadsheet),
clocks as ISO local timestamps (the date half is information — a rise lands on the
day after the night's name). One row per night, plus the
4-week averages that drive the therapy. The player log
is a proxy: audio running means "in bed, awake"; audio left running past sleep is
caught by taking the midpoint of the last pre-sleep play as the onset moment.

Three kinds of rows arrive from the phone. Plays are real listening. A row whose
stopReason is "wake-up" is a marker: zero-length, written by the day-mode switch
at the moment of getting up, optionally carrying a note. Markers are the recorded
rise time — raw, never derived. The diary's Note column reads the fatigue answer's
note (where the free text lives since 2026-09-05), falling back to the rise
marker's note for the earlier era, when the wake-up sheet carried the field. A row
whose stopReason is "fatigue" is the answer to the alarm that rings 45 minutes
after the rise: a 1–10 self-score (10 = maximum fatigue), zero-length like the
marker; the night's Fatigue column is the FIRST score after its onset — the
morning's answer; a second ring after an evening mode switch belongs to the day. A row whose
stopReason is "morning-walk" is the daylight marker: the ☀️ Morning walk event on
the day screen, stamped with the tap that logged it — the walk IS the daylight
log. The Morning light column is the FIRST such marker in the 24 hours before the
night's bedtime — the walk of the day the night follows, not the morning after it.
Light and melatonin are the day's two zeitgebers, so a row reads left to right as
cause then effect: the day's inputs, then the night they produced (Maxime,
2026-08-26 — row 2026-08-26 carries the light and dose of the 25th). First
exposure is the fact that matters, later taps say nothing new. ("daylight" rows,
from the short-lived dedicated button, are read the same way.) A row whose
stopReason is "melatonin" is the dose marker, stamped when "Taken ✓" closes the
reminder; the night's Melatonin column is the last dose in the 12 hours before its
bedtime. (A row whose stopReason is "screens-off" is the old 📵 Screens off tap,
gone from the app since 2026-09-09 and never used: it fed a screens_off column that
phone_off replaced on 2026-09-25. Such rows are still set aside, never read.) Doses
and daylight markers belong to the night they precede and must never glue two
nights together, so all ride outside the night clustering. The phone_off column is
the moment the phone was put down before the night, measured, from phone-time.csv:
the end of the last unlocked screen session that began in the 12 hours before
bedtime but more than 5 minutes before it — the session that starts the bedtime
audio is the phone being used to go to bed, not the evening's use, so it is left
out; a session still running into bedtime counts up to bedtime (Maxime, 2026-09-25:
the phone is always the last screen, so one column says when screens stopped).
The computer column is
the day's time in Cadence work sessions, added up from computer-time.csv, the
per-session file tools/computer-time.py writes. The work and personal columns are the
day's LOGGED time in Cadence's pro and personal projects, and computer_off the last
Cadence session end before the night, all read straight from Cadence's local files
(logtime.json / projects.json / sessions.json) — the columns measured by the Mac rather
than logged on the phone. The phone column is the day's phone screen time, added up
from phone-time.csv, the per-session file the phone sends (POST /phone) — measured by
Android, not tapped.

The rules, as Maxime defined them (2026-08-18, markers added 2026-08-19):

  bedtime         start of the night's first REAL block. A block is a test, not the
                  night starting, when its last play never ran untouched for 4 minutes:
                  falling asleep leaves audio playing to no one, and a block without
                  that stretch cannot have contained it (Maxime, 2026-08-25 — a
                  one-minute 23:30 timer test must not become bedtime). Leading test
                  blocks are dropped; a night of nothing but tests is not a night.
  initial block   plays chained while the gap between one's end and the next's
                  start is under 20 minutes. Falling asleep happens during the LAST
                  play of that block; sleep onset is the midpoint of its range
                  (a 20-minute play -> onset 10 minutes in).
  SOL             sleep onset - bedtime.
  blocks          every play of the night clustered the same way (<20 min gap).
  final wake      start of the last block, which requires at least TWO blocks —
                  only a morning play proves when sleep ended. A marker is not a
                  wake time.
  rise time       the first wake-up marker after sleep onset — the recorded moment
                  of getting out of bed, raw. A LATER marker replaces it only when a
                  block that could hold sleep (the untouched test again) lies between
                  the two AND that block began within 12 hours of the current rise:
                  going back to bed and re-rising is real, an afternoon
                  test or an evening mode toggle is the day, not the night (Maxime,
                  2026-08-26 — a 20:14 switch after 1-minute afternoon tests must
                  not become the rise), and a sleep-holding block further out than
                  12 hours is the NEXT night's bedtime, never this night's return
                  to bed (2026-08-29 — the night of the 25th once rose on the 27th). Only the marker records the rise; a night
                  without one has no rise, whatever else it has. Plays that start
                  after the rise belong to the day, not the night: they are not
                  blocks, not awakenings, and never the final wake (this is what
                  keeps TST inside TIB — a daytime play once inflated SE past
                  100 %, which is impossible for a real night).
  awakenings      play blocks between the initial one and the morning one. No
                  middle block means ZERO, not unknown — waking at night always
                  means playing audio, so silence is itself the record. Same for
                  WASO.
  Nothing is ever stood in for: every value has exactly one source, and a night
  missing the source leaves the cell blank. TIB needs rise; TST needs final wake;
  SE needs both.
  WASO            over those same awakening blocks, read exactly as the initial
                  block is read: awake runs from the block's start to the MIDPOINT
                  of its last play, because falling back asleep happens during that
                  play. Audio running means awake — the same reading that gives SOL
                  its minutes — so a 20-minute night listen is 10 minutes of WASO,
                  never zero. It is a lower bound: the minutes spent lying awake
                  before reaching for the phone are nowhere in the log, and are not
                  stood in for.
  TIB             rise time - bedtime.
  TST             final wake - sleep onset - WASO.
  SE              TST / TIB * 100.

A night with one block and no marker has no way to know when the morning came, so
everything past SOL stays empty - never guessed. The averages at the top cover the
last 28 days and only the nights that actually have the number.

Nights are split where the gap between rows exceeds 12 hours — and a recorded rise
also ends its night: the rows after the rise are read again as their own night, so
a day busy enough with taps and toggles to never leave a 12-hour silence (1-minute
tests bridging morning to evening) can no longer glue two real nights into one
31-hour monster (2026-08-29 — the nights of the 25th and 26th, welded by the
26th's afternoon tests). A night is named
after the DAY it follows — the local date read 12 hours before bedtime — so the
whole row speaks of one day: the morning's light, the evening's dose, the night
they produced (Maxime, 2026-08-26: the night beginning 01:47 on the 26th is the
night OF the 25th). A bedtime before midnight names the night after that same
day. Override keys in diary-overrides.json follow this naming. A day whose
inputs are already logged but whose night is not yet — today, before tonight —
appears as an inputs-only row: light and dose shown, every night cell blank
until the night has been slept (same reading as always: blank means the night
gave no data, not zero). The same shape covers a day whose night was never
recorded at all. Plays under a minute are noise (a stray
tap) and are dropped; markers are zero-length by design and always kept.
"""

import csv
import json
import os
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

GAP_BLOCK_MIN = 20          # chaining threshold within a night
GAP_NIGHT_H = 12            # a longer silence than this starts a new night
MIN_PLAY_MIN = 1.0          # anything shorter is a stray tap, not a listen
ONSET_UNTOUCHED_MIN = 4.0   # a bedtime block's last play must run untouched this long
AVG_WINDOW_DAYS = 28

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else ".").expanduser()

# The day files are the raw log; the diary row spans the whole day (light,
# melatonin, then the night), so the record lives as daily.csv at the ROOT of the folder
# holding the day-file folders (~/.../sources/), above the per-domain subfolders. A
# second argument names the destination outright; without one, the day-file folder's
# parent is used.
OUT_DIR = Path(sys.argv[2]).expanduser() if len(sys.argv) > 2 else SRC.parent
OUT_CSV = OUT_DIR / "daily.csv"
# Retired outputs, removed on every run so they cannot linger stale: the markdown twin
# (2026-08-29), and the pre-2026-09-05 home — sleep-diary.csv in the sleep/ subfolder,
# from when the record was filed as a sleep document rather than the day record.
RETIRED = [OUT_DIR / "sleep-diary.md", OUT_DIR / "sleep-diary.csv",
           OUT_DIR / "sleep" / "sleep-diary.md", OUT_DIR / "sleep" / "sleep-diary.csv"]

# Hand-written corrections, one file beside the day files (the sync never deletes local
# extras). The raw log is never edited — a wrong value is marked here and the diary stops
# deriving from it. Shape: {"YYYY-MM-DD": {"no_morning_block": true}} — that night's last
# block is NOT a morning wake (final wake and TST become unknown; the block counts as an
# awakening like any other middle one). {"YYYY-MM-DD": {"bedtime_only": true}} — the
# night was not a true record: only the bedtime is kept, every other night value blank.
OVERRIDES_FILE = SRC / "diary-overrides.json"

# (A Cardio column lived here until 2026-09-09, showing each zone-alarm session's start
# time. It was dropped: exercise-log.csv is the cardio record now — the session's own row,
# with its bands, parts and effort — and a start time repeated here said nothing that file
# does not say better.)

# computer-time.csv, one row per Cadence session, written by tools/computer-time.py
# (the sync refreshes it before this runs). The day's rows add up to its computer time;
# a day with no session stays blank, like every other missing source.
# Cadence (the work-tracking app) keeps its whole history in two local JSON files, so
# the diary reads them directly — no collector, no cache. logtime.json is the
# deliberately LOGGED time, split by the category projects.json gives each project:
# "pro" projects make the work column, "personal" ones the personal column (minutes per
# local day). Before CADENCE_SPLIT_FROM everything was logged under Chess, Cadence's
# default project, whatever it really was (Maxime, 2026-09-24), so the split starts
# there and earlier days stay blank. An entry whose project is gone from projects.json
# counts in neither;
# sessions.json holds the precise activity spans, whose last end before a night's
# bedtime is the computer_off marker. Cadence clips sessions at midnight (the
# continuation restarts at 00:00 next day), so the off-moment must be read across
# midnight — the same last-in-the-12-hours-before-bedtime reading as the dose.
_cadence_env = os.environ.get("AUDIO_TIMER_CADENCE_DIR")
CADENCE_DIR = (Path(_cadence_env).expanduser() if _cadence_env
               else Path("~/Library/Application Support/Cadence").expanduser())
CADENCE_SPLIT_FROM = "2026-09-25"


def load_cadence():
    def loc(ts):
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone()
    logged = {"pro": {}, "personal": {}}
    try:
        category = {p["path"]: p.get("category") for p in json.loads(
            (CADENCE_DIR / "projects.json").read_text()).get("projects", [])}
    except (OSError, ValueError, KeyError, TypeError):
        category = {}
    try:
        entries = json.loads((CADENCE_DIR / "logtime.json").read_text()).get("entries", [])
    except (OSError, ValueError):
        entries = []
    for e in entries:
        try:
            day = loc(e["started_at"]).strftime("%Y-%m-%d")
        except (KeyError, ValueError):
            continue
        m = e.get("minutes_logged")
        if day < CADENCE_SPLIT_FROM or not isinstance(m, (int, float)):
            continue
        # A day with any logged time shows 00:00 in the other column, not blank: Cadence
        # was running, just not on that side.
        for side in logged.values():
            side.setdefault(day, 0)
        side = logged.get(category.get(e.get("project_path")))
        if side is not None:
            side[day] += m
    ends = []
    try:
        sess = json.loads((CADENCE_DIR / "sessions.json").read_text()).get("sessions", [])
    except (OSError, ValueError):
        sess = []
    for x in sess:
        try:
            ends.append(loc(x["ended_at"]))
        except (KeyError, ValueError):
            continue
    return logged["pro"], logged["personal"], sorted(ends)


_computer_env = os.environ.get("AUDIO_TIMER_COMPUTER_FILE")
COMPUTER_FILE = (Path(_computer_env).expanduser() if _computer_env
                 else SRC.parent / "computer-time.csv")


def load_computer():
    minutes = {}
    try:
        with COMPUTER_FILE.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                try:
                    minutes[r["date"]] = minutes.get(r["date"], 0) + int(r["duration_s"]) / 60
                except (KeyError, TypeError, ValueError):
                    continue
    except OSError:
        pass
    return minutes


# The phone's screen sessions, one row each, as the receiver files them (POST /phone). A
# session across midnight comes as two rows, so a date's rows add up to its screen time,
# glances included. Android keeps about ten days of log, so days before the first send
# stay blank, and the first day it holds can be partial.
_phone_env = os.environ.get("AUDIO_TIMER_PHONE_CSV")
PHONE_FILE = (Path(_phone_env).expanduser() if _phone_env
              else SRC.parent / "phone-time.csv")


def load_phone():
    minutes = {}
    try:
        with PHONE_FILE.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                try:
                    minutes[r["date"]] = minutes.get(r["date"], 0) + int(r["duration_s"]) / 60
                except (KeyError, TypeError, ValueError):
                    continue
    except OSError:
        pass
    return minutes


# The fatigue checks the phone sends (POST /fatigue), one day file each in fatigue-checks/
# beside the audio day files. Each check has its own columns, created the first time a
# check with that timing sends a real (non-test) run, named "<what> <when>" (Maxime,
# 2026-09-20 and 2026-09-25): what is HR / HRV (the strap test), PVT (its time, the only
# thing a PVT records here) or Fatigue (the question's 1–10 score), and when is the
# check's own `column` suffix — "+45min", "+4h", "-1h sleep". The columns sit just
# before bedtime, in the order the checks happen in the day. A check is keyed by its
# timing (anchor + offsetMin), never by its id or its words, so editing a check's delay
# starts a new column and the old one keeps its history. Test-button runs belong in no
# column, and a step not done is a blank cell, never a guess. The row is the check's
# localDay: the morning checks of day D sit with D's other day inputs (its light, its
# dose), ahead of the night of D. Two real runs of one check on one day: the first done
# value wins, like the first score after onset.
_fatigue_env = os.environ.get("AUDIO_TIMER_FATIGUE_DIR")
FATIGUE_DIR = (Path(_fatigue_env).expanduser() if _fatigue_env
               else SRC.parent / "fatigue-checks")
FATIGUE_MEASURES = (("strap", "hr", "HR"), ("strap", "rmssd", "HRV"),
                    ("pvt", "at", "PVT"), ("question", "score", "Fatigue"))


def load_fatigue_checks():
    """Returns (columns, values): the ordered column names, and {column: {day: value}}."""
    runs = []
    for f in sorted(FATIGUE_DIR.glob("*.json")):
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}\.json", f.name):
            continue
        try:
            runs += json.loads(f.read_text()).get("sessions", [])
        except (OSError, ValueError, AttributeError):
            continue
    checks = {}
    for r in sorted(runs, key=lambda r: r.get("started") or ""):
        if r.get("test") or not isinstance(r.get("steps"), dict) or not r.get("localDay"):
            continue
        try:
            key = (r["anchor"], int(r["offsetMin"]))
        except (KeyError, TypeError, ValueError):
            continue
        c = checks.setdefault(key, {"when": r.get("column") or "", "values": {}, "hours": []})
        try:
            t = datetime.fromisoformat(r["started"].replace("Z", "+00:00")).astimezone()
            # The hour of the day it ran, counted from 05:00 so a check after midnight
            # still sorts at the end of the day it belongs to.
            c["hours"].append((t.hour * 60 + t.minute - 300) % 1440)
        except (KeyError, AttributeError, ValueError):
            pass
        for step, field, what in FATIGUE_MEASURES:
            st = r["steps"].get(step)
            if not isinstance(st, dict):
                continue
            cell = c["values"].setdefault(what, {})     # the step exists: so does its column
            v = st.get(field)
            if st.get("status") != "done" or v is None or r["localDay"] in cell:
                continue
            if what == "PVT":
                v = datetime.fromtimestamp(v / 1000).astimezone()
            cell[r["localDay"]] = v
    # In the order they happen in the day (Maxime, 2026-09-25): by the median hour the
    # check's runs took place at — wake- and sleep-anchored checks interleave by the
    # clock, not by anchor. Within a check, the order the steps run in.
    def hour(k):
        h = sorted(checks[k]["hours"])
        return h[len(h) // 2] if h else 0
    order = sorted(checks, key=lambda k: (hour(k), k))
    columns, values = [], {}
    for k in order:
        for _, _, what in FATIGUE_MEASURES:
            if what in checks[k]["values"] and f"{what} {checks[k]['when']}" not in values:
                name = f"{what} {checks[k]['when']}"
                columns.append(name)
                values[name] = checks[k]["values"][what]
    return columns, values


PHONE_LAUNCH_MIN = 5   # phone use this close to bedtime is starting the night's audio


def load_phone_uses():
    """The phone's unlocked screen sessions as (start, end) local clocks, in order, a
    session the file split at midnight joined back into one."""
    uses = []
    try:
        with PHONE_FILE.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if r.get("unlocked") != "yes":
                    continue                    # a glance at the clock is not phone use
                try:
                    uses.append((datetime.fromisoformat(r["start"]),
                                 datetime.fromisoformat(r["end"])))
                except (KeyError, TypeError, ValueError):
                    continue
    except OSError:
        pass
    joined = []
    for a, b in sorted(uses):
        if joined and a - joined[-1][1] <= timedelta(seconds=1):
            joined[-1] = (joined[-1][0], max(joined[-1][1], b))
        else:
            joined.append((a, b))
    return joined


def load_overrides():
    try:
        return json.loads(OVERRIDES_FILE.read_text())
    except (OSError, ValueError):
        return {}


def load_rows():
    rows = []
    day_files = [f for f in sorted(SRC.glob("*.json"))
                 if re.fullmatch(r"\d{4}-\d{2}-\d{2}\.json", f.name)]
    for f in day_files:
        try:
            data = json.loads(f.read_text())
        except ValueError:
            continue
        for s in data.get("sessions", []):
            if not s.get("started"):
                continue
            start = datetime.fromisoformat(s["started"].replace("Z", "+00:00")).astimezone()
            if s.get("stopReason") == "wake-up":
                rows.append({"kind": "marker", "start": start, "end": start,
                             "note": (s.get("note") or "").strip()})
                continue
            if s.get("stopReason") in ("morning-walk", "daylight"):
                rows.append({"kind": "daylight", "start": start, "end": start})
                continue
            if s.get("stopReason") == "screens-off":
                rows.append({"kind": "screens", "start": start, "end": start})
                continue
            if s.get("stopReason") == "melatonin":
                rows.append({"kind": "melatonin", "start": start, "end": start})
                continue
            if s.get("stopReason") == "fatigue":
                score = s.get("fatigueScore")
                if isinstance(score, (int, float)):
                    rows.append({"kind": "fatigue", "start": start, "end": start,
                                 "score": float(score),
                                 "note": (s.get("note") or "").strip()})
                continue
            if not s.get("ended"):
                continue                      # an unfinished run says nothing about sleep
            if (s.get("listenedMinutes") or 0) < MIN_PLAY_MIN:
                continue
            end = datetime.fromisoformat(s["ended"].replace("Z", "+00:00")).astimezone()
            if end <= start:
                continue
            rows.append({"kind": "play", "start": start, "end": end,
                         "played": float(s.get("listenedMinutes") or 0),
                         "untouched": s.get("minutesUntouchedBeforeStop")})
    rows.sort(key=lambda r: r["start"])
    return rows


def cluster(rows, gap):
    groups, cur = [], []
    for r in rows:
        if cur and (r["start"] - cur[-1]["end"]) > gap:
            groups.append(cur)
            cur = []
        cur.append(r)
    if cur:
        groups.append(cur)
    return groups


def mins(td):
    return td.total_seconds() / 60


def attach_day_inputs(nights, doses, lights, phone_uses, cad_ends):
    """The preceding day's zeitgebers: the last dose in the 12 h before bedtime, the
    first daylight marker in the 24 h before it, the phone put down before it (see
    the module docstring for phone_off). All stay out of the night clustering: they
    sit mid-gap between two nights and would bridge them into one."""
    for n in nights:
        prior = [d for d in doses
                 if n["bedtime"] - timedelta(hours=12) <= d["start"] <= n["bedtime"]]
        n["melatonin"] = prior[-1]["start"] if prior else None
        walked = [l for l in lights
                  if n["bedtime"] - timedelta(hours=24) <= l["start"] <= n["bedtime"]]
        n["light"] = walked[0]["start"] if walked else None
        bed = n["bedtime"].replace(tzinfo=None)     # phone-time.csv clocks are local, naive
        used = [(a, b) for a, b in phone_uses
                if bed - timedelta(hours=12) <= a < bed - timedelta(minutes=PHONE_LAUNCH_MIN)]
        n["phone_off"] = min(used[-1][1], bed) if used else None
        off = [t for t in cad_ends
               if n["bedtime"] - timedelta(hours=12) <= t <= n["bedtime"]]
        n["cadence_off"] = off[-1] if off else None


def pending_day_rows(nights, doses, lights):
    """Days whose inputs exist but whose night does not (yet): any dose or daylight
    marker no night claimed becomes an inputs-only row named after the
    event's own local date. Tonight's row-to-be is the usual case — it fills in
    tomorrow."""
    # A marker is claimed when it falls inside ANY night's window for its kind — not
    # only when it is the one the night chose. Two taps the same evening are one
    # night's story: the unchosen one says nothing new and must not spawn a phantom
    # inputs-only row for the same date.
    def claimed(e, window_h):
        return any(n["bedtime"]
                   and n["bedtime"] - timedelta(hours=window_h) <= e["start"] <= n["bedtime"]
                   for n in nights)
    days = {}
    for kind, window_h, events in (("melatonin", 12, doses), ("light", 24, lights)):
        for e in events:
            if claimed(e, window_h):
                continue
            d = days.setdefault(e["start"].strftime("%Y-%m-%d"), {})
            # First light (first exposure); last dose (the one nearest the night).
            if kind == "light":
                d.setdefault("light", e["start"])
            else:
                d[kind] = e["start"]
    return [day_row(date, **got) for date, got in sorted(days.items(), reverse=True)]


def day_row(date, melatonin=None, light=None):
    """A row with no night: the day's inputs only."""
    return {"date": date, "bedtime": None, "sol": None, "awakenings": None,
            "waso": None, "final_wake": None, "rise": None, "tib": None,
            "tst": None, "se": None, "fatigue": None, "note": "",
            "melatonin": melatonin, "light": light, "phone_off": None,
            "cadence_off": None}


def night_metrics(rows, overrides):
    plays = [r for r in rows if r["kind"] == "play"]
    if not plays:
        return None                           # a lone marker is not a night

    blocks = cluster(plays, timedelta(minutes=GAP_BLOCK_MIN))

    # The bedtime block must be one sleep could have arrived in: its last play ran
    # untouched into its stop for a few minutes (a missing field passes — old rows are
    # not retroactively disqualified). A 1-minute timer test never does. Trim from the
    # front only: a short block in the middle of the night is a real awakening.
    def holds_sleep(b):
        u = b[-1]["untouched"]
        return u is None or u >= ONSET_UNTOUCHED_MIN
    while blocks and not holds_sleep(blocks[0]):
        blocks = blocks[1:]
    if not blocks:
        return None                       # an evening of tests is not a night
    plays = [p for b in blocks for p in b]
    bedtime = blocks[0][0]["start"]
    base = blocks[0][-1]                      # last play of the initial block
    onset = base["start"] + (base["end"] - base["start"]) / 2

    n = {"date": (bedtime - timedelta(hours=12)).strftime("%Y-%m-%d"), "bedtime": bedtime,
         "sol": mins(onset - bedtime), "awakenings": None, "waso": None,
         "final_wake": None, "rise": None, "tib": None, "tst": None, "se": None,
         "fatigue": None, "light": None, "phone_off": None, "cadence_off": None,
         "note": ""}

    # The morning's self-score, if the alarm was answered: the last one after onset.
    # The FIRST score after onset: the morning's answer. A second alarm the same
    # evening (day-mode toggled again) speaks of the day, not this night. The night
    # note rides this answer too (moved off the wake-up sheet, 2026-09-05).
    scores = [r for r in rows if r["kind"] == "fatigue" and r["start"] > onset]
    if scores:
        n["fatigue"] = scores[0]["score"]
        n["note"] = scores[0].get("note", "")

    # Rise and final wake are different facts, each with exactly one source, and neither
    # ever gets a stand-in (Maxime, 2026-08-19): blank always means unknown, never guessed.
    markers = [r for r in rows if r["kind"] == "marker" and r["start"] > onset]
    if markers:
        # Out of bed: only the marker records it. The first one after the night is the
        # rise; a later marker re-rises the night ONLY if a block that could hold sleep
        # lies between the two AND began within the night gap of the current rise —
        # back to bed and up again is real, an afternoon test or an evening toggle is
        # the day folding back onto the night and is ignored, and a sleep-holding block
        # further out is the NEXT night's bedtime, not this night's return to bed.
        rise = markers[0]
        for m in markers[1:]:
            between = [b for b in blocks
                       if b[0]["start"] > rise["start"] and b[-1]["end"] < m["start"]]
            if any(holds_sleep(b)
                   and b[0]["start"] - rise["start"] <= timedelta(hours=GAP_NIGHT_H)
                   for b in between):
                rise = m
        n["rise"] = rise["start"]
        # Note: the fatigue answer's text wins; marker notes are the pre-2026-09-05 era
        # (the wake-up sheet carried the field then) and still read for those nights.
        n["note"] = n["note"] or rise["note"]
        n["tib"] = mins(n["rise"] - bedtime)
        # Out of bed means the night is over: whatever plays after the rise is daytime
        # listening, not a block of this night. Without this cut a late-morning play
        # becomes the "final wake" and pushes TST past TIB (the impossible SE > 100 %).
        plays = [p for p in plays if p["start"] < n["rise"]]
        blocks = cluster(plays, timedelta(minutes=GAP_BLOCK_MIN))

    # A marked night is one whose last block LOOKS like a morning wake but is known not
    # to be one — it demotes to an ordinary awakening and the morning stays unknown.
    no_morning = overrides.get(n["date"], {}).get("no_morning_block", False)

    # Awakenings and WASO come from the middle blocks, and an absent middle block IS the
    # record: waking at night always means playing audio, so no block means no awakening —
    # zero, not unknown (Maxime, 2026-08-19).
    awakening_blocks = blocks[1:] if no_morning else blocks[1:-1]
    n["awakenings"] = len(awakening_blocks)
    # Each awakening ends the way the night began: asleep again somewhere inside the last
    # play of the block, so its midpoint is the moment. Subtracting the played minutes
    # instead (as this did until 2026-08-22) measured the SILENCE between chained plays,
    # which is zero for a single-play awakening — it read the very same audio as "awake"
    # at bedtime and as "asleep" at 04:00, and handed those minutes to TST as sleep.
    n["waso"] = sum(
        mins((b[-1]["start"] + (b[-1]["end"] - b[-1]["start"]) / 2) - b[0]["start"])
        for b in awakening_blocks
    )

    if len(blocks) >= 2 and not no_morning:   # woke and played: only a block proves it
        n["final_wake"] = blocks[-1][0]["start"]
        n["tst"] = mins(n["final_wake"] - onset) - n["waso"]

    if n["tst"] is not None and n["tib"]:
        n["se"] = n["tst"] / n["tib"] * 100
    return n


def main():
    rows = load_rows()
    doses = [r for r in rows if r["kind"] == "melatonin"]
    lights = [r for r in rows if r["kind"] == "daylight"]
    rows = [r for r in rows if r["kind"] not in ("melatonin", "daylight", "screens")]
    overrides = load_overrides()
    # A recorded rise ends its night, so the rows after it are read again as their own
    # night: a day busy enough with taps to never leave a 12-hour silence would otherwise
    # weld two real nights into one group, and the second night's bedtime block would
    # masquerade as this night's "return to bed".
    def nights_in(group):
        n = night_metrics(group, overrides)
        if not n:
            return []
        found = [n]
        if n["rise"]:
            found += nights_in([r for r in group if r["start"] > n["rise"]])
        return found

    nights = [n for g in cluster(rows, timedelta(hours=GAP_NIGHT_H))
              for n in nights_in(g)]
    # A night marked bedtime_only was not a true record: only its bedtime stands, every
    # other night value goes blank (unknown) and drops out of the averages. Applied after
    # the split above, which still needs the real rise to cut the next night off.
    for n in nights:
        if overrides.get(n["date"], {}).get("bedtime_only", False):
            for k in ("sol", "awakenings", "waso", "final_wake", "rise", "tib", "tst",
                      "se", "fatigue"):
                n[k] = None
    cad_work, cad_personal, cad_ends = load_cadence()
    attach_day_inputs(nights, doses, lights, load_phone_uses(), cad_ends)
    pending = pending_day_rows(nights, doses, lights)
    fatigue_cols, fatigue_vals = load_fatigue_checks()
    # A day with a check but nothing else yet still gets its row, so no result is dropped.
    have = {n["date"] for n in nights + pending}
    pending += [day_row(d) for d in sorted({d for col in fatigue_vals.values() for d in col}
                                           - have)]
    # Newest first, like the app's own log; pending inputs-only rows fall into date
    # order with the nights instead of stacking on top out of sequence.
    nights = sorted(nights + pending, key=lambda n: n["date"], reverse=True)

    def window_avgs(end):
        """Trailing 4-week averages as of `end`, over the nights that have the number."""
        if end is None:
            return [], []                     # a day still in progress has no night to average
        win = [n for n in nights
               if n["bedtime"] and end - timedelta(days=AVG_WINDOW_DAYS) <= n["bedtime"] <= end]
        tsts = [n["tst"] for n in win if n["tst"] is not None]
        ses = [n["se"] for n in win if n["se"] is not None]
        return tsts, ses



    computer = load_computer()
    phone = load_phone()

    # Durations as zero-padded HH:MM, clocks as ISO local timestamps (a rise can land
    # on the day after the night's date, so HH:MM alone would lie to any date
    # arithmetic), empty cells staying truly empty — blank means the night gave no way
    # to compute the value, never zero.
    def iso(dt):
        return dt.strftime("%Y-%m-%dT%H:%M") if dt else ""

    def hm(m):
        if m is None:
            return ""
        m = round(m)
        return f"{m // 60:02d}:{m % 60:02d}"

    def num(x):
        return "" if x is None else round(x, 1)

    def fatigue_cell(v):
        return iso(v) if isinstance(v, datetime) else num(v)

    with OUT_CSV.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["night", "morning_light", "melatonin", "computer",
                    "work", "personal", "computer_off", "phone", "phone_off", *fatigue_cols, "bedtime", "sol",
                    "awakenings", "waso", "final_wake", "rise", "tib", "tst",
                    "se_pct", "fatigue_1to10", "avg4w_tst", "avg4w_se_pct", "note"])
        for n in nights:
            w_tst, w_se = window_avgs(n["bedtime"])
            w.writerow([
                n["date"], iso(n["light"]),
                iso(n["melatonin"]), hm(computer.get(n["date"])),
                hm(cad_work.get(n["date"])), hm(cad_personal.get(n["date"])),
                iso(n["cadence_off"]), hm(phone.get(n["date"])),
                iso(n["phone_off"]),
                *[fatigue_cell(fatigue_vals[c].get(n["date"])) for c in fatigue_cols],
                iso(n["bedtime"]),
                hm(n["sol"]), num(n["awakenings"]), hm(n["waso"]), iso(n["final_wake"]),
                iso(n["rise"]), hm(n["tib"]), hm(n["tst"]), num(n["se"]), num(n["fatigue"]),
                hm(sum(w_tst) / len(w_tst)) if w_tst else "",
                num(sum(w_se) / len(w_se)) if w_se else "",
                n["note"],
            ])

    for old in RETIRED:
        old.unlink(missing_ok=True)

    print(f"daily.csv — {len(nights)} nights → {OUT_DIR}")


if __name__ == "__main__":
    main()
