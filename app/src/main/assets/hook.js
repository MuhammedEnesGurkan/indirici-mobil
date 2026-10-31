// Movie Track tarayıcı kancası: her sayfa ve çerçevede belge başında çalışır.
// fetch/XHR yanıtlarındaki m3u8/mpd listelerini (gizlenmiş adresler dahil), JSON içindeki akış
// adreslerini ve <video>/<audio> kaynaklarını uygulamaya bildirir.
(() => {
  if (window.__mtHook) return;
  Object.defineProperty(window, '__mtHook', { value: true });
  const bridge = window.MTBridge;
  if (!bridge) return;
  const send = (o) => { try { bridge.postMessage(JSON.stringify(o)); } catch (e) {} };
  const MANIFEST_URL = /\.(m3u8|mpd)(\?|#|$)|[?&/=](m3u8|mpd)([?&/.=]|$)/i;
  const STREAM_IN_TEXT = /https?:\\?\/\\?\/[^"'\s<>]+?\.(?:m3u8|mpd|mp4)(?:\?[^"'\s<>]*)?/g;

  function inspect(url, ct, len, getText, cheap) {
    ct = (ct || '').toLowerCase();
    const hint = /mpegurl|dash\+xml/.test(ct) || MANIFEST_URL.test(url || '');
    const json = ct.includes('json') || ct.includes('javascript');
    const unclear = !ct || ct.startsWith('text/') || ct.includes('octet-stream');
    if (!hint && !json && !(unclear && (cheap || (len && len < 3e6)))) return;
    if (/^(video|audio|image|font)\//.test(ct) && !hint) return;
    if (len && len > 4e6) return;
    let p;
    try { p = Promise.resolve(getText()); } catch (e) { return; }
    p.then((text) => {
      if (!text || typeof text !== 'string') return;
      const head = text.slice(0, 3000).trimStart();
      if (head.startsWith('#EXTM3U')) { send({ t: 'manifest', kind: 'hls', url, text: text.slice(0, 400000) }); return; }
      if (/<MPD[\s>]/.test(head)) { send({ t: 'manifest', kind: 'dash', url, text: text.slice(0, 400000) }); return; }
      if ((json || unclear) && text.length < 3e6) {
        const found = text.match(STREAM_IN_TEXT);
        if (found) send({ t: 'urls', urls: [...new Set(found.map((u) => u.replace(/\\\//g, '/')))].slice(0, 20) });
      }
    }).catch(() => {});
  }

  const of = window.fetch;
  if (of) {
    window.fetch = function (...args) {
      const p = of.apply(this, args);
      p.then((res) => {
        try {
          if (res.type === 'opaque') return;
          inspect(res.url, res.headers.get('content-type'), +res.headers.get('content-length') || 0, () => res.clone().text(), false);
        } catch (e) {}
      }, () => {});
      return p;
    };
  }
  const os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (...a) {
    this.addEventListener('load', () => {
      try {
        const rt = this.responseType;
        if (rt && rt !== 'text' && rt !== 'json') return;
        inspect(this.responseURL, this.getResponseHeader('content-type'), +this.getResponseHeader('content-length') || 0,
          () => (rt === 'json' ? JSON.stringify(this.response) : this.responseText), true);
      } catch (e) {}
    });
    return os.apply(this, a);
  };

  // <video>/<audio> kaynakları ve süreleri
  const seen = new Set();
  function scan() {
    for (const el of document.querySelectorAll('video, audio')) {
      const src = el.currentSrc || el.src;
      const dur = isFinite(el.duration) ? el.duration : 0;
      if (src && /^https?:/.test(src)) {
        const k = src + '|' + Math.round(dur);
        if (!seen.has(k)) { seen.add(k); send({ t: 'media', url: src, kind: el.tagName === 'AUDIO' ? 'audio' : 'video', duration: dur }); }
      } else if (dur) {
        const k = 'player|' + Math.round(dur);
        if (!seen.has(k)) { seen.add(k); send({ t: 'player', duration: dur }); }
      }
      for (const t of el.querySelectorAll('track[src]')) {
        try {
          const u = new URL(t.getAttribute('src'), location.href).href;
          if (!seen.has(u)) { seen.add(u); send({ t: 'media', url: u, kind: 'subtitle', lang: t.srclang || '' }); }
        } catch (e) {}
      }
    }
  }
  for (const ev of ['loadedmetadata', 'durationchange', 'play']) {
    document.addEventListener(ev, (e) => { if (e.target && /^(VIDEO|AUDIO)$/.test(e.target.tagName)) setTimeout(scan, 300); }, true);
  }
  document.addEventListener('DOMContentLoaded', () => setTimeout(scan, 500));
})();
