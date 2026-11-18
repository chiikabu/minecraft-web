// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

@FunctionalInterface
public interface SDL_DialogFileCallbackI {
    void invoke(long userdata, long filelist, int filter);
}
