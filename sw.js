// Keeps the app usable without network: the network answers when it is quick, otherwise the saved copy does.
const CACHE = 'gymrepris-1';
const FILES = ['./', 'index.html', 'manifest.webmanifest', 'icon.svg'];
const WAIT_MS = 3000;

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(caches.open(CACHE).then(async cache => {
    const saved = await cache.match(req, { ignoreSearch: true });
    const fresh = fetch(req, { cache: 'no-cache' }).then(res => { if (res.ok) cache.put(req, res.clone()); return res; });
    if (!saved) return fresh;
    const late = new Promise(done => setTimeout(() => done(saved), WAIT_MS));
    return Promise.race([fresh.catch(() => saved), late]);
  }));
});
