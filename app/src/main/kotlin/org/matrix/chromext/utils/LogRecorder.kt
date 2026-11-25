package org.matrix.chromext.utils

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRecorder {
  @Volatile private var browserContext: Context? = null
  private val lock = Any()
  fun initialize(context: Context) {
    browserContext = context
    write("INFO", "Hook recorder initialized")
  }
  fun isEnabled(context: Context? = null): Boolean = true
  fun setEnabled(context: Context, enabled: Boolean) {}
  fun write(level: String, message: String, context: Context? = null) {
    val ctx = context ?: browserContext ?: return
    if (ctx.packageName != "com.quark.browser") return
    runCatching {
      synchronized(lock) {
        val dir = File(ctx.filesDir, "chromext-diagnostics")
        dir.mkdirs()
        val file = File(dir, "hook-${Process.myPid()}.log")
        if (file.length() > 512 * 1024) file.writeText("")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        file.appendText("$stamp [$level] ${message.take(4096)}\n")
      }
    }.onFailure { android.util.Log.e("ChromeXt", "Local diagnostic write failed", it) }
  }
  internal fun writeLocal(context: Context, level: String, message: String) = write(level, message, context)
  private fun root(): String {
    val cmd = """
      id
      echo '--- ChromeXt Logcat ---'
      logcat -d -t 5000 -v threadtime -s CXDiagnostics:V 2>&1
      echo '--- LSPosed module logs ---'
      grep -h -F 'org.matrix.chromext,XposedBridge' /data/adb/lspd/log/modules*.log 2>/dev/null | tail -n 200
    """.trimIndent()
    return runCatching {
      val process = java.lang.ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().use { it.readText() }
      val code = process.waitFor()
      if (code == 0) output.takeLast(200000) else "Root 操作失败 ($code): $output"
    }.getOrElse { "Root 读取失败：$it" }
  }
  fun read(context: Context? = null): String {
    val output = root()
    val previous = context?.getSharedPreferences("diagnostic_clear", 0)?.getString("snapshot", null)
    if (previous.isNullOrEmpty()) return output
    val oldLines = previous.lineSequence().toHashSet()
    return output.lineSequence().filter { it !in oldLines }.joinToString("\n")
  }
  fun clear(context: Context? = null): Boolean {
    val ctx = context ?: return false
    val snapshot = root()
    if (snapshot.startsWith("Root 操作失败") || snapshot.startsWith("Root 读取失败")) return false
    return ctx.getSharedPreferences("diagnostic_clear", 0).edit().putString("snapshot", snapshot).commit()
  }
}
