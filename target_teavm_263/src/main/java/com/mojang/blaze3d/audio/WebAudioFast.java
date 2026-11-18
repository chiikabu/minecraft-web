// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.audio;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

final class WebAudioFast {
	private WebAudioFast() {
	}

	@JSBody(params = {"p", "x", "y", "z"}, script = "if (p.positionX) { if (p.positionX.value !== x) p.positionX.value = x; if (p.positionY.value !== y) p.positionY.value = y; if (p.positionZ.value !== z) p.positionZ.value = z; } else p.setPosition(x, y, z);")
	static native void pannerPosition(JSObject panner, float x, float y, float z);

	@JSBody(params = {"l", "x", "y", "z", "fx", "fy", "fz", "ux", "uy", "uz"}, script = "if (l.positionX) { if (l.positionX.value !== x) l.positionX.value = x; if (l.positionY.value !== y) l.positionY.value = y; if (l.positionZ.value !== z) l.positionZ.value = z; if (l.forwardX.value !== fx) l.forwardX.value = fx; if (l.forwardY.value !== fy) l.forwardY.value = fy; if (l.forwardZ.value !== fz) l.forwardZ.value = fz; if (l.upX.value !== ux) l.upX.value = ux; if (l.upY.value !== uy) l.upY.value = uy; if (l.upZ.value !== uz) l.upZ.value = uz; } else { l.setPosition(x, y, z); l.setOrientation(fx, fy, fz, ux, uy, uz); }")
	static native void listenerTransform(JSObject listener, float x, float y, float z, float fx, float fy, float fz, float ux, float uy, float uz);
}
