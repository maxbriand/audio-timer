#!/usr/bin/env python3
"""Cache the Mac's per-day computer time for the daily record's computer column.

macOS Screen Time keeps app-usage intervals in knowledgeC.db, but only ~4 weeks of
them — read directly at diary time, every older row would go blank retroactively as
the system prunes. So this collector runs on every sync and merges the DB's current
window into a small append-only JSON cache (day -> minutes): fresh days overwrite
their cached value, days the DB no longer holds keep the value they had. The cache
is the diary's source; the DB is only ever read here.

Minutes are the UNION of the day's usage intervals, clipped at local midnight —
overlapping app rows are not double-counted, and a session crossing midnight is
split between its two days.

Usage: computer-time.py <cache.json> [knowledgeC.db]
(The DB defaults to the user's own; reading it needs Full Disk Access, which the
sync's /bin/zsh already holds. A missing or unreadable DB leaves the cache as-is.)
"""

import json
import sqlite3
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

APPLE_EPOCH = 978307200          # 2001-01-01 UTC, the reference of ZSTARTDATE

if len(sys.argv) < 2:
    sys.exit("usage: computer-time.py <cache.json> [knowledgeC.db]")
CACHE = Path(sys.argv[1]).expanduser()
DB = (Path(sys.argv[2]).expanduser() if len(sys.argv) > 2
      else Path("~/Library/Application Support/Knowledge/knowledgeC.db").expanduser())


def load_cache():
    try:
        data = json.loads(CACHE.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def usage_intervals():
    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    try:
        return con.execute(
            "SELECT ZSTARTDATE, ZENDDATE FROM ZOBJECT "
            "WHERE ZSTREAMNAME = '/app/usage' "
            "AND ZSTARTDATE IS NOT NULL AND ZENDDATE > ZSTARTDATE").fetchall()
    finally:
        con.close()


def day_minutes(rows):
    per = defaultdict(list)
    for s, e in rows:
        st = datetime.fromtimestamp(s + APPLE_EPOCH)
        en = datetime.fromtimestamp(e + APPLE_EPOCH)
        while st < en:                       # split at local midnight
            nxt = (st + timedelta(days=1)).replace(hour=0, minute=0,
                                                   second=0, microsecond=0)
            cut = min(en, nxt)
            per[st.strftime("%Y-%m-%d")].append((st, cut))
            st = cut
    out = {}
    for day, ivs in per.items():
        ivs.sort()
        total = timedelta()
        cur_s, cur_e = ivs[0]
        for s, e in ivs[1:]:                 # union, not sum: no double-counting
            if s <= cur_e:
                cur_e = max(cur_e, e)
            else:
                total += cur_e - cur_s
                cur_s, cur_e = s, e
        total += cur_e - cur_s
        out[day] = round(total.total_seconds() / 60)
    return out


def main():
    cache = load_cache()
    try:
        fresh = day_minutes(usage_intervals())
    except (sqlite3.Error, OSError) as e:
        sys.exit(f"knowledgeC unreadable ({e}); cache untouched")
    cache.update(fresh)                      # DB wins for its window; older days persist
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(dict(sorted(cache.items())), indent=1) + "\n")
    print(f"computer-time — {len(fresh)} days refreshed, {len(cache)} cached → {CACHE}")


if __name__ == "__main__":
    main()
