/* Audio Timer service worker — precache the shell so the app opens with no network. */
const CACHE = 'audio-timer-v1';
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];

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

self.addEventListener('fetch', e => {
  const r = e.request;
  if (r.method !== 'GET' || new URL(r.url).origin !== self.location.origin) return;
  e.respondWith(
    caches.match(r, {ignoreSearch: true}).then(hit => {
      if (hit) {
        // Refresh in the background, but always answer instantly from cache.
        fetch(r).then(res => {
          if (res && res.ok) caches.open(CACHE).then(c => c.put(r, res.clone()));
        }).catch(() => {});
        return hit;
      }
      return fetch(r).then(res => {
        if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(r, copy)); }
        return res;
      }).catch(() => caches.match('./index.html'));
    })
  );
});
