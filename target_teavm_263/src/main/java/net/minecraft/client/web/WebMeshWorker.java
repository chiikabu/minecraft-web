// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.renderer.chunk.Web263MeshOffload;
import org.teavm.jso.JSBody;

public final class WebMeshWorker implements Web263MeshOffload.Bridge {
	private WebMeshWorker() {
	}

	public static void install(final boolean disabled) {
		if (disabled) {
			System.out.println("[26.3-web] mesh worker: off (?nomeshworker)");
			return;
		}
		if (!present()) {
			System.out.println("[26.3-web] mesh worker: page script missing, sections mesh on the main thread");
			return;
		}
		Web263MeshOffload.bridge = new WebMeshWorker();
		System.out.println("[26.3-web] mesh worker: bridge installed");
	}

	@JSBody(script = "return !!(globalThis.__twitMesh && globalThis.__teavmMemory);")
	private static native boolean present();

	@Override
	public int caps() {
		if (net.minecraft.client.web.shaderpack.ShaderBlockIds.active) {
			return 0;
		}
		return jsCaps();
	}

	@Override
	public int capacity() {
		return jsCapacity();
	}

	@Override
	public void post(final long address, final int length) {
		jsPost((int)address, length);
	}

	@Override
	public void reference(final long address, final int length) {
		jsReference((int)address, length);
	}

	@Override
	public int peek() {
		return jsPeek();
	}

	@Override
	public void take(final long address) {
		jsTake((int)address);
	}

	@Override
	public void note(final int kind, final int a, final int b) {
		jsNote(kind, a, b);
	}

	@JSBody(script = "return globalThis.__twitMesh.caps() | 0;")
	private static native int jsCaps();

	@JSBody(script = "return globalThis.__twitMesh.capacity() | 0;")
	private static native int jsCapacity();

	@JSBody(params = {"address", "length"}, script =
		"globalThis.__twitMesh.post(globalThis.__teavmMemory.buffer, address >>> 0, length);")
	private static native void jsPost(int address, int length);

	@JSBody(params = {"address", "length"}, script =
		"globalThis.__twitMesh.reference(globalThis.__teavmMemory.buffer, address >>> 0, length);")
	private static native void jsReference(int address, int length);

	@JSBody(script = "return globalThis.__twitMesh.peek() | 0;")
	private static native int jsPeek();

	@JSBody(params = {"address"}, script =
		"globalThis.__twitMesh.take(globalThis.__teavmMemory.buffer, address >>> 0);")
	private static native void jsTake(int address);

	@JSBody(params = {"kind", "a", "b"}, script = "globalThis.__twitMesh.note(kind, a, b);")
	private static native void jsNote(int kind, int a, int b);
}
