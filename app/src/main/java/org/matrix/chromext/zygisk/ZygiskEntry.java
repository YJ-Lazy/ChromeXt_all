package org.matrix.chromext.zygisk;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.matrix.chromext.BrowserRuntime;
import org.matrix.chromext.Resource;
import org.matrix.chromext.utils.LogRecorder;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ZygiskEntry {
    private static final AtomicBoolean started = new AtomicBoolean();
    private ZygiskEntry() {}

    public static boolean install(String process, byte[] resources, byte[] library) {
        try {
            if (!XposedBridge.initialize()) {
                Log.e("ChromeXt-Zygisk", "[CX-ZYGISK] LSPlant initialization failed");
                return false;
            }
            Method attach = XposedBridge.applicationAttachNative();
            if (attach == null) throw new IllegalStateException("Application.attach unavailable");
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.args[0];
                    if (!process.equals(context.getPackageName()) || !started.compareAndSet(false, true)) return;
                    try {
                        Application app = (Application) p.thisObject;
                        Log.i("ChromeXt-Zygisk", "[CX-ZYGISK] Application.attach reached: " + process);
                        // JS assets do not depend on PackageManager, hidden resource APIs, or writable APK files.
                        Resource.INSTANCE.initArchive(resources);
                        try {
                            java.io.File cache = new java.io.File(context.getCodeCacheDir(), "chromext-resources.apk");
                            android.util.AtomicFile file = new android.util.AtomicFile(cache);
                            java.io.FileOutputStream out = file.startWrite();
                            try { out.write(resources); file.finishWrite(out); }
                            catch (Throwable e) { file.failWrite(out); throw e; }
                            Resource.INSTANCE.init(cache.getAbsolutePath());
                        } catch (Throwable e) {
                            Log.w("ChromeXt-Zygisk", "[CX-RES] optional Android UI resource cache unavailable", e);
                        }
                        org.matrix.chromext.ScriptPolicy.INSTANCE.initialize(library);
                        LogRecorder.INSTANCE.initialize(app);
                        org.matrix.chromext.utils.Log.INSTANCE.i("[CX-ZYGISK] standalone runtime; process=" + process);
                        BrowserRuntime.INSTANCE.load(process, process, app.getClassLoader(), app);
                    } catch (Throwable e) {
                        Log.e("ChromeXt-Zygisk", "[CX-ZYGISK] standalone resource or browser initialization failed", e);
                    }
                }
            });
            Log.i("ChromeXt-Zygisk", "[CX-ZYGISK] Application.attach hook installed");
            return true;
        } catch (Throwable e) {
            Log.e("ChromeXt-Zygisk", "[CX-ZYGISK] bootstrap failed", e);
            return false;
        }
    }
}
