/* Lock In service worker: makes the app open offline after the first visit.
 * Generated at build time (vite.config.ts) with the list of built files. */
const VERSION = '__VERSION__';
const CACHE = `lockin-${VERSION}`;
const PRECACHE = __PRECACHE__;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE.map((path) => new Request(path, { cache: 'reload' }))))
      .then(async () => {
        // First install: take over straight away. Updates wait for the page to say so.
        const clients = await self.clients.matchAll({ includeUncontrolled: true });
        if (clients.length === 0 || !(await caches.keys()).some((k) => k.startsWith('lockin-') && k !== CACHE)) await self.skipWaiting();
      }),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('lockin-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  // Firebase and anything else off-site goes straight to the network.
  if (url.origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    // The app shell: cached copy first so it opens instantly and offline.
    event.respondWith(
      caches.open(CACHE).then(async (cache) => (await cache.match('./')) ?? fetch(request).catch(() => Response.error())),
    );
    return;
  }

  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const hit = await cache.match(request, { ignoreSearch: true });
      if (hit) return hit;
      const response = await fetch(request);
      if (response.ok && response.type === 'basic') cache.put(request, response.clone());
      return response;
    }),
  );
});
