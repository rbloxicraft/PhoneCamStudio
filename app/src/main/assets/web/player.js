/*
 * PhoneCam — lecteur H.264 basse latence (WebCodecs, contexte sécurisé requis : localhost/https).
 * Le flux arrive par WebSocket, une access unit Annex-B par message, et chaque image décodée
 * est dessinée immédiatement (aucun tampon de lecture).
 *
 * initPlayer(canvas, host, port, { onFrame, onOpen, onClose, onFail }) → { stop() }
 */
function initPlayer(canvas, host, port, opts) {
  opts = opts || {};
  var ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
  var START = new Uint8Array([0, 0, 0, 1]);
  var decoder = null, codec = null, sps = null, pps = null;
  var waitKey = true, ts = 0, ws = null, stopped = false, everOpened = false, attempts = 0, errors = 0;

  if (!('VideoDecoder' in window) || !window.isSecureContext) {
    setTimeout(function () { opts.onFail && opts.onFail(new Error('WebCodecs indisponible')); }, 0);
    return { stop: function () {} };
  }

  function hex(b) { return (b < 16 ? '0' : '') + b.toString(16); }

  /** NAL units sans leur start code (n[0] = en-tête NAL). */
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

  function concat(parts) {
    var len = 0, k, off = 0;
    for (k = 0; k < parts.length; k++) len += parts[k].length;
    var out = new Uint8Array(len);
    for (k = 0; k < parts.length; k++) { out.set(parts[k], off); off += parts[k].length; }
    return out;
  }

  function reset() {
    if (decoder) { try { decoder.close(); } catch (e) {} }
    decoder = null; codec = null; waitKey = true;
  }

  function draw(frame) {
    var w = frame.displayWidth, h = frame.displayHeight;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    ctx.drawImage(frame, 0, 0);
    frame.close();
    errors = 0;
    opts.onFrame && opts.onFrame();
  }

  function ensureDecoder() {
    var c = 'avc1.' + hex(sps[1]) + hex(sps[2]) + hex(sps[3]);
    if (decoder && codec === c && decoder.state === 'configured') return;
    reset();
    decoder = new VideoDecoder({
      output: draw,
      error: function (e) {
        reset();
        if (++errors >= 3) fail(e);
      }
    });
    // Pas de « description » : les images restent en Annex-B, SPS/PPS sont injectés devant les keyframes
    decoder.configure({ codec: c, optimizeForLatency: true });
    codec = c;
  }

  function onAccessUnit(d) {
    var nals = splitNals(d), key = false, slice = false, ps = false;
    for (var k = 0; k < nals.length; k++) {
      var t = nals[k][0] & 31;
      if (t === 7) { sps = nals[k].slice(); ps = true; }
      else if (t === 8) { pps = nals[k].slice(); ps = true; }
      else if (t === 5) { key = true; slice = true; }
      else if (t === 1) slice = true;
    }
    if (!slice || !sps || !pps) return; // SPS/PPS seuls : mémorisés pour le prochain keyframe
    ensureDecoder();
    if (waitKey && !key) return;
    if (key) waitKey = false;
    var data = key && !ps ? concat([START, sps, START, pps, d]) : d;
    ts += 33333;
    try {
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data: data }));
    } catch (e) { reset(); }
  }

  function fail(e) {
    if (stopped) return;
    stopped = true;
    try { ws && ws.close(); } catch (x) {}
    reset();
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
  return { stop: function () { stopped = true; try { ws && ws.close(); } catch (x) {} reset(); } };
}
