// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgl;
import org.teavm.jso.JSObject;
public interface WebGLDrawBuffersIndexed extends JSObject {
    void enableiOES(int target, int index);
    void disableiOES(int target, int index);
    void colorMaskiOES(int index, boolean r, boolean g, boolean b, boolean a);
    void blendFuncSeparateiOES(int index, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
}
