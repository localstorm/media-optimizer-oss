const CACHE = 'media-feed-v1';
const STATIC = ['/favicon.ico', '/favicon-32x32.png', '/logo.png', '/manifest.json'];
const FEED_DATA_KEY = 'feed-data-v1';

self.addEventListener('install', function(e) {
  e.waitUntil(
    caches.open(CACHE).then(function(c) { return c.addAll(STATIC); })
  );
  self.skipWaiting();
});

self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
    })
  );
  self.clients.claim();
});

function fetchAndCache() {
  return fetch('/api/feed')
    .then(function(resp) {
      if (!resp.ok) return;
      return resp.json().then(function(items) {
        return caches.open(CACHE).then(function(c) {
          return c.put(FEED_DATA_KEY, new Response(JSON.stringify(items), {
            headers: { 'Content-Type': 'application/json' }
          }));
        }).then(function() {
          // Notify all open clients so they can update without waiting for their own poll
          return self.clients.matchAll({ type: 'window' }).then(function(clients) {
            clients.forEach(function(client) {
              client.postMessage({ type: 'FEED_UPDATE', items: items });
            });
          });
        });
      });
    })
    .catch(function() { /* network unavailable, skip */ });
}

// Periodic Background Sync — OS-driven refresh while app is closed
self.addEventListener('periodicsync', function(e) {
  if (e.tag === 'feed-refresh') {
    e.waitUntil(fetchAndCache());
  }
});

// On-demand sync triggered by the page (e.g. on open, on visibility change)
self.addEventListener('message', function(e) {
  if (e.data && e.data.type === 'SYNC_FEED') {
    fetchAndCache();
  }
});

self.addEventListener('fetch', function(e) {
  const url = new URL(e.request.url);

  // Never intercept API calls or audio streams — must always be fresh
  if (url.pathname.startsWith('/api/') || url.pathname.endsWith('.mp3')) {
    return;
  }

  // Network-first for the HTML page so feed stays live
  if (url.pathname === '/' || url.pathname === '') {
    e.respondWith(
      fetch(e.request).catch(function() { return caches.match(e.request); })
    );
    return;
  }

  // Cache-first for static assets (logo, icons, manifest)
  e.respondWith(
    caches.match(e.request).then(function(cached) {
      return cached || fetch(e.request).then(function(resp) {
        if (resp.ok) {
          const clone = resp.clone();
          caches.open(CACHE).then(function(c) { c.put(e.request, clone); });
        }
        return resp;
      });
    })
  );
});
