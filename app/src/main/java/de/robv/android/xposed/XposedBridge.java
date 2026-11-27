package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.*;
import java.util.*;

/** Internal ChromeXt-only callback dispatcher, not an Xposed framework entry. */
public final class XposedBridge {
    private static final Map<Member, Record> records = new HashMap<>();
    private XposedBridge() {}
    private static native boolean initializeNative();
    private static native Method hookNative(Member target, Object record, Method callback);
    public static native Method applicationAttachNative();
    public static boolean initialize() { return initializeNative(); }
    public static void log(String msg) { Log.d("ChromeXt-Zygisk", msg); }

    public static synchronized XC_MethodHook.Unhook hookMethod(Member target, XC_MethodHook callback) {
        if (!(target instanceof Method) && !(target instanceof Constructor<?>))
            throw new IllegalArgumentException("Not a Java method or constructor");
        Record record = records.get(target);
        if (record == null) {
            record = new Record(target);
            record.add(callback);
            synchronized (record) {
                try {
                    Method entry = Record.class.getDeclaredMethod("dispatch", Object[].class);
                    record.backup = hookNative(target, record, entry);
                    if (record.backup == null) throw new IllegalStateException("LSPlant hook failed: " + target);
                    record.backup.setAccessible(true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Hook callback setup failed", e);
                }
            }
            records.put(target, record);
        } else record.add(callback);
        return callback.new Unhook(target);
    }
    static synchronized void remove(Member target, XC_MethodHook callback) {
        Record record = records.get(target);
        if (record != null) record.remove(callback);
        // Keep the backup trampoline alive for callbacks already in flight.
    }
    public static final class Record {
        final Member target;
        Method backup;
        private final ArrayList<XC_MethodHook> callbacks = new ArrayList<>();
        Record(Member target) { this.target = target; }
        synchronized void add(XC_MethodHook cb) {
            if (!callbacks.contains(cb)) callbacks.add(cb);
            callbacks.sort((a, b) -> Integer.compare(b.priority, a.priority));
        }
        synchronized void remove(XC_MethodHook cb) { callbacks.remove(cb); }
        public Object dispatch(Object[] raw) throws Throwable {
            final XC_MethodHook[] list;
            final Method original;
            synchronized (this) {
                list = callbacks.toArray(new XC_MethodHook[0]);
                original = backup;
            }
            if (original == null) throw new IllegalStateException("Missing method backup");
            boolean isStatic = target instanceof Method && Modifier.isStatic(target.getModifiers());
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.method = target;
            p.thisObject = isStatic ? null : raw[0];
            p.args = isStatic ? raw : Arrays.copyOfRange(raw, 1, raw.length);
            int entered = 0;
            for (XC_MethodHook cb : list) {
                try { cb.beforeHookedMethod(p); }
                catch (Throwable e) {
                    Log.e("ChromeXt-Zygisk", "before callback failed", e);
                    p.result = null; p.throwable = null; p.early = false;
                }
                entered++;
                if (p.early) break;
            }
            if (!p.early) {
                try { p.result = original.invoke(p.thisObject, p.args); }
                catch (InvocationTargetException e) { p.throwable = e.getCause(); }
                catch (Throwable e) { p.throwable = e; }
            }
            for (int i = entered - 1; i >= 0; i--) {
                Object result = p.result; Throwable error = p.throwable; boolean early = p.early;
                try { list[i].afterHookedMethod(p); }
                catch (Throwable e) {
                    p.result = result; p.throwable = error; p.early = early;
                    Log.e("ChromeXt-Zygisk", "after callback failed", e);
                }
            }
            return p.getResultOrThrowable();
        }
    }
}
