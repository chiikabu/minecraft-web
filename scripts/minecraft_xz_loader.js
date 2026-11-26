// minecraft-web by chiikabu (https://github.com/chiikabu)

globalThis.__minecraftHTML85Decode = function (text) {
  var digits = globalThis.__minecraftHTML85Decode._t;
  if (!digits) {
    var alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;.=>?@^_`{|}~';
    digits = new Int16Array(128);
    digits.fill(-1);
    for (var i = 0; i < alphabet.length; i++) { digits[alphabet.charCodeAt(i)] = i; }
    globalThis.__minecraftHTML85Decode._t = digits;
  }
  if (/[\t\n\f\r ]/.test(text)) { text = text.replace(/[\t\n\f\r ]/g, ''); }
  if (text.length % 5 === 1) { throw new Error('Invalid base85 payload length'); }
  var bytes = new Uint8Array(Math.floor(text.length * 4 / 5)), cursor = 0;
  for (var offset = 0; offset < text.length; offset += 5) {
    var value = 0;
    for (var j = 0; j < 5; j++) {
      var code = offset + j < text.length ? text.charCodeAt(offset + j) : -1;
      var digit = code < 0 ? 84 : (code < 128 ? digits[code] : -1);
      if (!(digit >= 0)) { throw new Error('Invalid base85 payload character'); }
      value = value * 85 + digit;
    }
    if (value > 0xFFFFFFFF) { throw new Error('Base85 payload overflow'); }
    for (var k = 0; k < 4 && cursor < bytes.length; k++) { bytes[cursor++] = value >>> (24 - k * 8); }
  }
  return bytes;
};

globalThis.__minecraftBase122Decode = function (text) {
  var len = text.length;
  if (!len) { return new Uint8Array(0); }
  var tbl = globalThis.__minecraftBase122Decode._t;
  if (!tbl) {
    tbl = new Uint8Array(128);
    tbl[0] = 1; tbl[10] = 1; tbl[13] = 1; tbl[34] = 1; tbl[38] = 1; tbl[60] = 1; tbl[92] = 1;
    globalThis.__minecraftBase122Decode._t = tbl;
  }
  var illegal = [0, 10, 13, 34, 38, 60, 92];
  var cap = ((len * 15) >>> 4) + 16;
  var out = new Uint8Array(cap), w = 0, bits = 0, nbits = 0;
  for (var p = 0; p < len; p++) {
    if (w + 2 > out.length) {
      var next = new Uint8Array(Math.min(Math.floor(len * 7 / 4) + 4, out.length * 2));
      next.set(out.subarray(0, w));
      out = next;
    }
    var c = text.charCodeAt(p);
    if (c < 128) {
      if (tbl[c]) { throw new Error('Invalid base122 payload character'); }
      bits = (bits << 7) | c;
      nbits += 7;
      if (nbits >= 8) {
        nbits -= 8;
        out[w++] = (bits >>> nbits) & 255;
        bits &= (1 << nbits) - 1;
      }
    } else if (c >= 0x0100 && c <= 0x04FF) {
      var v = c - 0x0100, tag = v >>> 7, lo = v & 127;
      if (tag < 7) {
        bits = (bits << 14) | (illegal[tag] << 7) | lo;
        nbits += 14;
        nbits -= 8; out[w++] = (bits >>> nbits) & 255;
        if (nbits >= 8) { nbits -= 8; out[w++] = (bits >>> nbits) & 255; }
        bits &= (1 << nbits) - 1;
      } else {
        if (p !== len - 1) { throw new Error('Invalid base122 shortened marker position'); }
        bits = (bits << 7) | lo;
        nbits += 7;
        if (nbits >= 8) {
          nbits -= 8;
          out[w++] = (bits >>> nbits) & 255;
          bits &= (1 << nbits) - 1;
        }
      }
    } else {
      throw new Error('Invalid base122 payload character');
    }
  }
  if (nbits === 7) { throw new Error('Invalid base122 payload length'); }
  if (nbits > 0 && bits !== 0) { throw new Error('Invalid base122 padding bits'); }
  return out.subarray(0, w);
};

globalThis.__minecraftXmlUtf8Decode = function (text) {
  var len = text.length;
  if (!len) { return new Uint8Array(0); }
  var tbl = globalThis.__minecraftXmlUtf8Decode._t;
  if (!tbl) {
    tbl = new Int16Array(128);
    tbl.fill(-1);
    var idx = 0;
    tbl[9] = idx++;
    for (var ch = 32; ch < 127; ch++) {
      if (ch === 38 || ch === 60 || ch === 62) { continue; }
      tbl[ch] = idx++;
    }
    globalThis.__minecraftXmlUtf8Decode._t = tbl;
  }
  var cap = Math.floor(len * 1.15) + 32;
  var out = new Uint8Array(cap), w = 0, bits = 0, nbits = 0;
  for (var p = 0; p < len; p++) {
    if (w + 3 > out.length) {
      var next = new Uint8Array(Math.min(Math.floor(len * 5 / 2) + 8, out.length * 2));
      next.set(out.subarray(0, w));
      out = next;
    }
    var c = text.charCodeAt(p);
    if (c < 128) {
      var v = tbl[c];
      if (v < 0) { throw new Error('Invalid xml-utf8 payload character'); }
      bits = (bits << 7) | v;
      nbits += 7;
      if (nbits >= 8) {
        nbits -= 8;
        out[w++] = (bits >>> nbits) & 255;
        bits &= (1 << nbits) - 1;
      }
    } else if (c >= 0x00A0 && c <= 0x07DF) {
      var d2 = c - 0x00A0;
      bits = (bits << 13) | ((93 + (d2 >>> 6)) << 6) | (d2 & 63);
      nbits += 13;
      nbits -= 8; out[w++] = (bits >>> nbits) & 255;
      if (nbits >= 8) { nbits -= 8; out[w++] = (bits >>> nbits) & 255; }
      bits &= (1 << nbits) - 1;
    } else if (c >= 0x0800 && c <= 0xC7FF) {
      var d3 = c - 0x0800;
      bits = (bits << 20) | ((122 + (d3 >>> 13)) << 13) | (d3 & 8191);
      nbits += 20;
      nbits -= 8; out[w++] = (bits >>> nbits) & 255;
      nbits -= 8; out[w++] = (bits >>> nbits) & 255;
      if (nbits >= 8) { nbits -= 8; out[w++] = (bits >>> nbits) & 255; }
      bits &= (1 << nbits) - 1;
    } else if (c >= 0xC800 && c <= 0xC8FF) {
      bits = (bits << 8) | (c - 0xC800);
      nbits += 8;
      nbits -= 8; out[w++] = (bits >>> nbits) & 255;
      bits &= (1 << nbits) - 1;
    } else if (c >= 0xC900 && c <= 0xCC7F) {
      var dt = c - 0xC900, k = (dt >>> 7) + 1, vt = dt & 127;
      if (vt >= (1 << k) || p !== len - 1) { throw new Error('Invalid xml-utf8 tail character'); }
      bits = (bits << k) | vt;
      nbits += k;
      if (nbits >= 8) {
        nbits -= 8;
        out[w++] = (bits >>> nbits) & 255;
        bits &= (1 << nbits) - 1;
      }
    } else {
      throw new Error('Invalid xml-utf8 payload character');
    }
  }
  if (nbits !== 0) { throw new Error('Invalid xml-utf8 payload length'); }
  return out.subarray(0, w);
};

globalThis.__minecraftPayloadDecode = function (el, explicitEnc) {
  var text, enc;
  if (typeof el === 'string') {
    text = el;
    enc = explicitEnc || 'h85';
  } else {
    if (!el) { throw new Error('Missing payload element'); }
    text = el.textContent || '';
    enc = explicitEnc || (el.getAttribute && el.getAttribute('data-enc')) || 'h85';
  }
  if (enc === 'b122') { return globalThis.__minecraftBase122Decode(text); }
  if (enc === 'xutf') { return globalThis.__minecraftXmlUtf8Decode(text); }
  if (enc === 'h85') { return globalThis.__minecraftHTML85Decode(text); }
  throw new Error('Unknown payload encoding: ' + enc);
};

globalThis.__minecraftArchiveUnpack = function (raw) {
  raw = raw instanceof Uint8Array ? raw : new Uint8Array(raw);
  var td = new TextDecoder('utf-8', {fatal: true});
  if (raw.length < 12 || td.decode(raw.subarray(0, 8)) !== 'MCXZ001\0') {
    throw new Error('Invalid XZ archive magic');
  }
  var view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  var count = view.getUint32(8, true), cursor = 12, spans = [], decoded = Object.create(null);
  if (count > (raw.length - 12) / 12) { throw new Error('Invalid XZ archive count'); }
  for (var i = 0; i < count; i++) {
    if (cursor + 12 > raw.length) { throw new Error('Truncated XZ archive record'); }
    var nameLen = view.getUint16(cursor, true), reserved = view.getUint16(cursor + 2, true);
    var offset = view.getUint32(cursor + 4, true), length = view.getUint32(cursor + 8, true);
    cursor += 12;
    if (reserved || offset + length > raw.length || cursor + nameLen > raw.length) {
      throw new Error('Invalid XZ archive record');
    }
    var name = td.decode(raw.subarray(cursor, cursor + nameLen));
    cursor += nameLen;
    if (Object.prototype.hasOwnProperty.call(decoded, name)) { throw new Error('Duplicate XZ archive name'); }
    decoded[name] = raw.subarray(offset, offset + length);
    spans.push([offset, length]);
  }
  for (var s = 0; s < spans.length; s++) {
    if (spans[s][0] !== cursor) { throw new Error('Overlapping or noncontiguous XZ payload'); }
    cursor += spans[s][1];
  }
  if (cursor !== raw.length) { throw new Error('Trailing XZ archive bytes'); }
  return decoded;
};

globalThis.__minecraftXZRawDecode = async function (input, wasmInput) {
  input = input instanceof Uint8Array ? input : new Uint8Array(input);
  var td = new TextDecoder('utf-8', {fatal: true});
  var magic = input.length >= 16 ? td.decode(input.subarray(0, 8)) : '';
  if (magic !== 'MCXC001\0' && magic !== 'MCGC001\0') {
    throw new Error('Invalid compressed container header');
  }
  var header = new DataView(input.buffer, input.byteOffset, input.byteLength);
  var rawSize = header.getUint32(8, true), packedSize = header.getUint32(12, true);
  if (rawSize < 12 || packedSize !== input.length - 16 || rawSize + packedSize > 1073741824) {
    throw new Error('Invalid or oversized compressed container');
  }
  var raw;
  if (magic === 'MCGC001\0') {
    if (typeof DecompressionStream === 'undefined') { throw new Error('DecompressionStream unavailable'); }
    raw = new Uint8Array(rawSize);
    var stream = new ReadableStream({start: function (controller) {
      controller.enqueue(input.subarray(16)); controller.close();
    }});
    var reader = stream.pipeThrough(new DecompressionStream('gzip')).getReader();
    var written = 0;
    try {
      while (true) {
        var chunk = await reader.read();
        if (chunk.done) { break; }
        if (chunk.value.length > rawSize - written) { throw new Error('Gzip output exceeds declared length'); }
        raw.set(chunk.value, written);
        written += chunk.value.length;
      }
      if (written !== rawSize) { throw new Error('Gzip output shorter than declared length'); }
    } catch (error) {
      await reader.cancel(error).catch(function () {});
      throw error;
    }
  } else {
    var loaded = await WebAssembly.instantiate(wasmInput, {}), exports = loaded.instance.exports;
    exports._initialize();
    var pointer = exports.malloc(rawSize + packedSize) >>> 0;
    if (!pointer) { throw new Error('Cannot allocate XZ decoder buffer'); }
    new Uint8Array(exports.memory.buffer).set(input.subarray(16), pointer);
    var status = exports.decode(pointer, packedSize, pointer + packedSize, rawSize);
    if (status !== 1) { throw new Error('XZ payload is corrupt or unsupported, code ' + status); }
    raw = new Uint8Array(exports.memory.buffer, pointer + packedSize, rawSize);
  }
  return raw;
};

globalThis.__minecraftXZDecode = async function (input, wasmInput) {
  var raw = await globalThis.__minecraftXZRawDecode(input, wasmInput);
  return globalThis.__minecraftArchiveUnpack(raw);
};

globalThis.__minecraftParallelUnpack = function (wasmEl, restEl, loaderInput, onStage) {
  function extract(el) {
    if (!el) { throw new Error('Missing offline payload element'); }
    var text = typeof el.text === 'string' ? el.text : (el.textContent || '');
    var enc = el.enc || (el.getAttribute && el.getAttribute('data-enc')) || 'h85';
    if (el.parentNode) { el.parentNode.removeChild(el); }
    return { text: text, enc: enc };
  }
  function decodeLoader(src) {
    if (src instanceof Uint8Array) { return src; }
    var s = String(src || '').replace(/\s+/g, '');
    var out = new Uint8Array(((s.length / 4) * 3) | 0), n = 0, CH = 4 * 65536;
    for (var i = 0; i < s.length; i += CH) {
      var part = atob(s.substr(i, CH));
      for (var j = 0; j < part.length; j++) { out[n++] = part.charCodeAt(j); }
    }
    return out.subarray(0, n);
  }
  var wasmSpec = extract(wasmEl);
  var restSpec = extract(restEl);
  var loaderBytes = decodeLoader(loaderInput);

  async function mainThreadOne(spec, kind) {
    var packed = globalThis.__minecraftPayloadDecode(spec.text, spec.enc);
    spec.text = null;
    var raw = await globalThis.__minecraftXZRawDecode(packed, loaderBytes);
    packed = null;
    if (kind === 'wasm') {
      var entries = globalThis.__minecraftArchiveUnpack(raw);
      if (!entries.wasm) { throw new Error('p-wasm archive missing wasm entry'); }
      return entries.wasm.slice();
    }
    var rawCopy = raw.slice();
    globalThis.__minecraftArchiveUnpack(rawCopy);
    return rawCopy;
  }

  function makeWorkerUrl() {
    if (typeof Worker !== 'function' || typeof Blob !== 'function' || typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
      return null;
    }
    var src = [
      'globalThis.__minecraftHTML85Decode = ' + globalThis.__minecraftHTML85Decode.toString() + ';',
      'globalThis.__minecraftBase122Decode = ' + globalThis.__minecraftBase122Decode.toString() + ';',
      'globalThis.__minecraftXmlUtf8Decode = ' + globalThis.__minecraftXmlUtf8Decode.toString() + ';',
      'globalThis.__minecraftPayloadDecode = ' + globalThis.__minecraftPayloadDecode.toString() + ';',
      'globalThis.__minecraftArchiveUnpack = ' + globalThis.__minecraftArchiveUnpack.toString() + ';',
      'globalThis.__minecraftXZRawDecode = ' + globalThis.__minecraftXZRawDecode.toString() + ';',
      'self.onmessage = async function (e) {',
      '  try {',
      '    var d = e.data;',
      '    var packed = globalThis.__minecraftPayloadDecode(d.text, d.enc);',
      '    d.text = null;',
      '    var raw = await globalThis.__minecraftXZRawDecode(packed, d.loader);',
      '    packed = null;',
      '    var copy;',
      '    if (d.kind === "wasm") {',
      '      var entries = globalThis.__minecraftArchiveUnpack(raw);',
      '      if (!entries.wasm) { throw new Error("p-wasm archive missing wasm entry"); }',
      '      copy = entries.wasm.slice();',
      '    } else {',
      '      copy = raw.slice();',
      '      globalThis.__minecraftArchiveUnpack(copy);',
      '    }',
      '    self.postMessage({ ok: true, buf: copy.buffer }, [copy.buffer]);',
      '  } catch (err) {',
      '    self.postMessage({ ok: false, error: (err && err.message) ? err.message : String(err) });',
      '  }',
      '};'
    ].join('\n');
    try {
      return URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
    } catch (e) {
      return null;
    }
  }

  var workerUrl = makeWorkerUrl();
  function runWorker(spec, kind) {
    return new Promise(function (resolve, reject) {
      if (!workerUrl) {
        mainThreadOne(spec, kind).then(resolve, reject);
        return;
      }
      var w;
      try {
        w = new Worker(workerUrl);
      } catch (e) {
        mainThreadOne(spec, kind).then(resolve, reject);
        return;
      }
      var settled = false;
      w.onmessage = function (e) {
        if (settled) { return; }
        settled = true;
        spec.text = null;
        try { w.terminate(); } catch (err) {}
        var d = e.data;
        if (d && d.ok && d.buf) {
          resolve(new Uint8Array(d.buf));
        } else {
          reject(new Error((d && d.error) || ('Offline unpack worker failed (' + kind + ')')));
        }
      };
      w.onerror = function (e) {
        if (settled) { return; }
        settled = true;
        if (e && e.preventDefault) { e.preventDefault(); }
        try { w.terminate(); } catch (err) {}
        mainThreadOne(spec, kind).then(resolve, reject);
      };
      try {
        w.postMessage({ text: spec.text, enc: spec.enc, loader: loaderBytes, kind: kind });
      } catch (e) {
        settled = true;
        try { w.terminate(); } catch (err) {}
        mainThreadOne(spec, kind).then(resolve, reject);
      }
    });
  }

  var wasmPromise, restPromise;
  if (workerUrl) {
    wasmPromise = runWorker(wasmSpec, 'wasm').then(function (wasmBytes) {
      if (onStage) { onStage('wasm', wasmBytes); }
      return wasmBytes;
    });
    restPromise = runWorker(restSpec, 'rest').then(function (rawRest) {
      var rest = globalThis.__minecraftArchiveUnpack(rawRest);
      if (onStage) { onStage('rest', rest, rawRest); }
      return { rest: rest, rawRest: rawRest };
    });
  } else {
    // Main-thread fallback: unpack wasm first so WebAssembly.compile can overlap with rest.
    wasmPromise = mainThreadOne(wasmSpec, 'wasm').then(function (wasmBytes) {
      if (onStage) { onStage('wasm', wasmBytes); }
      return wasmBytes;
    });
    restPromise = wasmPromise.then(function () {
      return new Promise(function (r) { setTimeout(r, 0); });
    }).then(function () {
      return mainThreadOne(restSpec, 'rest');
    }).then(function (rawRest) {
      var rest = globalThis.__minecraftArchiveUnpack(rawRest);
      if (onStage) { onStage('rest', rest, rawRest); }
      return { rest: rest, rawRest: rawRest };
    });
  }

  var combined = Promise.all([wasmPromise, restPromise]).then(function (pair) {
    if (workerUrl) { try { URL.revokeObjectURL(workerUrl); } catch (e) {} }
    return { wasm: pair[0], rest: pair[1].rest, rawRest: pair[1].rawRest };
  }, function (err) {
    if (workerUrl) { try { URL.revokeObjectURL(workerUrl); } catch (e) {} }
    throw err;
  });
  combined.wasmPromise = wasmPromise;
  combined.restPromise = restPromise;
  return combined;
};
