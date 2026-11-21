/*
 * PhoneCam — aperçu JPEG poussé par WebSocket (fonctionne en http sur le réseau local).
 * Le téléphone envoie toujours l'image la plus récente ; ici on ne garde qu'une image en
 * attente pendant le décodage (fait hors du thread principal) → jamais de retard cumulé.
 * Repli automatique sur /frame en long-poll si le WebSocket est bloqué.
 *
 * initJpeg(canvas, { onFrame, onOpen, onClose }) → { stop() }
 */
function initJpeg(canvas, opts) {
  opts = opts || {};
  var ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
  var busy = false, pending = null, ws = null, stopped = false, wsFails = 0, polling = false;

  function show(blob) {
    busy = true;
    createImageBitmap(blob).then(function (bmp) {
      if (canvas.width !== bmp.width || canvas.height !== bmp.height) {
        canvas.width = bmp.width; canvas.height = bmp.height;
      }
      ctx.drawImage(bmp, 0, 0);
      bmp.close();
      opts.onFrame && opts.onFrame();
    }).catch(function () {}).then(function () {
      busy = false;
      if (pending) { var p = pending; pending = null; show(p); }
    });
  }

  function onBlob(b) { if (busy) pending = b; else show(b); }

  function connect() {
    if (stopped) return;
    var opened = false;
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    ws.binaryType = 'blob';
    ws.onopen = function () { opened = true; wsFails = 0; opts.onOpen && opts.onOpen(); };
    ws.onmessage = function (e) { if (e.data && e.data.size) onBlob(e.data); };
    ws.onclose = function () {
      if (stopped) return;
      opts.onClose && opts.onClose();
      if (!opened && ++wsFails >= 3) { poll(); return; }
      setTimeout(connect, 700);
    };
    ws.onerror = function () { try { ws.close(); } catch (x) {} };
  }

  function poll() {
    if (polling) return;
    polling = true;
    var seq = -1;
    (function loop() {
      if (stopped) return;
      fetch('/frame?after=' + seq, { cache: 'no-store' }).then(function (r) {
        var s = parseInt(r.headers.get('X-Frame'), 10);
        if (!isNaN(s)) seq = s;
        return r.status === 200 ? r.blob() : null;
      }).then(function (b) {
        if (b && b.size) onBlob(b);
        loop();
      }).catch(function () { setTimeout(loop, 800); });
    })();
  }

  connect();
  return { stop: function () { stopped = true; try { ws && ws.close(); } catch (x) {} } };
}
