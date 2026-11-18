// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class WebMemCheck {
	private WebMemCheck() {
	}

	static void run() {
		StringBuilder fail = new StringBuilder();
		try {
			long p = MemoryUtil.nmemCalloc(64, 1);
			boolean zero = true;
			for (int i = 0; i < 64; i++) {
				zero &= MemoryUtil.memGetByte(p + i) == 0;
			}
			check(fail, "calloc-zero", zero);
			MemoryUtil.memPutInt(p, 0x11223344);
			check(fail, "int-roundtrip", MemoryUtil.memGetInt(p) == 0x11223344);
			check(fail, "little-endian", MemoryUtil.memGetByte(p) == 0x44 && MemoryUtil.memGetByte(p + 3) == 0x11);
			MemoryUtil.memPutByte(p + 8, (byte) -5);
			check(fail, "byte", MemoryUtil.memGetByte(p + 8) == -5);
			MemoryUtil.memPutShort(p + 10, (short) 0x7ABC);
			check(fail, "short", MemoryUtil.memGetByte(p + 10) == (byte) 0xBC && MemoryUtil.memGetByte(p + 11) == 0x7A);
			MemoryUtil.memPutFloat(p + 12, 1.5f);
			check(fail, "float", MemoryUtil.memGetInt(p + 12) == Float.floatToRawIntBits(1.5f));
			MemoryUtil.memPutBlockVertex(p + 16, 1, 2, 3, 4, 5, 6, 7);
			check(fail, "block-vertex", MemoryUtil.memGetInt(p + 16) == 1 && MemoryUtil.memGetInt(p + 40) == 7);
			long q = MemoryUtil.nmemAlloc(64);
			MemoryUtil.memCopy(p, q, 64);
			check(fail, "memCopy", MemoryUtil.memGetInt(q) == 0x11223344 && MemoryUtil.memGetInt(q + 40) == 7);
			ByteBuffer view = MemoryUtil.memByteBuffer(q, 64);
			check(fail, "view-order", view.order() == ByteOrder.LITTLE_ENDIAN && view.capacity() == 64 && view.position() == 0);
			check(fail, "view-read", view.getInt(0) == 0x11223344);
			check(fail, "view-address", MemoryUtil.memAddress(view) == q);
			view.position(4);
			check(fail, "view-address-pos", MemoryUtil.memAddress(view) == q + 4);
			ByteBuffer sl = view.slice().order(ByteOrder.LITTLE_ENDIAN);
			sl.putInt(0, 0x55667788);
			check(fail, "slice-writethrough", MemoryUtil.memGetInt(q + 4) == 0x55667788 && MemoryUtil.memAddress(sl) == q + 4);
			IntBuffer ib = MemoryUtil.memIntBuffer(q, 16);
			check(fail, "intview", ib.get(0) == 0x11223344 && ib.get(1) == 0x55667788 && MemoryUtil.memAddress(ib) == q);
			ib.put(2, 99);
			check(fail, "intview-write", MemoryUtil.memGetInt(q + 8) == 99);
			MemoryUtil.memSet(q + 32, 0xAB, 8);
			check(fail, "memSet", MemoryUtil.memGetByte(q + 32) == (byte) 0xAB && MemoryUtil.memGetByte(q + 39) == (byte) 0xAB && MemoryUtil.memGetByte(q + 40) == 7);
			ByteBuffer heap = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
			heap.putInt(0, 0x0BADF00D);
			MemoryUtil.memCopy(MemoryUtil.memAddress(heap), q, 4);
			check(fail, "foreign-in", MemoryUtil.memGetInt(q) == 0x0BADF00D);
			MemoryUtil.memCopy(q + 4, MemoryUtil.memAddress(heap) + 8, 4);
			check(fail, "foreign-out", heap.getInt(8) == 0x55667788);
			ByteBuffer v2 = MemoryUtil.memByteBuffer(p, 64);
			MemoryUtil.memCopy(heap, v2);
			check(fail, "bb-copy", MemoryUtil.memGetInt(p) == 0x0BADF00D && MemoryUtil.memGetInt(p + 8) == 0x55667788);
			ByteBuffer big = MemoryUtil.memRealloc(v2, 256);
			check(fail, "realloc-keeps", big.capacity() == 256 && big.getInt(0) == 0x0BADF00D && big.getInt(8) == 0x55667788);
			ByteBuffer huge = MemoryUtil.memAlloc(8 << 20);
			huge.putInt((8 << 20) - 4, 7);
			check(fail, "8mb", huge.getInt((8 << 20) - 4) == 7);
			MemoryUtil.memFree(huge);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer si = stack.mallocInt(4);
				si.put(0, 42);
				check(fail, "stack-int", si.get(0) == 42 && MemoryUtil.memGetInt(MemoryUtil.memAddress(si)) == 42);
			}
			MemoryUtil.memFree(big);
			MemoryUtil.nmemFree(q);
			MemoryUtil.nmemFree(q);
			check(fail, "double-free", true);
		} catch (Throwable t) {
			fail.append(" exception:").append(t);
			t.printStackTrace();
		}
		long[] st = MemoryUtil.debugStats();
		System.out.println("[26.3-web] memcheck " + (fail.length() == 0 ? "PASS" : "FAIL:" + fail) + " linear=" + MemoryUtil.LINEAR + " blocks=" + st[0] + " bytes=" + st[1] + " foreign=" + st[2]);
	}

	private static void check(StringBuilder fail, String name, boolean ok) {
		if (!ok) {
			fail.append(' ').append(name);
		}
	}
}
