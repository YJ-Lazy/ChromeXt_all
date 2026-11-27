package org.matrix.chromext.utils

import android.content.Context
import android.util.Log as AndroidLog
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import org.matrix.chromext.BuildConfig

object Log {
  private const val TAG = "ChromeXt-Zygisk"
  private var lastToast: WeakReference<Toast>? = null

  fun i(msg: String) {
    AndroidLog.i(TAG, msg)
    AndroidLog.i("CXDiagnostics", msg.substringBefore("?"))
    LogRecorder.write("INFO", msg.substringBefore("?"))
    XposedBridge.log("ChromeXt logging: " + msg)
  }

  fun d(msg: String, full: Boolean = false) {
    if (BuildConfig.DEBUG) {
      if (!full && msg.length > 300) {
        AndroidLog.d(TAG, msg.take(300) + " ...")
      } else {
        AndroidLog.d(TAG, msg)
      }
    }
    AndroidLog.d("CXDiagnostics", msg.substringBefore("?"))
    LogRecorder.write("DEBUG", msg.substringBefore("?"))
  }

  fun w(msg: String) {
    AndroidLog.w(TAG, msg)
    AndroidLog.w("CXDiagnostics", msg.substringBefore("?"))
    LogRecorder.write("WARN", msg.substringBefore("?"))
  }

  fun e(msg: String) {
    AndroidLog.e(TAG, msg)
    AndroidLog.e("CXDiagnostics", msg.substringBefore("?"))
    LogRecorder.write("ERROR", msg.substringBefore("?"))
    XposedBridge.log("ChromeXt error: " + msg)
  }

  fun ex(thr: Throwable, msg: String = "") {
    AndroidLog.e(TAG, msg, thr)
    AndroidLog.e("CXDiagnostics", msg, thr)
    LogRecorder.write("ERROR", msg.substringBefore("?") + "\n" + AndroidLog.getStackTraceString(thr))
    XposedBridge.log("ChromeXt exception caught: [${msg}] " + AndroidLog.getStackTraceString(thr))
  }

  fun toast(context: Context, msg: String) {
    this.lastToast?.get()?.cancel()
    val duration = Toast.LENGTH_SHORT
    val toast = Toast.makeText(context, msg, duration)
    toast.show()
    this.lastToast = WeakReference(toast)
  }
}
