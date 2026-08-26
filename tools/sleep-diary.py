#!/usr/bin/env python3
"""Derive a CBT-I sleep diary from the audio-timer day files.

Reads the YYYY-MM-DD.json files the receiver writes and produces the diary twice
from the same rows: sleep-diary.md to read (formatted durations, for a human or a
clinician) and sleep-diary.csv to compute on (plain minutes, ISO timestamps, so
nothing has to parse "9h19" back into a number). One row per night, plus the
4-week averages that drive the therapy. The player log
is a proxy: audio running means "in bed, awake"; audio left running past sleep is
caught by taking the midpoint of the last pre-sleep play as the onset moment.

Three kinds of rows arrive from the phone. Plays are real listening. A row whose
stopReason is "wake-up" is a marker: zero-length, written by the day-mode switch
at the moment of getting up, optionally carrying a note. Markers are the recorded
rise time — raw, never derived — and their note is the diary's Note column. A row
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
bedtime. Doses and daylight markers belong to the night they precede and must
never glue two nights together, so both ride outside the night clustering.

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
                  the two: going back to bed and re-rising is real, an afternoon
                  test or an evening mode toggle is the day, not the night (Maxime,
                  2026-08-26 — a 20:14 switch after 1-minute afternoon tests must
                  not become the rise). Only the marker records the rise; a night
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

Nights are split where the gap between rows exceeds 12 hours; a night is named
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

# The day files are the raw log; the diary is a sleep record, so it is written with the
# other sleep documents (sources/sleep/) rather than in the log folder next to them. A
# second argument names the destination outright; without one, the sibling "sleep" folder
# is used when it exists and the source folder otherwise, so a checkout with no Body asset
# beside it still produces the diary somewhere sensible.
OUT_DIR = (Path(sys.argv[2]).expanduser() if len(sys.argv) > 2
           else SRC.parent / "sleep" if (SRC.parent / "sleep").is_dir()
           else SRC)
OUT = OUT_DIR / "sleep-diary.md"
OUT_CSV = OUT_DIR / "sleep-diary.csv"

# Hand-written corrections, one file beside the day files (the sync never deletes local
# extras). The raw log is never edited — a wrong value is marked here and the diary stops
# deriving from it. Shape: {"YYYY-MM-DD": {"no_morning_block": true}} — that night's last
# block is NOT a morning wake (final wake and TST become unknown; the block counts as an
# awakening like any other middle one).
OVERRIDES_FILE = SRC / "diary-overrides.json"

# The cardio day files zone-alarm pushes (receiver route /cardio), filed beside the
# audio log. The Cardio column shows each session's local start time on its calendar
# day — a day input like light and melatonin, read as cause before the night.
_cardio_env = os.environ.get("AUDIO_TIMER_CARDIO_DIR")
CARDIO_SRC = Path(_cardio_env).expanduser() if _cardio_env else SRC.parent / "cardio-sessions"


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
            if s.get("stopReason") == "melatonin":
                rows.append({"kind": "melatonin", "start": start, "end": start})
                continue
            if s.get("stopReason") == "fatigue":
                score = s.get("fatigueScore")
                if isinstance(score, (int, float)):
                    rows.append({"kind": "fatigue", "start": start, "end": start,
                                 "score": float(score)})
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


def attach_day_inputs(nights, doses, lights):
    """The preceding day's zeitgebers: the last dose in the 12 h before bedtime, the
    first daylight marker in the 24 h before it. Both stay out of the night clustering:
    they sit mid-gap between two nights and would bridge them into one."""
    for n in nights:
        prior = [d for d in doses
                 if n["bedtime"] - timedelta(hours=12) <= d["start"] <= n["bedtime"]]
        n["melatonin"] = prior[-1]["start"] if prior else None
        walked = [l for l in lights
                  if n["bedtime"] - timedelta(hours=24) <= l["start"] <= n["bedtime"]]
        n["light"] = walked[0]["start"] if walked else None


def pending_day_rows(nights, doses, lights):
    """Days whose inputs exist but whose night does not (yet): any dose or daylight
    marker no night claimed becomes an inputs-only row named after the event's own
    local date. Tonight's row-to-be is the usual case — it fills in tomorrow."""
    used = {n["melatonin"] for n in nights} | {n["light"] for n in nights}
    days = {}
    for kind, events in (("melatonin", doses), ("light", lights)):
        for e in events:
            if e["start"] in used:
                continue
            d = days.setdefault(e["start"].strftime("%Y-%m-%d"), {})
            # First light (first exposure), last dose (the one nearest the night).
            if kind == "light":
                d.setdefault("light", e["start"])
            else:
                d["melatonin"] = e["start"]
    rows = []
    for date, got in sorted(days.items(), reverse=True):
        rows.append({"date": date, "bedtime": None, "sol": None, "awakenings": None,
                     "waso": None, "final_wake": None, "rise": None, "tib": None,
                     "tst": None, "se": None, "fatigue": None, "note": "",
                     "melatonin": got.get("melatonin"), "light": got.get("light")})
    return rows


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
         "fatigue": None, "light": None, "note": ""}

    # The morning's self-score, if the alarm was answered: the last one after onset.
    # The FIRST score after onset: the morning's answer. A second alarm the same
    # evening (day-mode toggled again) speaks of the day, not this night.
    scores = [r for r in rows if r["kind"] == "fatigue" and r["start"] > onset]
    if scores:
        n["fatigue"] = scores[0]["score"]

    # Rise and final wake are different facts, each with exactly one source, and neither
    # ever gets a stand-in (Maxime, 2026-08-19): blank always means unknown, never guessed.
    markers = [r for r in rows if r["kind"] == "marker" and r["start"] > onset]
    if markers:
        # Out of bed: only the marker records it. The first one after the night is the
        # rise; a later marker re-rises the night ONLY if a block that could hold sleep
        # lies between the two — back to bed and up again is real, an afternoon test or
        # an evening toggle is the day folding back onto the night and is ignored.
        rise = markers[0]
        for m in markers[1:]:
            between = [b for b in blocks
                       if b[0]["start"] > rise["start"] and b[-1]["end"] < m["start"]]
            if any(holds_sleep(b) for b in between):
                rise = m
        n["rise"] = rise["start"]
        n["note"] = rise["note"]
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


def fmt_clock(dt):
    return dt.strftime("%H:%M") if dt else ""


def fmt_min(m):
    if m is None:
        return ""
    m = round(m)
    return f"{m // 60}h{m % 60:02d}" if m >= 60 else f"{m} min"


def load_cardio():
    """Session start times by local day, from the zone-alarm day files."""
    by_day = {}
    for f in (sorted(CARDIO_SRC.glob("*.json")) if CARDIO_SRC.is_dir() else []):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for sess in data.get("sessions", []):
            if not isinstance(sess, dict):
                continue
            try:
                dt = datetime.fromisoformat(
                    str(sess.get("started", "")).replace("Z", "+00:00")).astimezone()
            except ValueError:
                continue
            day = sess.get("localDay") or dt.strftime("%Y-%m-%d")
            by_day.setdefault(day, []).append(dt)
    return {d: sorted(ts) for d, ts in by_day.items()}


def main():
    rows = load_rows()
    doses = [r for r in rows if r["kind"] == "melatonin"]
    lights = [r for r in rows if r["kind"] == "daylight"]
    rows = [r for r in rows if r["kind"] not in ("melatonin", "daylight")]
    overrides = load_overrides()
    nights = [n for g in cluster(rows, timedelta(hours=GAP_NIGHT_H))
              if (n := night_metrics(g, overrides))]
    attach_day_inputs(nights, doses, lights)
    pending = pending_day_rows(nights, doses, lights)
    nights.reverse()                          # newest first, like the app's own log
    nights = pending + nights                 # the day in progress sits on top

    def window_avgs(end):
        """Trailing 4-week averages as of `end`, over the nights that have the number."""
        if end is None:
            return [], []                     # a day still in progress has no night to average
        win = [n for n in nights
               if n["bedtime"] and end - timedelta(days=AVG_WINDOW_DAYS) <= n["bedtime"] <= end]
        tsts = [n["tst"] for n in win if n["tst"] is not None]
        ses = [n["se"] for n in win if n["se"] is not None]
        return tsts, ses



    lines = [
        "# Sleep diary",
        "",
        "Derived from the audio-timer Nights log — the player as sleep proxy, the day-mode",
        "switch as the rise-time marker. Regenerated on every sync; do not edit by hand.",
        "Empty cells mean the night gave no way to compute the value, not that it is zero.",
        "",
        "## Nights",
        "",
        "The 4wk columns are the trailing 28-day averages as of that night — the running",
        "record the therapy tracks, recomputed from the raw log on every sync.",
        "",
        "| Night | Morning light | Cardio | Melatonin | Bedtime | SOL | Wakes | WASO | Final wake | Rise | TIB | TST | SE | Fatigue | 4wk TST | 4wk SE | Note |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    cardio = load_cardio()
    for n in nights:
        se = f"{n['se']:.0f} %" if n["se"] is not None else ""
        aw = "" if n["awakenings"] is None else str(n["awakenings"])
        note = n["note"].replace("|", "\\|").replace("\n", " ")
        w_tst, w_se = window_avgs(n["bedtime"])
        avg_tst = fmt_min(sum(w_tst) / len(w_tst)) if w_tst else ""
        avg_se = f"{sum(w_se) / len(w_se):.0f} %" if w_se else ""
        fat = f"{n['fatigue']:.0f}/10" if n["fatigue"] is not None else ""
        lines.append(
            f"| {n['date']} | {fmt_clock(n['light'])} | {', '.join(fmt_clock(t) for t in cardio.get(n['date'], []))} | {fmt_clock(n['melatonin'])} | {fmt_clock(n['bedtime'])} | {fmt_min(n['sol'])} | {aw}"
            f" | {fmt_min(n['waso'])} | {fmt_clock(n['final_wake'])} | {fmt_clock(n['rise'])}"
            f" | {fmt_min(n['tib'])} | {fmt_min(n['tst'])} | {se} | {fat}"
            f" | {avg_tst} | {avg_se} | {note} |"
        )
    lines.append("")
    OUT.write_text("\n".join(lines))

    # The same rows again, machine-shaped: minutes as plain numbers, clocks as ISO local
    # timestamps (a rise can land on the day after the night's date, so HH:MM alone would
    # lie to any date arithmetic), empty cells staying truly empty.
    def iso(dt):
        return dt.strftime("%Y-%m-%dT%H:%M") if dt else ""

    def num(x):
        return "" if x is None else round(x, 1)

    with OUT_CSV.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["night", "morning_light", "cardio", "melatonin", "bedtime", "sol_min",
                    "awakenings", "waso_min", "final_wake", "rise", "tib_min", "tst_min",
                    "se_pct", "fatigue_1to10", "avg4w_tst_min", "avg4w_se_pct", "note"])
        for n in nights:
            w_tst, w_se = window_avgs(n["bedtime"])
            w.writerow([
                n["date"], iso(n["light"]),
                ";".join(iso(t) for t in cardio.get(n["date"], [])),
                iso(n["melatonin"]), iso(n["bedtime"]),
                num(n["sol"]), num(n["awakenings"]), num(n["waso"]), iso(n["final_wake"]),
                iso(n["rise"]), num(n["tib"]), num(n["tst"]), num(n["se"]), num(n["fatigue"]),
                num(sum(w_tst) / len(w_tst)) if w_tst else "",
                num(sum(w_se) / len(w_se)) if w_se else "",
                n["note"],
            ])

    print(f"sleep-diary.md + sleep-diary.csv — {len(nights)} nights → {OUT_DIR}")


if __name__ == "__main__":
    main()
