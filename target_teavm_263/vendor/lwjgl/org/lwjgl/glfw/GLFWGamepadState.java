// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

public final class GLFWGamepadState {

	private final ByteBuffer buttons = ByteBuffer.allocate(15);
	private final FloatBuffer axes = FloatBuffer.allocate(6);

	public static GLFWGamepadState create() {
		return new GLFWGamepadState();
	}

	public byte buttons(int index) {
		return buttons.get(index);
	}

	public float axes(int index) {
		return axes.get(index);
	}

	public ByteBuffer buttons() {
		return buttons.duplicate();
	}

	public FloatBuffer axes() {
		return axes.duplicate();
	}

	boolean read(JSObject pad) {
		Float32Array values = map(pad);
		for (int i = 0; i < 15; i++) buttons.put(i, values == null ? 0 : (byte)values.get(i));
		for (int i = 0; i < 6; i++) axes.put(i, values == null ? (i < 4 ? 0 : -1) : values.get(15 + i));
		return values != null;
	}

	@JSBody(params = {"pad"}, script = """
		if (!pad || !pad.connected || pad.mapping !== 'standard') return null;
		var out = new Float32Array(21);
		var buttons = [0,1,2,3,4,5,8,9,16,10,11,12,13,14,15];
		for (var i = 0; i < 15; i++) {
			var button = pad.buttons[buttons[i]];
			out[i] = button && button.pressed ? 1 : 0;
		}
		for (var i = 0; i < 4; i++) {
			var axis = pad.axes[i];
			out[15+i] = Number.isFinite(axis) ? Math.max(-1, Math.min(1, axis)) : 0;
		}
		for (var i = 0; i < 2; i++) {
			var trigger = pad.buttons[6+i];
			var value = trigger && Number.isFinite(trigger.value) ? trigger.value : 0;
			out[19+i] = Math.max(0, Math.min(1, value)) * 2 - 1;
		}
		return out;
		""")
	private static native Float32Array map(JSObject pad);
}
