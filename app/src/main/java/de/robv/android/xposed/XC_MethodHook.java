package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XCallback;
import java.lang.reflect.Member;

public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() { this(PRIORITY_DEFAULT); }
    public XC_MethodHook(int priority) { super(priority); }
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        Object result;
        Throwable throwable;
        boolean early;
        public Object getResult() { return result; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setResult(Object value) { result = value; throwable = null; early = true; }
        public void setThrowable(Throwable value) { throwable = value; result = null; early = true; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
    public final class Unhook {
        private final Member member;
        Unhook(Member member) { this.member = member; }
        public Member getHookedMethod() { return member; }
        public XC_MethodHook getCallback() { return XC_MethodHook.this; }
        public void unhook() { XposedBridge.remove(member, XC_MethodHook.this); }
    }
}
