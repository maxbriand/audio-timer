#!/usr/bin/env python3
"""Derive a CBT-I sleep diary from the audio-timer day files.

Reads the YYYY-MM-DD.json files the receiver writes and produces sleep-diary.md —
one row per night, plus the 4-week averages that drive the therapy. The player log
is a proxy: audio running means "in bed, awake"; audio left running past sleep is
caught by taking the midpoint of the last pre-sleep play as the onset moment.

The rules, as Maxime defined them (2026-08-18):

  bedtime         start of the night's first session.
  initial block   sessions chained while the gap between one's end and the next's
                  start is under 20 minutes. Falling asleep happens during the LAST
                  session of that block; sleep onset is the midpoint of its range
                  (a 20-minute play -> onset 10 minutes in).
  SOL             sleep onset - bedtime.
  blocks          every session of the night clustered the same way (<20 min gap).
  awakenings      blocks after the initial one, minus 1 - the final block is the
                  morning wake, not an awakening. Never negative.
  WASO            middle blocks only (not first, not last): sum over them of
                  (block end - block start) - minutes actually played. Near zero by
                  construction for single-play blocks; kept to confirm exactly that.
  final wake      start of the last block.
  rise time       end of the last block - the raw recorded moment, no adjustment.
  TIB             rise time - bedtime.
  TST             final wake - sleep onset - WASO   (equivalent to
                  TIB - SOL - WASO - morning in-bed time, without double counting).
  SE              TST / TIB * 100.

A night with a single block has no morning session, so awakenings, WASO, final
wake, rise, TIB, TST and SE are simply left empty - never guessed. The averages at
the top cover the last 28 days and only the nights that actually have the number.

Nights are split where the gap between sessions exceeds 12 hours; a night is named
after the local date on which it starts. Sessions that played under a minute are
noise (a stray tap on play) and are dropped before any of this runs.
"""

import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

GAP_BLOCK_MIN = 20          # chaining threshold within a night
GAP_NIGHT_H = 12            # a longer silence than this starts a new night
MIN_PLAY_MIN = 1.0          # anything shorter is a stray tap, not a listen
AVG_WINDOW_DAYS = 28

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else ".").expanduser()
OUT = SRC / "sleep-diary.md"


def load_sessions():
    rows = []
    for f in sorted(SRC.glob("*.json")):
        try:
            data = json.loads(f.read_text())
        except ValueError:
            continue
        for s in data.get("sessions", []):
            if not s.get("started") or not s.get("ended"):
                continue                      # an unfinished run says nothing about sleep
            if (s.get("listenedMinutes") or 0) < MIN_PLAY_MIN:
                continue
            start = datetime.fromisoformat(s["started"].replace("Z", "+00:00")).astimezone()
            end = datetime.fromisoformat(s["ended"].replace("Z", "+00:00")).astimezone()
            if end <= start:
                continue
            rows.append({"start": start, "end": end,
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


def night_metrics(sessions):
    blocks = cluster(sessions, timedelta(minutes=GAP_BLOCK_MIN))
    first, last = blocks[0], blocks[-1]

    bedtime = first[0]["start"]
    base = first[-1]                          # last play of the initial block
    onset = base["start"] + (base["end"] - base["start"]) / 2
    sol = mins(onset - bedtime)

    n = {"date": bedtime.strftime("%Y-%m-%d"), "bedtime": bedtime, "sol": sol,
         "awakenings": None, "waso": None, "final_wake": None, "rise": None,
         "tib": None, "tst": None, "se": None}

    if len(blocks) < 2:
        return n                              # no morning session: the rest stays empty

    n["awakenings"] = max(len(blocks) - 2, 0)
    n["waso"] = sum(
        mins(b[-1]["end"] - b[0]["start"]) - sum(s["played"] for s in b)
        for b in blocks[1:-1]
    )
    n["final_wake"] = last[0]["start"]
    n["rise"] = last[-1]["end"]
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
    sessions = load_sessions()
    nights = [night_metrics(g) for g in cluster(sessions, timedelta(hours=GAP_NIGHT_H))]
    nights.reverse()                          # newest first, like the app's own log

    cutoff = datetime.now().astimezone() - timedelta(days=AVG_WINDOW_DAYS)
    recent = [n for n in nights if n["bedtime"] >= cutoff]
    tsts = [n["tst"] for n in recent if n["tst"] is not None]
    ses = [n["se"] for n in recent if n["se"] is not None]

    lines = [
        "# Sleep diary",
        "",
        "Derived from the audio-timer Nights log — the player as sleep proxy. Regenerated",
        "on every sync; do not edit by hand. Empty cells mean the night had no morning",
        "session to compute from, not that the value is zero.",
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
        lines.append(
            f"| {n['date']} | {fmt_clock(n['bedtime'])} | {fmt_min(n['sol'])} | {aw}"
            f" | {fmt_min(n['waso'])} | {fmt_clock(n['final_wake'])} | {fmt_clock(n['rise'])}"
            f" | {fmt_min(n['tib'])} | {fmt_min(n['tst'])} | {se} |  |"
        )
    lines.append("")
    OUT.write_text("\n".join(lines))
    print(f"sleep-diary.md — {len(nights)} nights, {len(tsts)} with full data")


if __name__ == "__main__":
    main()
