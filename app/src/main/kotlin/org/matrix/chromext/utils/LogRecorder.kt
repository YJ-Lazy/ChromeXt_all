package org.matrix.chromext.utils

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object LogRecorder {
  @Volatile private var browserContext: Context? = null
  private val lock = Any()
  private val pending = ArrayDeque<Pair<String,String>>()
  private val worker = ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(256),ThreadPoolExecutor.DiscardOldestPolicy())
  fun initialize(context: Context) {
    synchronized(lock) {
      if (browserContext != null) return
      browserContext = context.applicationContext ?: context
      while (pending.isNotEmpty()) {
        val (level,message) = pending.removeFirst()
        write(level,message)
      }
    }
    write("INFO","Hook recorder initialized")
  }
  fun isEnabled(context: Context? = null): Boolean = true
  fun setEnabled(context: Context, enabled: Boolean) {}
  fun write(level: String, message: String, context: Context? = null) {
    val ctx = context ?: browserContext
    if (ctx == null) {
      synchronized(lock) {
        if (pending.size >= 128) pending.removeFirst()
        pending.addLast(level to message.take(8192))
      }
      return
    }
    worker.execute {
      runCatching {
        ctx.contentResolver.call(Uri.parse("content://org.matrix.chromext.diagnostics"),"append",null,
          Bundle().apply { putString("level",level); putString("message",message.take(8192)) })
      }.onFailure { android.util.Log.e("CXDiagnostics","Diagnostic IPC failed",it) }
    }
  }
  private fun file(context: Context) = File(context.filesDir,"module-diagnostics.log")
  internal fun writeLocal(context: Context, level: String, message: String) {
    synchronized(lock) {
      val f = file(context)
      if (f.length() > 512 * 1024) f.writeText(f.readText().takeLast(200000))
      val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date())
      f.appendText("$stamp [$level] ${message.take(8192)}\n")
    }
  }
  fun read(context: Context? = null): String = synchronized(lock) {
    if (context == null) "" else runCatching { file(context).takeIf { it.exists() }?.readText().orEmpty() }.getOrElse { "读取失败：$it" }
  }
  fun readRoot(context: Context): String {
    val local = read(context)
    val output = File.createTempFile("root-diagnostics-", ".txt", context.cacheDir)
    try {
      val command = "echo '--- ChromeXt Logcat ---'; logcat -d -t 5000 -v threadtime -s CXDiagnostics:V; echo '--- LSPosed module logs ---'; for f in /data/adb/lspd/log/modules*.log; do [ -f \"\$f\" ] && grep -F org.matrix.chromext \"\$f\" | tail -n 1000; done"
      val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).redirectOutput(output).start()
      if (!process.waitFor(45, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return local + "\nRoot 读取超时"
      }
      val extra = output.inputStream().bufferedReader().use { it.readText().takeLast(500000) }
      return "--- 模块独立日志 ---\n" + local + "\n" + (if (process.exitValue() == 0) extra else "Root 读取失败或部分不可用：\n$extra")
    } catch (e: Exception) {
      return local + "\nRoot 读取失败：${e.message}"
    } finally { output.delete() }
  }
  fun clear(context: Context? = null): Boolean = synchronized(lock) {
    if (context == null) false else runCatching { val f = file(context); !f.exists() || f.delete() }.getOrDefault(false)
  }
}
