// minecraft-web by chiikabu (https://github.com/chiikabu)

globalThis.__minecraftHTML85Decode = function (text) {
  var alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;.=>?@^_`{|}~';
  var digits = new Int16Array(128);
  digits.fill(-1);
  for (var i = 0; i < alphabet.length; i++) { digits[alphabet.charCodeAt(i)] = i; }
  text = text.replace(/[\t\n\f\r ]/g, '');
  if (text.length % 5 === 1) { throw new Error('Invalid base85 payload length'); }
  var bytes = new Uint8Array(Math.floor(text.length * 4 / 5)), cursor = 0;
  for (var offset = 0; offset < text.length; offset += 5) {
    var value = 0;
    for (var j = 0; j < 5; j++) {
      var digit = offset + j < text.length ? digits[text.charCodeAt(offset + j)] : 84;
      if (!(digit >= 0)) { throw new Error('Invalid base85 payload character'); }
      value = value * 85 + digit;
    }
    if (value > 0xFFFFFFFF) { throw new Error('Base85 payload overflow'); }
    for (var j = 0; j < 4 && cursor < bytes.length; j++) { bytes[cursor++] = value >>> (24 - j * 8); }
  }
  return bytes;
};

globalThis.__minecraftXZDecode = async function (input, wasmInput) {
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
  if (td.decode(raw.subarray(0, 8)) !== 'MCXZ001\0') { throw new Error('Invalid XZ archive magic'); }
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
  for (var span of spans) {
    if (span[0] !== cursor) { throw new Error('Overlapping or noncontiguous XZ payload'); }
    cursor += span[1];
  }
  if (cursor !== raw.length) { throw new Error('Trailing XZ archive bytes'); }
  return decoded;
};
