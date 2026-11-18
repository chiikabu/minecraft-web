// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

public abstract class TRecursiveAction extends TForkJoinTask<Void> {
    public TRecursiveAction() { }

    protected abstract void compute();
    @Override public final Void getRawResult() { return null; }
    @Override protected final void setRawResult(Void value) { }

    @Override
    protected final boolean exec() {
        compute();
        return true;
    }
}
