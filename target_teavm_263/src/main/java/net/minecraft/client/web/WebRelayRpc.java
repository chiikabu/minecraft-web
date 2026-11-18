// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebRelayRpc {
	private static final int CONNECT_TIMEOUT_MS = 15000;
	private static final int REQUEST_TIMEOUT_MS = 30000;
	private static final int PACK_REQUEST_TIMEOUT_MS = 330000;
	private static final int BINARY_REQUEST_TIMEOUT_MS = 40000;
	private static final int MAX_BINARY_RESPONSE = 262144000;
	private static final int IDLE_TIMEOUT_MS = 1000;
	private static final int MAX_PENDING = 64;
	private static final int MAX_QUEUE = 32;
	public static final int BINARY_OPENED = 0;
	public static final int BINARY_FRAME = 1;
	public static final int BINARY_CLOSED = 2;
	private static int nextStreamId = 1;

	private WebRelayRpc() {
	}

	public static void post(final String path, final String body, final ResponseHandler handler) {
		init();
		requestText(WebRelayAuth.relaySocketUrl(), path, body, handler, false);
	}

	public static void postAt(final String socketUrl, final String path, final String body,
			final ResponseHandler handler) {
		init();
		requestText(socketUrl, path, body, handler, false);
	}

	public static void postBinary(final String path, final String body, final BinaryResponseHandler handler) {
		init();
		requestBinary(WebRelayAuth.relaySocketUrl(), path, body, handler, true);
	}

	public static boolean usesWebRTC() {
		init();
		return usesWebRTCJs();
	}

	public static boolean usedWebSocketFallback(final String socketUrl) {
		init();
		return usedWebSocketFallbackJs(socketUrl);
	}

	public static int allocateStreamId() {
		if (nextStreamId <= 0 || nextStreamId >= 0x7FFFFFFF) {
			nextStreamId = 1;
		}
		return nextStreamId++;
	}

	public static void registerBinaryStream(final int streamId, final BinaryHandler handler) {
		init();
		register(WebRelayAuth.relaySocketUrl(), streamId, handler);
	}

	public static void registerBinaryStreamAt(final String socketUrl, final int streamId, final BinaryHandler handler) {
		init();
		register(socketUrl, streamId, handler);
	}

	public static void sendBinary(final int streamId, final byte[] frame) {
		send(streamId, Int8Array.copyFromJavaArray(frame));
	}

	public static void closeBinaryStream(final int streamId, final byte[] closeFrame) {
		close(streamId, Int8Array.copyFromJavaArray(closeFrame));
	}

	public static void forgetBinaryStream(final int streamId) {
		close(streamId, null);
	}

	public static void sendLan(final int streamId, final int peerStreamId, final Int8Array bytes, final boolean batched,
			final int firstLarge, final int largeBytes) {
		sendLanJs(streamId, peerStreamId, bytes, batched, firstLarge, largeBytes);
	}

	public static void sendLanOrdered(final int streamId, final int peerStreamId, final Int8Array frame) {
		sendLanOrderedJs(streamId, peerStreamId, frame);
	}

	public static String lanCounters() {
		return lanCountersJs();
	}

	public static int bufferedAmount() {
		return bufferedAmountJs();
	}

	@JSFunctor
	public interface ResponseHandler extends JSObject {
		void response(int status, String body);
	}

	@JSFunctor
	public interface BinaryResponseHandler extends JSObject {
		void response(int status, Int8Array body);
	}

	@JSFunctor
	public interface BinaryHandler extends JSObject {
		void event(int kind, Int8Array data, String reason);
	}

	@JSBody(script = ""
		+ "var root=globalThis;if(root.__minecraftRelayOpen)return;"
		+ "var fail=function(s,message){if(!s||s.failed)return;s.failed=true;if(s.connectTimer){clearTimeout(s.connectTimer);s.connectTimer=0;}if(s.idleTimer){clearTimeout(s.idleTimer);s.idleTimer=0;}"
		+ "var pending=s.pending;s.pending=Object.create(null);s.queue=[];Object.keys(pending).forEach(function(id){clearTimeout(pending[id].timer);try{pending[id].cb(0,pending[id].binary?null:(message||'relay socket closed'));}catch(e){}});"
		+ "var streams=s.streams;s.streams=Object.create(null);Object.keys(streams).forEach(function(id){try{streams[id](2,null,message||'relay socket closed');}catch(e){}});"
		+ "if(root.__minecraftRelayState===s)root.__minecraftRelayState=null;};"
		+ "var makeCarrier=function(url){var real=url;try{var net=root[Symbol.for('net.minecraft.client.n')];if(net)real=String(net.u(url));}catch(e){}var f={readyState:0,binaryType:'arraybuffer',kind:'connecting',fallback:false,onopen:null,onmessage:null,onerror:null,onclose:null};var link=null,pc=null,closed=false,opened=false,fallbackTimer=0,sendTimer=0,outgoing=[],outBytes=0,parts=[],partBytes=0,partText=-1,selected='auto';Object.defineProperty(f,'bufferedAmount',{get:function(){try{return outBytes+(link&&link.bufferedAmount||0);}catch(e){return outBytes;}}});try{selected=new URL(url,String(root.location&&root.location.href||url)).searchParams.get('transport')||'auto';}catch(e){}if(selected!=='webrtc'&&selected!=='websocket')selected='auto';"
		+ "var emitClose=function(){if(closed)return;closed=true;f.readyState=3;if(fallbackTimer)clearTimeout(fallbackTimer);try{if(f.onclose)f.onclose({code:1000});}catch(e){}};var emitError=function(){try{if(f.onerror)f.onerror({});}catch(e){}};"
		+ "var startWs=function(){if(closed||opened||f.kind==='websocket')return;f.kind='websocket';link=null;try{if(pc)pc.close();}catch(e){}pc=null;if(fallbackTimer){clearTimeout(fallbackTimer);fallbackTimer=0;}var ws;try{ws=new WebSocket(real,'socket-v1');}catch(e){emitError();emitClose();return;}link=ws;ws.binaryType='arraybuffer';ws.onopen=function(){if(closed){try{ws.close();}catch(e){}return;}opened=true;f.readyState=1;root.__minecraftRelayLastTransport={url:url,fallback:f.fallback};if(f.onopen)f.onopen({});};ws.onmessage=function(e){if(f.onmessage)f.onmessage(e);};ws.onerror=function(){emitError();};ws.onclose=emitClose;};var fallback=function(){if(closed||opened||f.kind==='websocket')return;if(selected==='webrtc'){emitError();f.close();}else{f.fallback=true;startWs();}};"
		+ "var pump=function(){if(closed||f.readyState!==1||f.kind!=='webrtc')return;try{while(outgoing.length){var item=outgoing[0],end=Math.min(item.at+16383,item.bytes.length);if(link.bufferedAmount+1+end-item.at>262144)break;var wire=new Uint8Array(1+end-item.at);wire[0]=(item.text?1:0)|(end===item.bytes.length?2:0);wire.set(item.bytes.subarray(item.at,end),1);link.send(wire);clearTimeout(sendTimer);sendTimer=0;item.at=end;if(end===item.bytes.length){outBytes-=item.bytes.length;outgoing.shift();}}}catch(e){emitError();f.close();return;}if(outgoing.length&&!sendTimer)sendTimer=setTimeout(function(){emitError();f.close();},40000);};"
		+ "f.send=function(data){if(f.readyState!==1||!link)throw new Error('relay carrier is not open');if(f.kind==='websocket'){link.send(data);return;}var text=typeof data==='string';var bytes=text?new TextEncoder().encode(data):(ArrayBuffer.isView(data)?new Uint8Array(data.buffer,data.byteOffset,data.byteLength):new Uint8Array(data));if(bytes.length>2097152||outBytes+bytes.length>4194304||outgoing.length>=256){emitError();f.close();return;}outgoing.push({bytes:bytes.slice(),text:text,at:0});outBytes+=bytes.length;pump();};"
		+ "f.close=function(){if(closed)return;f.readyState=2;if(fallbackTimer)clearTimeout(fallbackTimer);clearTimeout(sendTimer);outgoing=[];outBytes=0;parts=[];try{if(link)link.close();}catch(e){}try{if(pc)pc.close();}catch(e){}emitClose();};"
		+ "if(selected==='websocket'){startWs();return f;}if(typeof RTCPeerConnection!=='function'||typeof fetch!=='function'){fallback();return f;}f.kind='webrtc';try{pc=new RTCPeerConnection();var dc=pc.createDataChannel('socket-v1',{ordered:true,protocol:'socket-v1'});link=dc;dc.binaryType='arraybuffer';dc.bufferedAmountLowThreshold=131072;dc.onbufferedamountlow=pump;fallbackTimer=setTimeout(fallback,8000);dc.onopen=function(){if(closed||link!==dc){try{dc.close();}catch(e){}return;}opened=true;if(fallbackTimer){clearTimeout(fallbackTimer);fallbackTimer=0;}f.readyState=1;root.__minecraftRelayLastTransport={url:url,fallback:false};if(f.onopen)f.onopen({});};"
		+ "dc.onmessage=function(e){if(closed||link!==dc)return;try{var bytes=new Uint8Array(e.data);if(!bytes.length||(bytes[0]&252))throw new Error('invalid RTC frame');var text=bytes[0]&1;if(partText!==-1&&partText!==text)throw new Error('mixed RTC frame');partText=text;var piece=bytes.subarray(1);partBytes+=piece.length;if(partBytes>2097152)throw new Error('RTC frame too large');parts.push(piece);if(!(bytes[0]&2))return;var all=new Uint8Array(partBytes),at=0;for(var i=0;i<parts.length;i++){all.set(parts[i],at);at+=parts[i].length;}parts=[];partBytes=0;partText=-1;var data=text?new TextDecoder('utf-8',{fatal:true}).decode(all):all.buffer;if(f.onmessage)f.onmessage({data:data});}catch(x){emitError();f.close();}};dc.onerror=function(){if(closed||link!==dc)return;if(opened)emitError();else fallback();};dc.onclose=function(){if(closed||link!==dc)return;if(opened)f.close();else fallback();};"
		+ "var gatherIce=function(){if(pc.iceGatheringState==='complete')return Promise.resolve();return new Promise(function(ok,no){var timer=setTimeout(function(){no(new Error('ICE gathering timed out'));},5000);pc.onicegatheringstatechange=function(){if(pc.iceGatheringState==='complete'){clearTimeout(timer);ok();}};});};pc.createOffer().then(function(offer){return pc.setLocalDescription(offer);}).then(gatherIce).then(function(){if(closed||opened||f.kind!=='webrtc')return null;var endpoint=new URL(real,String(root.location&&root.location.href||real));endpoint.protocol=endpoint.protocol==='wss:'?'https:':'http:';endpoint.pathname='/rtc/offer';endpoint.hash='';return fetch(endpoint.toString(),{method:'POST',headers:{'content-type':'application/json','x-socket-protocol':'socket-v1'},body:JSON.stringify(pc.localDescription)}).then(function(response){if(!response.ok)throw new Error('signaling HTTP '+response.status);return response.json();}).then(function(answer){if(!answer||answer.type!=='answer'||typeof answer.sdp!=='string')throw new Error('invalid answer');return pc.setRemoteDescription(answer);});}).catch(function(){fallback();});}catch(e){fallback();}return f;};"
		+ "root.__minecraftRelayOpen=function(url){var old=root.__minecraftRelayState;if(old&&old.url===url&&old.ws&&old.ws.readyState<2&&!old.failed)return old;"
		+ "if(old){try{old.ws.close();}catch(e){}fail(old,'relay changed');}if(!url){root.__minecraftRelayOpenError='no relay selected';return null;}"
		+ "var s={url:url,next:1,pending:Object.create(null),queue:[],streams:Object.create(null),ws:null,connectTimer:0,idleTimer:0,failed:false};root.__minecraftRelayState=s;"
		+ "var ws;try{ws=makeCarrier(url);}catch(e){root.__minecraftRelayOpenError='cannot open relay transport: '+e;root.__minecraftRelayState=null;return null;}s.ws=ws;"
		+ "var sendText=function(message){try{ws.send(message);}catch(e){fail(s,'relay socket send failed: '+e);try{ws.close();}catch(x){}}};"
		+ "s.cancelIdle=function(){if(s.idleTimer){clearTimeout(s.idleTimer);s.idleTimer=0;}};s.closeIfIdle=function(){if(Object.keys(s.pending).length||Object.keys(s.streams).length){s.cancelIdle();return;}if(s.idleTimer)return;"
		+ "s.idleTimer=setTimeout(function(){s.idleTimer=0;if(Object.keys(s.pending).length||Object.keys(s.streams).length)return;if(root.__minecraftRelayState===s)root.__minecraftRelayState=null;s.failed=true;try{ws.close(1000,'idle');}catch(e){}}," + IDLE_TIMEOUT_MS + ");};"
		+ "s.connectTimer=setTimeout(function(){fail(s,'relay socket connect timed out');try{ws.close();}catch(e){}}," + CONNECT_TIMEOUT_MS + ");"
		+ "ws.onopen=function(){if(s.connectTimer){clearTimeout(s.connectTimer);s.connectTimer=0;}var q=s.queue;s.queue=[];q.forEach(function(item){if(s.pending[item.id])sendText(item.message);});Object.keys(s.streams).forEach(function(id){try{s.streams[id](0,null,'');}catch(e){}});s.closeIfIdle();};"
		+ "ws.onmessage=function(event){if(typeof event.data!=='string'){var bytes=new Uint8Array(event.data);if(bytes.length>=5&&(bytes[0]===32||bytes[0]===33)){var id=(bytes[1]|bytes[2]<<8|bytes[3]<<16|bytes[4]<<24)>>>0;var p=s.pending[id];if(!p||!p.binary)return;if(bytes[0]===32){p.bytes+=bytes.length-5;if(p.bytes>" + MAX_BINARY_RESPONSE + "){delete s.pending[id];clearTimeout(p.timer);try{p.cb(413,null);}catch(e){}s.closeIfIdle();return;}p.parts.push(bytes.subarray(5));if(bytes.length>5)p.refresh();return;}var expected=bytes.length===9?(bytes[5]|bytes[6]<<8|bytes[7]<<16|bytes[8]<<24)>>>0:-1;delete s.pending[id];clearTimeout(p.timer);if(expected!==p.bytes){try{p.cb(0,null);}catch(e){}s.closeIfIdle();return;}var all=new Int8Array(p.bytes),at=0;for(var i=0;i<p.parts.length;i++){all.set(p.parts[i],at);at+=p.parts[i].length;}try{p.cb(200,all);}catch(e){}s.closeIfIdle();return;}var frame=new Int8Array(event.data);Object.keys(s.streams).forEach(function(id){try{s.streams[id](1,frame,'');}catch(e){}});return;}"
		+ "var raw=event.data;if(raw.length>2097152){fail(s,'relay response is too large');try{ws.close();}catch(e){}return;}var reply;try{reply=JSON.parse(raw);}catch(e){fail(s,String(e));try{ws.close();}catch(x){}return;}"
		+ "if(!reply||typeof reply.id!=='number'||!Number.isSafeInteger(reply.id)||!Object.prototype.hasOwnProperty.call(s.pending,reply.id))return;var p=s.pending[reply.id];delete s.pending[reply.id];clearTimeout(p.timer);"
		+ "var status=Number(reply.status);if(!Number.isInteger(status)||status<0||status>599)status=0;if(p.binary){try{p.cb(status,null);}catch(e){}}else{var body;try{body=JSON.stringify(reply.body==null?{}:reply.body);}catch(e){body='{}';status=0;}try{p.cb(status,body);}catch(e){}}s.closeIfIdle();};"
		+ "ws.onerror=function(){fail(s,'relay socket error');try{ws.close();}catch(e){}};ws.onclose=function(){fail(s,'relay socket closed');};return s;};")
	private static native void init();

	private static final String REQUEST_SCRIPT = ""
		+ "var root=globalThis;var failBody=binary?null:'';var parsed;try{parsed=body?JSON.parse(body):{};}catch(e){cb(0,binary?null:String(e));return;}var s=root.__minecraftRelayOpen(url);if(!s){cb(0,binary?null:(root.__minecraftRelayOpenError||'cannot open relay socket'));return;}s.cancelIdle();"
		+ "if(Object.keys(s.pending).length>=" + MAX_PENDING + "){cb(0,failBody);return;}"
		+ "if(s.next>2147483647)s.next=1;var id=s.next++;var expire=function(){var p=s.pending[id];if(!p)return;delete s.pending[id];s.queue=s.queue.filter(function(item){return item.id!==id;});p.cb(0,p.binary?null:'relay request timed out');s.closeIfIdle();};var delay=binary?" + BINARY_REQUEST_TIMEOUT_MS + ":(path==='/auth/pack'?" + PACK_REQUEST_TIMEOUT_MS + ":" + REQUEST_TIMEOUT_MS + ");"
		+ "s.pending[id]={cb:cb,timer:setTimeout(expire,delay),refresh:function(){clearTimeout(this.timer);this.timer=setTimeout(expire,delay);},binary:binary,parts:binary?[]:null,bytes:0};var message=JSON.stringify({id:id,path:path,body:parsed});if(message.length>1048576){var p=s.pending[id];delete s.pending[id];clearTimeout(p.timer);cb(0,binary?null:'relay request is too large');s.closeIfIdle();return;}"
		+ "if(s.ws.readyState===1){try{s.ws.send(message);}catch(e){var p=s.pending[id];delete s.pending[id];clearTimeout(p.timer);cb(0,binary?null:('relay socket send failed: '+e));s.closeIfIdle();}}"
		+ "else if(s.queue.length<" + MAX_QUEUE + ")s.queue.push({id:id,message:message});else{var p=s.pending[id];delete s.pending[id];clearTimeout(p.timer);cb(0,binary?null:'relay connection queue is full');s.closeIfIdle();}";

	@JSBody(params = { "url", "path", "body", "cb", "binary" }, script = REQUEST_SCRIPT)
	private static native void requestText(String url, String path, String body, ResponseHandler handler,
			boolean binary);

	@JSBody(params = { "url", "path", "body", "cb", "binary" }, script = REQUEST_SCRIPT)
	private static native void requestBinary(String url, String path, String body, BinaryResponseHandler handler,
			boolean binary);

	@JSBody(params = { "url", "streamId", "handler" }, script = ""
		+ "var root=globalThis;var s=root.__minecraftRelayOpen(url);if(!s){handler(2,null,root.__minecraftRelayOpenError||'cannot open relay socket');return;}"
		+ "s.cancelIdle();s.streams[streamId]=handler;if(s.ws.readyState===1)handler(0,null,'');")
	private static native void register(String url, int streamId, BinaryHandler handler);

	@JSBody(params = { "streamId", "frame" }, script = ""
		+ "var s=globalThis.__minecraftRelayState;if(!s||!s.streams[streamId]||s.ws.readyState!==1)return;try{s.ws.send(frame);}catch(e){try{s.streams[streamId](2,null,'relay socket send failed: '+e);}catch(x){}delete s.streams[streamId];s.closeIfIdle();}")
	private static native void send(int streamId, JSObject frame);

	@JSBody(params = { "streamId", "frame" }, script = ""
		+ "var s=globalThis.__minecraftRelayState;if(!s)return;if(frame&&s.ws&&s.ws.readyState===1){try{s.ws.send(frame);}catch(e){}}delete s.streams[streamId];s.closeIfIdle();")
	private static native void close(int streamId, JSObject frame);

	private static final String LAN_STATE = """
		var lan = root.__minecraftLan;
		if (!lan) {
			lan = root.__minecraftLan = { tails: Object.create(null), wire: 0, raw: 0, frames: 0, packed: 0, packedIn: 0, packedOut: 0,
				canDeflate: typeof CompressionStream === 'function' };
			lan.header = function (u8, id) { u8[0] = 2; u8[1] = id & 255; u8[2] = id >>> 8 & 255; u8[3] = id >>> 16 & 255; u8[4] = id >>> 24 & 255; };
			lan.varint = function (out, at, v) { while (v > 127) { out[at++] = v & 127 | 128; v >>>= 7; } out[at++] = v; return at; };
			lan.varintSize = function (v) { var n = 1; while (v > 127) { v >>>= 7; n++; } return n; };
			lan.deflate = function (u8) {
				var cs = new CompressionStream('deflate'), w = cs.writable.getWriter(), r = cs.readable.getReader(), parts = [], n = 0;
				w.write(u8); w.close();
				var pump = function () {
					return r.read().then(function (x) {
						if (!x.done) { parts.push(x.value); n += x.value.length; return pump(); }
						var out = new Uint8Array(n), at = 0;
						for (var i = 0; i < parts.length; i++) { out.set(parts[i], at); at += parts[i].length; }
						return out;
					});
				};
				return pump();
			};
			lan.squeeze = function (item, data, pending) {
				pending.push(lan.deflate(data).then(function (z) {
					var head = lan.varintSize(data.length);
					if (head + z.length < data.length) {
						var c = new Uint8Array(head + z.length);
						lan.varint(c, 0, data.length); c.set(z, head);
						item.p = c; lan.packed++; lan.packedIn += data.length; lan.packedOut += z.length;
					}
				}, function () {}));
			};
			lan.put = function (state, frames) {
				if (root.__minecraftRelayState !== state || state.ws.readyState !== 1) { return; }
				for (var i = 0; i < frames.length; i++) {
					try { state.ws.send(frames[i]); lan.wire += frames[i].length; lan.frames++; } catch (e) { return; }
				}
			};
			lan.send = function (state, id, frames, promise) {
				var tail = lan.tails[id];
				if (!tail && !promise) { lan.put(state, frames); return; }
				var mine = (tail || Promise.resolve()).then(function () { return promise || frames; })
					.then(function (ready) { lan.put(state, ready); }, function () {})
					.then(function () { if (lan.tails[id] === mine) { delete lan.tails[id]; } });
				lan.tails[id] = mine;
			};
		}
		""";

	@JSBody(params = { "reg", "peer", "bytes", "batched", "firstLarge", "largeBytes" }, script = ""
		+ "var root = globalThis, s = root.__minecraftRelayState;"
		+ "if (!s || !s.streams[reg] || s.ws.readyState !== 1) { return; }"
		+ LAN_STATE + """
		var u8 = new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength), n = u8.length;
		lan.raw += n - 5;
		var deflating = firstLarge >= 0 && largeBytes > 0 && lan.canDeflate;
		if (batched && !deflating) { lan.header(u8, peer); lan.send(s, peer, [u8], null); return; }
		var items = [], pending = [], at = 5;
		while (at < n) {
			var frameAt = at, len = 0, shift = 0, b;
			do { b = u8[at++]; len |= (b & 127) << shift; shift += 7; } while (b & 128);
			var item = { p: u8.subarray(at, at + len) };
			if (deflating && frameAt >= firstLarge && len > largeBytes && u8[at] === 0) {
				lan.squeeze(item, u8.subarray(at + 1, at + len), pending);
			}
			items.push(item);
			at += len;
		}
		var build = function () {
			var frames = [], i, p;
			if (batched) {
				var total = 5;
				for (i = 0; i < items.length; i++) { p = items[i].p; total += lan.varintSize(p.length) + p.length; }
				var out = new Uint8Array(total), o = 5;
				lan.header(out, peer);
				for (i = 0; i < items.length; i++) { p = items[i].p; o = lan.varint(out, o, p.length); out.set(p, o); o += p.length; }
				frames.push(out);
			} else {
				for (i = 0; i < items.length; i++) { p = items[i].p; var f = new Uint8Array(5 + p.length); lan.header(f, peer); f.set(p, 5); frames.push(f); }
			}
			return frames;
		};
		if (pending.length) { lan.send(s, peer, null, Promise.all(pending).then(build)); } else { lan.send(s, peer, build(), null); }
		""")
	private static native void sendLanJs(int reg, int peer, Int8Array bytes, boolean batched, int firstLarge, int largeBytes);

	@JSBody(params = { "reg", "peer", "frame" }, script = ""
		+ "var root = globalThis, s = root.__minecraftRelayState;"
		+ "if (!s || !s.streams[reg] || s.ws.readyState !== 1) { return; }"
		+ LAN_STATE
		+ "lan.send(s, peer, [new Uint8Array(frame.buffer, frame.byteOffset, frame.byteLength)], null);")
	private static native void sendLanOrderedJs(int reg, int peer, Int8Array frame);

	@JSBody(script = "var lan = globalThis.__minecraftLan, s = globalThis.__minecraftRelayState; if (!lan) { return ''; }"
		+ "var buffered = 0; try { buffered = s && s.ws && s.ws.bufferedAmount || 0; } catch (e) {}"
		+ "return lan.wire + ',' + lan.raw + ',' + lan.frames + ',' + lan.packed + ',' + lan.packedIn + ',' + lan.packedOut + ',' + buffered;")
	private static native String lanCountersJs();

	@JSBody(script = "var s = globalThis.__minecraftRelayState; try { return s && s.ws && s.ws.bufferedAmount | 0 || 0; } catch (e) { return 0; }")
	private static native int bufferedAmountJs();

	@JSBody(params = { "url" }, script = "var last=globalThis.__minecraftRelayLastTransport;return !!(last&&last.url===url&&last.fallback);")
	private static native boolean usedWebSocketFallbackJs(String url);

	@JSBody(script = "var s=globalThis.__minecraftRelayState;return !!(s&&s.ws&&s.ws.kind==='webrtc'&&s.ws.readyState===1&&!s.failed);")
	private static native boolean usesWebRTCJs();
}
