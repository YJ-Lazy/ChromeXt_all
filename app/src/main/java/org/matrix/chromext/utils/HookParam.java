package org.matrix.chromext.utils;

import java.lang.reflect.Executable;

/** Local callback state for the existing before/after browser adapters. */
public final class HookParam {
    public final Executable method;
    public final Object thisObject;
    public Object[] args;
    private Object result;
    private Throwable throwable;
    private boolean early;

    public HookParam(Executable method, Object thisObject, Object[] args) {
        this.method = method;
        this.thisObject = thisObject;
        this.args = args;
    }

    public Object getResult() { return result; }
    public void setResult(Object result) {
        this.result = result; this.throwable = null; this.early = true;
    }
    public Throwable getThrowable() { return throwable; }
    public void setThrowable(Throwable throwable) {
        this.throwable = throwable; this.result = null; this.early = true;
    }
    public boolean getReturnEarly() { return early; }
    public boolean hasThrowable() { return throwable != null; }
    public void complete(Object value, Throwable error) {
        result = value; throwable = error; early = false;
    }
    public Object getResultOrThrowable() throws Throwable {
        if (throwable != null) throw throwable;
        return result;
    }
}
