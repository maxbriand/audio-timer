/* Audio Timer service worker — precache the shell so the app opens with no network. */
const CACHE = 'audio-timer-v3';
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];
const NET_TIMEOUT = 2500;

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// The page itself is network-first with a short timeout: online, an update lands on the very
// next open instead of the one after; offline, the timeout expires and the cache answers.
async function networkFirst(req){
  const cache = await caches.open(CACHE);
  try {
    const net = await Promise.race([
      fetch(req),
      new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), NET_TIMEOUT))
    ]);
    if (net && net.ok) cache.put(req, net.clone());
    return net;
  } catch(_) {
    return (await cache.match(req, {ignoreSearch:true}))
        || (await cache.match('./index.html'))
        || Response.error();
  }
}

// Everything else is cache-first: icons and the manifest do not change between releases.
async function cacheFirst(req){
  const cache = await caches.open(CACHE);
  const hit = await cache.match(req, {ignoreSearch:true});
  if (hit){
    fetch(req).then(res => { if (res && res.ok) cache.put(req, res.clone()); }).catch(() => {});
    return hit;
  }
  try {
    const net = await fetch(req);
    if (net && net.ok) cache.put(req, net.clone());
    return net;
  } catch(_) {
    return (await cache.match('./index.html')) || Response.error();
  }
}

self.addEventListener('fetch', e => {
  const r = e.request;
  if (r.method !== 'GET' || new URL(r.url).origin !== self.location.origin) return;
  const isPage = r.mode === 'navigate' || r.destination === 'document';
  e.respondWith(isPage ? networkFirst(r) : cacheFirst(r));
});
