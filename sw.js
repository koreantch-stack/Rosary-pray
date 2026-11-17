// 화면 파일은 온라인이면 항상 최신으로 받고(수정 즉시 반영), 오프라인이면 저장해 둔 것을 씁니다.
// 성화(위키미디어)와 글꼴은 한 번 받으면 기기에 보관해 인터넷 없이도 보이게 합니다.
const CACHE = 'rosary-v1';
const IMG = 'rosary-img-v1';
const FONT = 'rosary-font-v1';
const SHELL = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  const keep = [CACHE, IMG, FONT];
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => !keep.includes(k)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // 성화: 보관해 둔 것이 있으면 그것을, 없으면 받아서 보관
  if (url.hostname === 'commons.wikimedia.org' || url.hostname === 'upload.wikimedia.org') {
    e.respondWith(caches.open(IMG).then(async (c) => {
      const hit = await c.match(url.href);
      if (hit) return hit;
      let res;
      try { res = await fetch(url.href, { mode: 'cors' }); }
      catch (_) { res = await fetch(req); }
      if (res.ok) c.put(url.href, res.clone());
      return res;
    }));
    return;
  }

  // 글꼴: 보관본을 먼저 쓰고 뒤에서 새로 받아 둠
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open(FONT).then(async (c) => {
      const hit = await c.match(req);
      const net = fetch(req).then((r) => { if (r.ok || r.type === 'opaque') c.put(req, r.clone()); return r; }).catch(() => null);
      return hit || (await net) || Response.error();
    }));
    return;
  }

  if (url.origin !== location.origin) return;

  e.respondWith(
    fetch(req, { cache: 'no-cache' })
      .then((res) => {
        if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
        return res;
      })
      .catch(() => caches.match(req, { ignoreSearch: true }).then((hit) => hit || (req.mode === 'navigate' ? caches.match('index.html') : Response.error())))
  );
});
