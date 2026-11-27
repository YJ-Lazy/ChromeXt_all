package org.matrix.chromext.zygisk;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.matrix.chromext.BrowserRuntime;
import org.matrix.chromext.BuildConfig;
import org.matrix.chromext.Resource;
import org.matrix.chromext.utils.LogRecorder;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ZygiskEntry {
    private static final AtomicBoolean started = new AtomicBoolean();
    private ZygiskEntry() {}

    public static boolean install(String process) {
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
                        Context manager = context.createPackageContext(BuildConfig.APPLICATION_ID, 0);
                        ApplicationInfo info = manager.getApplicationInfo();
                        Resource.INSTANCE.init(info.sourceDir);
                        LogRecorder.INSTANCE.initialize(app);
                        org.matrix.chromext.utils.Log.INSTANCE.i("[CX-ZYGISK] standalone runtime; process=" + process);
                        BrowserRuntime.INSTANCE.load(process, process, app.getClassLoader(), app);
                    } catch (Throwable e) {
                        Log.e("ChromeXt-Zygisk", "[CX-ZYGISK] manager or browser initialization failed", e);
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
