// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.html.HTMLCanvasElement;

public final class BrowserTouch {

    @JSFunctor
    public interface Input extends JSObject {
        void send(int kind, double x, double y);
    }

    @JSBody(params = {"controls", "playing"}, script = "if (controls) controls.mode(playing);")
    public static native void mode(JSObject controls, boolean playing);

    @JSBody(params = {"controls"}, script = "if (controls) controls.destroy();")
    public static native void destroy(JSObject controls);

    @JSBody(params = {"canvas", "send"}, script = """
        if (!canvas || !navigator.maxTouchPoints) return null;
        var playing = false, pointers = new Map(), held = new Map(), removers = [];
        var root = document.createElement('div');
        root.setAttribute('aria-label', 'Touch controls');
        root.style.cssText = 'position:fixed;inset:0;z-index:1000;pointer-events:none;display:none';
        var layout = document.createElement('style');
        layout.textContent = '@media(max-width:560px){[data-browser-touch-slot]{'
            + 'bottom:calc(178px + env(safe-area-inset-bottom))!important}}';
        root.appendChild(layout);
        document.body.appendChild(root);
        var previousAction = canvas.style.touchAction;
        canvas.style.touchAction = 'none';
        function listen(node, type, fn) {
            node.addEventListener(type, fn, {passive:false});
            removers.push(function() { node.removeEventListener(type, fn); });
        }
        function hold(kind, code, down) {
            var id = kind + ':' + code, count = held.get(id) || 0;
            if (down) { held.set(id, count + 1); if (!count) send(kind, code, 1); }
            else if (count) { held.set(id, count - 1); if (count === 1) send(kind, code, 0); }
        }
        function end(e) {
            var p = pointers.get(e.pointerId);
            if (!p) return;
            pointers.delete(e.pointerId);
            if (p.kind >= 0) hold(p.kind, p.code, false);
            if (p.node.hasPointerCapture(e.pointerId)) p.node.releasePointerCapture(e.pointerId);
            if (p.node !== canvas) p.node.style.background = 'rgba(20,20,20,.55)';
            e.preventDefault();
            if (p.node === textButton && e.type === 'pointerup') {
                release();
                var text = window.prompt('Text to append to the selected game field:', '');
                if (text) Array.from(text).forEach(function(c){send(4,c.codePointAt(0),0);});
            }
        }
        function release() {
            Array.from(pointers.keys()).forEach(function(id) {
                end({pointerId:id, preventDefault:function(){}});
            });
        }
        function track(node, kind, code) {
            listen(node, 'pointerdown', function(e) {
                if (e.pointerType !== 'touch' || pointers.has(e.pointerId)) return;
                root.style.display = 'block';
                e.preventDefault();
                if (!node.hasPointerCapture(e.pointerId)) node.setPointerCapture(e.pointerId);
                var k = kind, c = code;
                if (node === canvas) {
                    k = playing ? -1 : 1; c = 0;
                    if (!playing) {
                        var r = canvas.getBoundingClientRect();
                        send(2, e.clientX - r.left, e.clientY - r.top);
                    }
                }
                pointers.set(e.pointerId, {node:node,kind:k,code:c,x:e.clientX,y:e.clientY});
                if (k >= 0) hold(k, c, true);
                if (node !== canvas) node.style.background = 'rgba(80,160,160,.8)';
            });
            listen(node, 'pointermove', function(e) {
                var p = pointers.get(e.pointerId);
                if (!p) return;
                e.preventDefault();
                if (node === canvas) {
                    if (p.kind === -1) {
                        if (document.pointerLockElement) send(3, e.movementX, e.movementY);
                        else send(3, e.clientX - p.x, e.clientY - p.y);
                    }
                    else { var r = canvas.getBoundingClientRect(); send(2,e.clientX-r.left,e.clientY-r.top); }
                    p.x = e.clientX; p.y = e.clientY;
                }
            });
            ['pointerup','pointercancel','lostpointercapture'].forEach(function(type) { listen(node,type,end); });
        }
        var gameButtons = [];
        function button(label, code, kind, position, gameOnly) {
            var b = document.createElement('button');
            b.textContent = label; b.setAttribute('aria-label', label);
            b.style.cssText = 'position:absolute;min-width:44px;min-height:44px;color:white;'
                + 'border:1px solid #aaa;border-radius:8px;background:rgba(20,20,20,.55);'
                + 'font:14px sans-serif;pointer-events:auto;touch-action:none;user-select:none;' + position;
            root.appendChild(b);
            track(b,kind,code);
            if (gameOnly) gameButtons.push(b);
            return b;
        }
        button('Forward',87,0,'left:70px;bottom:calc(124px + env(safe-area-inset-bottom))',true);
        button('Left',65,0,'left:16px;bottom:calc(70px + env(safe-area-inset-bottom))',true);
        button('Back',83,0,'left:70px;bottom:calc(16px + env(safe-area-inset-bottom))',true);
        button('Right',68,0,'left:124px;bottom:calc(70px + env(safe-area-inset-bottom))',true);
        button('Jump',32,0,'right:16px;bottom:calc(124px + env(safe-area-inset-bottom))',true);
        button('Attack',0,1,'right:90px;bottom:calc(70px + env(safe-area-inset-bottom))',true);
        button('Use',1,1,'right:16px;bottom:calc(70px + env(safe-area-inset-bottom))',true);
        button('Sneak',340,0,'right:16px;bottom:calc(16px + env(safe-area-inset-bottom))',true);
        button('Inventory',69,0,'right:16px;top:calc(12px + env(safe-area-inset-top))',true);
        button('Chat',84,0,'right:110px;top:calc(12px + env(safe-area-inset-top))',true);
        button('Esc',256,0,'left:16px;top:calc(12px + env(safe-area-inset-top))',false);
        var textButton = button('Text',0,-1,'left:70px;top:calc(12px + env(safe-area-inset-top))',false);
        var eraseButton = button('Delete',259,0,'left:124px;top:calc(12px + env(safe-area-inset-top))',false);
        var enterButton = button('Enter',257,0,'left:190px;top:calc(12px + env(safe-area-inset-top))',false);
        // ponytail: fixed default bindings; a layout editor can follow mobile play testing.
        for (var i = 1; i <= 9; i++) {
            button(String(i),48+i,0,'left:calc(50% + '+((i-5)*32-16)+'px);'
                + 'bottom:env(safe-area-inset-bottom);min-width:30px',true)
                .setAttribute('data-browser-touch-slot','');
        }
        track(canvas,-1,0);
        listen(window,'blur',release);
        listen(document,'visibilitychange',function(){ if(document.hidden) release(); });
        var controls = {
            mode:function(value) {
                if (playing !== value) release();
                playing = value;
                gameButtons.forEach(function(b){ b.style.display = playing ? '' : 'none'; });
                [textButton,eraseButton,enterButton].forEach(function(b){ b.style.display = playing ? 'none' : ''; });
            },
            destroy:function() {
                release(); removers.forEach(function(remove){remove();}); root.remove();
                canvas.style.touchAction = previousAction;
            }
        };
        controls.mode(false);
        return controls;
        """)
    public static native JSObject install(HTMLCanvasElement canvas, Input send);
}
