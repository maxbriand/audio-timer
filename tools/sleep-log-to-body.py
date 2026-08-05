#!/usr/bin/env python3
"""Fold exported Audio Timer sessions into the Body asset.

The app's ☾ sheet exports `audio-timer-sessions.csv`. This appends the rows that are not
already recorded to:

    ~/Documents/Assets/Body/sources/sleep/audio-sessions.csv

De-duplicates on the `started` timestamp, so running it repeatedly on overlapping exports
is safe. Prints a summary and changes nothing unless --write is passed.

    python3 tools/sleep-log-to-body.py                      # show what would be added
    python3 tools/sleep-log-to-body.py --write              # actually append
    python3 tools/sleep-log-to-body.py path/to/export.csv --write

What the columns mean: this records what the app observed, not a measurement of sleep.
`minutes_untouched_before_stop` is the useful one — a long untouched stretch before the
timer cut in means you were almost certainly asleep well before the audio stopped.
"""

import argparse
import csv
import os
import sys

DEST = os.path.expanduser('~/Documents/Assets/Body/sources/sleep/audio-sessions.csv')
DEFAULT_SRC = os.path.expanduser('~/Downloads/audio-timer-sessions.csv')

FIELDS = ['started', 'ended', 'listened_minutes', 'timer_minutes', 'stop_reason',
          'track_start', 'track_end', 'stop_position_seconds',
          'minutes_untouched_before_stop']


def read_rows(path):
    with open(path, newline='', encoding='utf-8') as fh:
        rows = list(csv.DictReader(fh))
    missing = [f for f in FIELDS if rows and f not in rows[0]]
    if missing:
        sys.exit('%s is missing expected columns: %s' % (path, ', '.join(missing)))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('source', nargs='?', default=DEFAULT_SRC,
                    help='exported CSV (default: %s)' % DEFAULT_SRC)
    ap.add_argument('--write', action='store_true', help='append instead of previewing')
    args = ap.parse_args()

    if not os.path.exists(args.source):
        sys.exit('No export found at %s — use "Export CSV" in the app first.' % args.source)

    incoming = read_rows(args.source)
    if not incoming:
        sys.exit('%s has no rows.' % args.source)

    existing, seen = [], set()
    if os.path.exists(DEST):
        existing = read_rows(DEST)
        seen = {r['started'] for r in existing}

    fresh = [r for r in incoming if r['started'] not in seen]

    print('source   : %s (%d rows)' % (args.source, len(incoming)))
    print('destination: %s (%d rows)' % (DEST, len(existing)))
    print('new rows : %d' % len(fresh))
    for r in fresh[:5]:
        print('  %s  %s min played  %s timer  %s' %
              (r['started'][:16], r['listened_minutes'], r['timer_minutes'] or '-',
               r['stop_reason']))
    if len(fresh) > 5:
        print('  … and %d more' % (len(fresh) - 5))

    if not fresh:
        return
    if not args.write:
        print('\nNothing written. Re-run with --write to append.')
        return

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    is_new = not os.path.exists(DEST)
    with open(DEST, 'a', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        if is_new:
            w.writeheader()
        for r in sorted(fresh, key=lambda r: r['started']):
            w.writerow({k: r.get(k, '') for k in FIELDS})
    print('\nAppended %d rows to %s' % (len(fresh), DEST))


if __name__ == '__main__':
    main()
