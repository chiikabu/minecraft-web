// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.reflect;

import java.lang.reflect.InvocationHandler;

public class TProxy {
	public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces, InvocationHandler handler) {
		throw new UnsupportedOperationException("no dynamic proxies in the browser");
	}

	public static boolean isProxyClass(Class<?> cls) {
		return false;
	}

	public static InvocationHandler getInvocationHandler(Object proxy) {
		throw new IllegalArgumentException("not a proxy instance");
	}
}
