/*
 * PhoneCam — lecteur H.264 par Media Source Extensions.
 * Fonctionne en http sur le réseau local (contrairement à WebCodecs) et dans OBS.
 * Chaque access unit reçue par WebSocket est emballée en fragment MP4 (moof+mdat) et ajoutée
 * à un <video> ; la lecture est maintenue au plus près du direct (léger accéléré, puis saut).
 * Débit ~6× plus faible que l'aperçu JPEG, décodage matériel côté PC.
 *
 * initMse(video, host, port, { onFrame, onOpen, onClose, onFail }) → { stop() }
 */
function initMse(video, host, port, opts) {
  opts = opts || {};
  var TIMESCALE = 90000, FRAME = 3000; // 30 i/s
  var sps = null, pps = null, ms = null, sb = null, mime = '';
  var queue = [], seq = 1, dts = 0, waitKey = true;
  var ws = null, stopped = false, everOpened = false, attempts = 0, lastTrim = 0;

  if (!window.MediaSource || !window.WebSocket) {
    setTimeout(function () { fail(new Error('MSE indisponible')); }, 0);
    return { stop: function () {} };
  }

  /* ---------------------------------------------------------- octets */
  function u8(n) { return [n & 255]; }
  function u16(n) { return [(n >> 8) & 255, n & 255]; }
  function u32(n) { return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]; }
  function str(s) { var a = []; for (var i = 0; i < s.length; i++) a.push(s.charCodeAt(i)); return a; }
  function zeros(n) { var a = []; while (n--) a.push(0); return a; }
  function cat(parts) {
    var len = 0, i, off = 0;
    for (i = 0; i < parts.length; i++) len += parts[i].length;
    var out = new Uint8Array(len);
    for (i = 0; i < parts.length; i++) { out.set(parts[i], off); off += parts[i].length; }
    return out;
  }
  function box(type) {
    var parts = [new Uint8Array(8)], i, len = 8;
    for (i = 1; i < arguments.length; i++) {
      var p = arguments[i]; p = p instanceof Uint8Array ? p : new Uint8Array(p);
      parts.push(p); len += p.length;
    }
    var out = cat(parts);
    out.set(u32(len), 0); out.set(str(type), 4);
    return out;
  }
  function hex(b) { return (b < 16 ? '0' : '') + b.toString(16); }

  /* ------------------------------------------------- segment d'init */
  function initSegment(w, h) {
    var matrix = [].concat(u32(0x10000), u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000));
    var avcC = box('avcC', [1, sps[1], sps[2], sps[3], 0xFF, 0xE1].concat(u16(sps.length)), sps, [1].concat(u16(pps.length)), pps);
    var avc1 = box('avc1',
      zeros(6), u16(1), zeros(16), u16(w), u16(h), u32(0x480000), u32(0x480000), u32(0), u16(1),
      zeros(32), u16(0x18), u16(0xFFFF), avcC);
    var stbl = box('stbl',
      box('stsd', u32(0), u32(1), avc1),
      box('stts', u32(0), u32(0)), box('stsc', u32(0), u32(0)),
      box('stsz', u32(0), u32(0), u32(0)), box('stco', u32(0), u32(0)));
    var minf = box('minf',
      box('vmhd', u32(1), zeros(8)),
      box('dinf', box('dref', u32(0), u32(1), box('url ', u32(1)))),
      stbl);
    var mdia = box('mdia',
      box('mdhd', u32(0), u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55C4), u16(0)),
      box('hdlr', u32(0), u32(0), str('vide'), zeros(12), str('PhoneCam'), [0]),
      minf);
    var trak = box('trak',
      box('tkhd', u32(3), u32(0), u32(0), u32(1), u32(0), u32(0), zeros(8), u16(0), u16(0), u16(0), u16(0),
        matrix, u32(w * 65536), u32(h * 65536)),
      mdia);
    var moov = box('moov',
      box('mvhd', u32(0), u32(0), u32(0), u32(1000), u32(0), u32(0x10000), u16(0x100), zeros(10), matrix, zeros(24), u32(2)),
      trak,
      box('mvex', box('trex', u32(0), u32(1), u32(1), u32(0), u32(0), u32(0))));
    return cat([box('ftyp', str('isom'), u32(0x200), str('isom'), str('iso2'), str('avc1'), str('mp41')), moov]);
  }

  /* -------------------------------------------- fragment d'une image */
  function mediaSegment(sample, key) {
    var hi = Math.floor(dts / 4294967296), lo = dts >>> 0;
    var flags = key ? 0x02000000 : 0x01010000;
    var trun = box('trun', u32(0x000701), u32(1), u32(0), u32(FRAME), u32(sample.length), u32(flags));
    var moof = box('moof',
      box('mfhd', u32(0), u32(seq++)),
      box('traf', box('tfhd', u32(0x020000), u32(1)), box('tfdt', u32(0x01000000), u32(hi), u32(lo)), trun));
    // data_offset (relatif au début du moof) = taille du moof + en-tête mdat
    // trun (dernière boîte du moof) = 32 octets ; data_offset en est l'octet 16 → 16 octets avant la fin
    var off = moof.length + 8, pos = moof.length - 16;
    moof.set(u32(off), pos);
    dts += FRAME;
    return cat([moof, box('mdat', sample)]);
  }

  /** Annex-B → NAL units (n[0] = en-tête). */
  function splitNals(d) {
    var out = [], i = 0, start = -1, n = d.length;
    while (i + 2 < n) {
      if (d[i] === 0 && d[i + 1] === 0 && (d[i + 2] === 1 || (d[i + 2] === 0 && i + 3 < n && d[i + 3] === 1))) {
        var sc = d[i + 2] === 1 ? 3 : 4;
        if (start >= 0) out.push(d.subarray(start, i));
        start = i + sc; i = start;
      } else i++;
    }
    if (start >= 0 && start < n) out.push(d.subarray(start));
    return out;
  }

  /* ------------------------------------------------------- lecture */
  function setup() {
    mime = 'video/mp4; codecs="avc1.' + hex(sps[1]) + hex(sps[2]) + hex(sps[3]) + '"';
    if (!MediaSource.isTypeSupported(mime)) { fail(new Error('H.264 non supporté : ' + mime)); return false; }
    ms = new MediaSource();
    video.muted = true; video.autoplay = true; video.playsInline = true;
    video.src = URL.createObjectURL(ms);
    ms.addEventListener('sourceopen', function () {
      try {
        sb = ms.addSourceBuffer(mime);
        sb.mode = 'segments';
        sb.addEventListener('updateend', onUpdateEnd);
        sb.addEventListener('error', function () { fail(new Error('SourceBuffer')); });
        queue.unshift(initSegment(opts.width || 1920, opts.height || 1080));
        flush();
      } catch (e) { fail(e); }
    }, { once: true });
    return true;
  }

  function flush() {
    if (!sb || sb.updating || !queue.length || stopped) return;
    var data = queue.length === 1 ? queue[0] : cat(queue);
    queue = [];
    try { sb.appendBuffer(data); } catch (e) {
      if (e.name === 'QuotaExceededError') trim(true); else fail(e);
    }
  }

  function trim(force) {
    var b = video.buffered;
    if (!b.length || sb.updating) return false;
    var now = performance.now();
    if (!force && now - lastTrim < 5000) return false;
    var cut = video.currentTime - 3;
    if (cut > b.start(0) + 1) { lastTrim = now; sb.remove(b.start(0), cut); return true; }
    return false;
  }

  /** Reste au bord du direct : accélère légèrement, saute si trop en retard. */
  function chase() {
    var b = video.buffered;
    if (!b.length) return;
    var end = b.end(b.length - 1), t = video.currentTime;
    var inRange = false;
    for (var i = 0; i < b.length; i++) if (t >= b.start(i) - 0.01 && t <= b.end(i) + 0.01) inRange = true;
    var lag = end - t;
    if (!inRange || lag > 0.5) {
      video.currentTime = Math.max(b.start(b.length - 1), end - 0.04);
    } else {
      video.playbackRate = lag > 0.12 ? 1.08 : 1.0;
    }
    if (video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); }
  }

  function onUpdateEnd() {
    if (stopped) return;
    chase();
    if (!trim(false)) flush();
  }

  function onAccessUnit(d) {
    var nals = splitNals(d), key = false, slice = false, parts = [];
    for (var k = 0; k < nals.length; k++) {
      var n = nals[k], t = n[0] & 31;
      if (t === 7) sps = n.slice();
      else if (t === 8) pps = n.slice();
      else if (t === 9) continue; // délimiteur d'AU
      else {
        if (t === 5) key = true;
        if (t === 1 || t === 5) slice = true;
        parts.push(new Uint8Array(u32(n.length)), n);
      }
    }
    if (!slice || !sps || !pps) return;
    if (waitKey && !key) return;
    waitKey = false;
    if (!ms && !setup()) return;
    queue.push(mediaSegment(cat(parts), key));
    if (queue.length > 30) { queue = queue.slice(-1); } // ne devrait pas arriver : on reste au direct
    flush();
  }

  if (video.requestVideoFrameCallback) {
    var tick = function () { if (stopped) return; opts.onFrame && opts.onFrame(); video.requestVideoFrameCallback(tick); };
    video.requestVideoFrameCallback(tick);
  } else {
    video.addEventListener('timeupdate', function () { opts.onFrame && opts.onFrame(); });
  }
  video.addEventListener('error', function () { fail(new Error('vidéo')); });

  function fail(e) {
    if (stopped) return;
    stopped = true;
    try { ws && ws.close(); } catch (x) {}
    opts.onFail && opts.onFail(e);
  }

  function connect() {
    if (stopped) return;
    attempts++;
    ws = new WebSocket('ws://' + host + ':' + port + '/ws');
    ws.binaryType = 'arraybuffer';
    ws.onopen = function () { everOpened = true; attempts = 0; waitKey = true; opts.onOpen && opts.onOpen(); };
    ws.onmessage = function (e) { try { onAccessUnit(new Uint8Array(e.data)); } catch (x) {} };
    ws.onclose = function () {
      if (stopped) return;
      opts.onClose && opts.onClose();
      if (!everOpened && attempts >= 3) { fail(new Error('serveur H.264 injoignable')); return; }
      setTimeout(connect, 700);
    };
    ws.onerror = function () { try { ws.close(); } catch (x) {} };
  }

  connect();
  return {
    stop: function () { stopped = true; try { ws && ws.close(); } catch (x) {} },
    snapshotSource: video
  };
}
