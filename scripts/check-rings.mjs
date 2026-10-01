/* The ring rules, checked before every APK build (build-apk.mjs runs this first).
 *
 * Every rule here is a bug that reached the phone once (Redmi Note 10S, MIUI 14):
 *   1. No ongoing ring. MIUI removes an ongoing notification from this app the instant it is
 *      posted, and the full-screen screen that rings goes with it (2026-10-01: fatigue checks
 *      fired on time and never rang). setOngoing(true) is allowed only in a file that runs a
 *      foreground service (startForeground) — that notification is the service's, not a ring.
 *   2. Rings are built and posted by Ring.java only: an alarm category or a full-screen intent
 *      anywhere else is a ring that skips the rules. ShakeService's silent screen-wake is the
 *      one listed exception.
 *   3. Every notification id is unique: two rings sharing one cancel each other (the
 *      melatonin reminder used to clear a ringing fatigue check).
 * Fails the build with the file and the rule; nothing to configure.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = process.env.CHECK_RINGS_SRC || join(dirname(fileURLToPath(import.meta.url)), '..',
  'android', 'app', 'src', 'main', 'java', 'com', 'maxbriand', 'audiotimer');
const RING_FILES = new Set(['Ring.java']);
const FULL_SCREEN_OK = new Set(['Ring.java', 'ShakeService.java']);   // the silent shake wake-up

const errors = [];
const ids = new Map();          // id -> "File.java NAME"
for (const f of readdirSync(SRC).filter(n => n.endsWith('.java'))){
  const code = readFileSync(join(SRC, f), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  if (/setOngoing\(\s*true\s*\)/.test(code) && !/startForeground\(/.test(code))
    errors.push(`${f}: setOngoing(true) on a notification that is not a foreground service — MIUI drops it. Use Ring.builder().`);
  if (!FULL_SCREEN_OK.has(f) && /CATEGORY_ALARM/.test(code))
    errors.push(`${f}: builds an alarm notification itself — build it with Ring.builder() and post it with Ring.post().`);
  if (!FULL_SCREEN_OK.has(f) && /setFullScreenIntent\(/.test(code) && !/Ring\.builder\(/.test(code))
    errors.push(`${f}: full-screen intent outside Ring — a ring must go through Ring.builder()/Ring.post().`);
  for (const m of code.matchAll(/static\s+final\s+int\s+(\w*(?:NOTIF\w*ID|ID_\w+))\s*=\s*(\d+)\s*;/g)){
    const where = `${f} ${m[1]}`;
    if (ids.has(m[2])) errors.push(`notification id ${m[2]} used twice: ${ids.get(m[2])} and ${where}. Take a free one in Ring.java.`);
    else ids.set(m[2], where);
  }
}
if (errors.length){
  console.error('check-rings: the APK would ship a ring that may not ring.\n  - ' + errors.join('\n  - '));
  process.exit(1);
}
console.log(`check-rings: ok — ${ids.size} notification ids, all unique; no ongoing ring`);
