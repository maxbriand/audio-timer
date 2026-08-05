#!/usr/bin/env python3
"""Flatten the per-day session JSON pushed by the PWA into one CSV.

    python3 tools/sessions-json-to-csv.py <json-dir> <out.csv>

Reads every YYYY-MM-DD.json in <json-dir>, sorts the runs by start time, and writes a single
CSV with the same columns as the app's own "Export CSV". Prints the row count and nothing
else, so the LaunchAgent script can put it straight in its log line.

Rewrites the CSV from scratch every time — the JSON files are the record, this is derived.
De-duplicates on session id, so a run that was corrected upstream appears once, corrected.
"""

import csv
import glob
import json
import os
import sys

FIELDS = [
    ('started', 'started'),
    ('ended', 'ended'),
    ('listened_minutes', 'listenedMinutes'),
    ('timer_minutes', 'timerMinutes'),
    ('timer_cancelled', 'timerCancelled'),
    ('timer_auto_armed', 'timerAutoArmed'),
    ('speed', 'speed'),
    ('fade_in_seconds', 'fadeInSeconds'),
    ('stop_reason', 'stopReason'),
    ('track_start', 'trackStart'),
    ('track_end', 'trackEnd'),
    ('stop_position_seconds', 'stopPositionSeconds'),
    ('minutes_untouched_before_stop', 'minutesUntouchedBeforeStop'),
]


def cell(value):
    if value is None:
        return ''
    if value is True:
        return 'yes'
    if value is False:
        return ''
    return value


def main():
    if len(sys.argv) != 3:
        sys.exit('usage: sessions-json-to-csv.py <json-dir> <out.csv>')
    src, out = sys.argv[1], sys.argv[2]

    rows = {}
    for path in sorted(glob.glob(os.path.join(src, '*.json'))):
        try:
            with open(path, encoding='utf-8') as fh:
                data = json.load(fh)
        except (OSError, ValueError) as err:
            # One malformed day must not cost the other 200.
            print('skipped %s (%s)' % (os.path.basename(path), err), file=sys.stderr)
            continue
        for s in data.get('sessions', []):
            if isinstance(s, dict) and s.get('id'):
                rows[s['id']] = s

    ordered = sorted(rows.values(), key=lambda s: str(s.get('started') or ''))

    tmp = out + '.tmp'
    with open(tmp, 'w', newline='', encoding='utf-8') as fh:
        w = csv.writer(fh)
        w.writerow([name for name, _ in FIELDS])
        for s in ordered:
            w.writerow([cell(s.get(key)) for _, key in FIELDS])
    os.replace(tmp, out)

    print(len(ordered))


if __name__ == '__main__':
    main()
