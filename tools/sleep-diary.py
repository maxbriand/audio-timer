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
marker; the night's Fatigue column is the last score after its onset.

The rules, as Maxime defined them (2026-08-18, markers added 2026-08-19):

  bedtime         start of the night's first play.
  initial block   plays chained while the gap between one's end and the next's
                  start is under 20 minutes. Falling asleep happens during the LAST
                  play of that block; sleep onset is the midpoint of its range
                  (a 20-minute play -> onset 10 minutes in).
  SOL             sleep onset - bedtime.
  blocks          every play of the night clustered the same way (<20 min gap).
  final wake      start of the last block, which requires at least TWO blocks —
                  only a morning play proves when sleep ended. A marker is not a
                  wake time.
  rise time       the last wake-up marker after sleep onset — the recorded moment
                  of getting out of bed, raw. Only the marker records it; a night
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
  WASO            over those same awakening blocks: (block end - block start)
                  minus minutes actually played. Near zero by construction for
                  single-play blocks; kept to confirm exactly that.
  TIB             rise time - bedtime.
  TST             final wake - sleep onset - WASO.
  SE              TST / TIB * 100.

A night with one block and no marker has no way to know when the morning came, so
everything past SOL stays empty - never guessed. The averages at the top cover the
last 28 days and only the nights that actually have the number.

Nights are split where the gap between rows exceeds 12 hours; a night is named
after the local date on which it starts. Plays under a minute are noise (a stray
tap) and are dropped; markers are zero-length by design and always kept.
"""

import csv
import json
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

GAP_BLOCK_MIN = 20          # chaining threshold within a night
GAP_NIGHT_H = 12            # a longer silence than this starts a new night
MIN_PLAY_MIN = 1.0          # anything shorter is a stray tap, not a listen
AVG_WINDOW_DAYS = 28

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else ".").expanduser()
OUT = SRC / "sleep-diary.md"
OUT_CSV = SRC / "sleep-diary.csv"

# Hand-written corrections, one file beside the day files (the sync never deletes local
# extras). The raw log is never edited — a wrong value is marked here and the diary stops
# deriving from it. Shape: {"YYYY-MM-DD": {"no_morning_block": true}} — that night's last
# block is NOT a morning wake (final wake and TST become unknown; the block counts as an
# awakening like any other middle one).
OVERRIDES_FILE = SRC / "diary-overrides.json"


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
                         "played": float(s.get("listenedMinutes") or 0)})
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


def night_metrics(rows, overrides):
    plays = [r for r in rows if r["kind"] == "play"]
    if not plays:
        return None                           # a lone marker is not a night

    blocks = cluster(plays, timedelta(minutes=GAP_BLOCK_MIN))
    bedtime = blocks[0][0]["start"]
    base = blocks[0][-1]                      # last play of the initial block
    onset = base["start"] + (base["end"] - base["start"]) / 2

    n = {"date": bedtime.strftime("%Y-%m-%d"), "bedtime": bedtime,
         "sol": mins(onset - bedtime), "awakenings": None, "waso": None,
         "final_wake": None, "rise": None, "tib": None, "tst": None, "se": None,
         "fatigue": None, "note": ""}

    # The morning's self-score, if the alarm was answered: the last one after onset.
    scores = [r for r in rows if r["kind"] == "fatigue" and r["start"] > onset]
    if scores:
        n["fatigue"] = scores[-1]["score"]

    # Rise and final wake are different facts, each with exactly one source, and neither
    # ever gets a stand-in (Maxime, 2026-08-19): blank always means unknown, never guessed.
    markers = [r for r in rows if r["kind"] == "marker" and r["start"] > onset]
    if markers:
        n["rise"] = markers[-1]["start"]      # out of bed: only the marker records it
        n["note"] = markers[-1]["note"]
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
    n["waso"] = sum(
        mins(b[-1]["end"] - b[0]["start"]) - sum(s["played"] for s in b)
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


def main():
    rows = load_rows()
    overrides = load_overrides()
    nights = [n for g in cluster(rows, timedelta(hours=GAP_NIGHT_H))
              if (n := night_metrics(g, overrides))]
    nights.reverse()                          # newest first, like the app's own log

    def window_avgs(end):
        """Trailing 4-week averages as of `end`, over the nights that have the number."""
        win = [n for n in nights if end - timedelta(days=AVG_WINDOW_DAYS) <= n["bedtime"] <= end]
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
        "| Night | Bedtime | SOL | Awakenings | WASO | Final wake | Rise | TIB | TST | SE | Fatigue | 4wk TST | 4wk SE | Note |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for n in nights:
        se = f"{n['se']:.0f} %" if n["se"] is not None else ""
        aw = "" if n["awakenings"] is None else str(n["awakenings"])
        note = n["note"].replace("|", "\\|").replace("\n", " ")
        w_tst, w_se = window_avgs(n["bedtime"])
        avg_tst = fmt_min(sum(w_tst) / len(w_tst)) if w_tst else ""
        avg_se = f"{sum(w_se) / len(w_se):.0f} %" if w_se else ""
        fat = f"{n['fatigue']:.0f}/10" if n["fatigue"] is not None else ""
        lines.append(
            f"| {n['date']} | {fmt_clock(n['bedtime'])} | {fmt_min(n['sol'])} | {aw}"
            f" | {fmt_min(n['waso'])} | {fmt_clock(n['final_wake'])} | {fmt_clock(n['rise'])}"
            f" | {fmt_min(n['tib'])} | {fmt_min(n['tst'])} | {se} | {fat} | {avg_tst} | {avg_se} | {note} |"
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
        w.writerow(["night", "bedtime", "sol_min", "awakenings", "waso_min",
                    "final_wake", "rise", "tib_min", "tst_min", "se_pct", "fatigue_1to10",
                    "avg4w_tst_min", "avg4w_se_pct", "note"])
        for n in nights:
            w_tst, w_se = window_avgs(n["bedtime"])
            w.writerow([
                n["date"], iso(n["bedtime"]), num(n["sol"]), num(n["awakenings"]),
                num(n["waso"]), iso(n["final_wake"]), iso(n["rise"]), num(n["tib"]),
                num(n["tst"]), num(n["se"]), num(n["fatigue"]),
                num(sum(w_tst) / len(w_tst)) if w_tst else "",
                num(sum(w_se) / len(w_se)) if w_se else "",
                n["note"],
            ])

    print(f"sleep-diary.md + sleep-diary.csv — {len(nights)} nights")


if __name__ == "__main__":
    main()
