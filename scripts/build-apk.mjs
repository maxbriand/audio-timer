/* Build the signed release APK and drop it in ~/Downloads.
 *
 * Assumes `npm run sync` has already refreshed android/app/src/main/assets/public — the `apk`
 * script chains them. Gradle needs a JDK, and the one Android Studio ships with is the one the
 * Android plugin is tested against, so prefer it over whatever `java` happens to be on PATH.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, copyFileSync, statSync, mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ANDROID = join(ROOT, 'android');
const OUT = join(homedir(), 'Downloads', 'audio-timer-standalone.apk');

const STUDIO_JBR = '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
const JAVA_HOME = process.env.JAVA_HOME || (existsSync(STUDIO_JBR) ? STUDIO_JBR : '');
if (!JAVA_HOME) throw new Error('No JDK found — set JAVA_HOME or install Android Studio');

if (!existsSync(join(ANDROID, 'keystore.properties'))) {
  throw new Error(
    'android/keystore.properties is missing, so the build would be unsigned.\n' +
    'It is deliberately not in git. See the "Building the APK" section of README.md.'
  );
}

execFileSync(join(ANDROID, 'gradlew'), ['assembleRelease'], {
  cwd: ANDROID,
  stdio: 'inherit',
  env: { ...process.env, JAVA_HOME, ANDROID_HOME: process.env.ANDROID_HOME || join(homedir(), 'Library/Android/sdk') }
});

const built = join(ANDROID, 'app/build/outputs/apk/release/app-release.apk');
if (!existsSync(built)) throw new Error('Gradle reported success but produced no APK at ' + built);

mkdirSync(dirname(OUT), { recursive: true });
copyFileSync(built, OUT);
console.log(`\n${OUT} — ${(statSync(OUT).size / 1e6).toFixed(1)} MB`);
