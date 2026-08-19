#!/usr/bin/env python3
"""Derive a CBT-I sleep diary from the audio-timer day files.

Reads the YYYY-MM-DD.json files the receiver writes and produces sleep-diary.md —
one row per night, plus the 4-week averages that drive the therapy. The player log
is a proxy: audio running means "in bed, awake"; audio left running past sleep is
caught by taking the midpoint of the last pre-sleep play as the onset moment.

Two kinds of rows arrive from the phone. Plays are real listening. A row whose
stopReason is "wake-up" is a marker: zero-length, written by the day-mode switch
at the moment of getting up, optionally carrying a note. Markers are the recorded
rise time — raw, never derived — and their note is the diary's Note column.

The rules, as Maxime defined them (2026-08-18, markers added 2026-08-19):

  bedtime         start of the night's first play.
  initial block   plays chained while the gap between one's end and the next's
                  start is under 20 minutes. Falling asleep happens during the LAST
                  play of that block; sleep onset is the midpoint of its range
                  (a 20-minute play -> onset 10 minutes in).
  SOL             sleep onset - bedtime.
  blocks          every play of the night clustered the same way (<20 min gap).
  rise time       the last wake-up marker after sleep onset — the recorded moment,
                  raw. Without a marker, the end of the last block (the moment the
                  morning audio was stopped) stands in.
  final wake      with a marker: the marker, unless a play block ends within 30
                  minutes of it — then that block is the morning listen and its
                  START is the final wake. Without a marker: start of the last
                  block, which only exists if the night has at least two.
  awakenings      play blocks between sleep onset and the final wake. (Without a
                  marker that is blocks minus the initial one minus the morning
                  one — never negative.)
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

import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

GAP_BLOCK_MIN = 20          # chaining threshold within a night
GAP_NIGHT_H = 12            # a longer silence than this starts a new night
MIN_PLAY_MIN = 1.0          # anything shorter is a stray tap, not a listen
MORNING_ATTACH_MIN = 30     # a block ending this close to the marker is the morning listen
AVG_WINDOW_DAYS = 28

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else ".").expanduser()
OUT = SRC / "sleep-diary.md"


def load_rows():
    rows = []
    for f in sorted(SRC.glob("*.json")):
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


def night_metrics(rows):
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
         "note": ""}

    markers = [r for r in rows if r["kind"] == "marker" and r["start"] > onset]
    marker = markers[-1] if markers else None

    if marker:
        n["rise"] = marker["start"]
        n["note"] = marker["note"]
        last = blocks[-1]
        morning = (len(blocks) >= 2
                   and mins(marker["start"] - last[-1]["end"]) <= MORNING_ATTACH_MIN)
        n["final_wake"] = last[0]["start"] if morning else marker["start"]
        awakening_blocks = blocks[1:-1] if morning else blocks[1:]
    elif len(blocks) >= 2:
        n["rise"] = blocks[-1][-1]["end"]     # morning audio stopped: best available
        n["final_wake"] = blocks[-1][0]["start"]
        awakening_blocks = blocks[1:-1]
    else:
        return n                              # single block, no marker: morning unknown

    n["awakenings"] = len(awakening_blocks)
    n["waso"] = sum(
        mins(b[-1]["end"] - b[0]["start"]) - sum(s["played"] for s in b)
        for b in awakening_blocks
    )
    n["tib"] = mins(n["rise"] - bedtime)
    n["tst"] = mins(n["final_wake"] - onset) - n["waso"]
    n["se"] = n["tst"] / n["tib"] * 100 if n["tib"] > 0 else None
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
    nights = [n for g in cluster(rows, timedelta(hours=GAP_NIGHT_H))
              if (n := night_metrics(g))]
    nights.reverse()                          # newest first, like the app's own log

    cutoff = datetime.now().astimezone() - timedelta(days=AVG_WINDOW_DAYS)
    recent = [n for n in nights if n["bedtime"] >= cutoff]
    tsts = [n["tst"] for n in recent if n["tst"] is not None]
    ses = [n["se"] for n in recent if n["se"] is not None]

    lines = [
        "# Sleep diary",
        "",
        "Derived from the audio-timer Nights log — the player as sleep proxy, the day-mode",
        "switch as the rise-time marker. Regenerated on every sync; do not edit by hand.",
        "Empty cells mean the night gave no way to compute the value, not that it is zero.",
        "",
        "## Last 4 weeks",
        "",
        f"- **Average total sleep time**: {fmt_min(sum(tsts) / len(tsts)) if tsts else '—'}"
        f" ({len(tsts)} night{'s' if len(tsts) != 1 else ''} with data)",
        f"- **Average sleep efficiency**: {f'{sum(ses) / len(ses):.0f} %' if ses else '—'}"
        f" ({len(ses)} night{'s' if len(ses) != 1 else ''} with data)",
        "",
        "## Nights",
        "",
        "| Night | Bedtime | SOL | Awakenings | WASO | Final wake | Rise | TIB | TST | SE | Note |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for n in nights:
        se = f"{n['se']:.0f} %" if n["se"] is not None else ""
        aw = "" if n["awakenings"] is None else str(n["awakenings"])
        note = n["note"].replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {n['date']} | {fmt_clock(n['bedtime'])} | {fmt_min(n['sol'])} | {aw}"
            f" | {fmt_min(n['waso'])} | {fmt_clock(n['final_wake'])} | {fmt_clock(n['rise'])}"
            f" | {fmt_min(n['tib'])} | {fmt_min(n['tst'])} | {se} | {note} |"
        )
    lines.append("")
    OUT.write_text("\n".join(lines))
    print(f"sleep-diary.md — {len(nights)} nights, {len(tsts)} with full data")


if __name__ == "__main__":
    main()
