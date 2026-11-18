// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

public interface Pointer {
    int POINTER_SIZE = 8;
    int POINTER_SHIFT = 3;
    long address();
}
