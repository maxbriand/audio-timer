/* Assemble www/ — the web assets Capacitor bundles into the APK.
 *
 * The repo root stays the source of truth: it is what GitHub Pages serves, so nothing here
 * may edit those files in place. This copies them into www/ and applies the two changes the
 * native shell needs, leaving the hosted PWA untouched.
 */
import { mkdir, readFile, writeFile, copyFile, rm } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WWW = join(ROOT, 'www');

// sw.js is deliberately absent: inside the APK every asset is already local, so a service
// worker adds nothing and its network-first page fetch would just stall each cold start
// against a localhost origin that serves from the bundle anyway.
const ASSETS = [
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png',
  'icon-maskable-512.png'
];

await rm(WWW, { recursive: true, force: true });
await mkdir(WWW, { recursive: true });

let html = await readFile(join(ROOT, 'index.html'), 'utf8');

// Registering the worker would be harmless but pointless, and it keeps a second cache of the
// app around that can answer with a stale build after an APK upgrade. Drop it in the native
// build only — the hosted PWA still needs it to work offline.
const swLine = /\n[^\n]*serviceWorker[^\n]*register\('\.\/sw\.js'\)[^\n]*\n/;
if (!swLine.test(html)) throw new Error('service-worker registration line not found in index.html');
html = html.replace(swLine, '\n');

await writeFile(join(WWW, 'index.html'), html);
for (const a of ASSETS) await copyFile(join(ROOT, a), join(WWW, a));

console.log(`www/ built — index.html + ${ASSETS.length} assets`);
