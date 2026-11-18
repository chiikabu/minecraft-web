// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.channels;

public interface TCompletionHandler<V, A> {
    void completed(V result, A attachment);
    void failed(Throwable error, A attachment);
}
