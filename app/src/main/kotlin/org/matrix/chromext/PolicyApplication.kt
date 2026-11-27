package org.matrix.chromext

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PolicyApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    PolicyPublisher.init(this)
  }
}

/** A single app-process publisher; the framework, not this process, owns the published data. */
internal object PolicyPublisher : XposedServiceHelper.OnServiceListener {
  private lateinit var app: Application
  private val executor = Executors.newSingleThreadScheduledExecutor()
  private var pending: ScheduledFuture<*>? = null
  private var service: XposedService? = null
  // SharedPreferences implementations may update their in-memory map before a failed commit.
  private var retryWrite = true
  private var prefs: SharedPreferences? = null
  @Volatile var status = "等待 LSPosed 共享服务"
    private set
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request() }

  fun init(application: Application) {
    app = application
    XposedServiceHelper.registerListener(this)
    if (unlocked()) connectPrefs()
    else {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          app.unregisterReceiver(this)
          connectPrefs()
        }
      }
      val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
      if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      else app.registerReceiver(receiver, filter)
    }
  }

  private fun unlocked() = app.getSystemService(UserManager::class.java)?.isUserUnlocked == true

  @Synchronized private fun connectPrefs() {
    if (prefs == null) {
      prefs = app.getSharedPreferences("script_policy", Context.MODE_PRIVATE)
      prefs?.registerOnSharedPreferenceChangeListener(listener)
    }
    request()
  }

  override fun onServiceBind(service: XposedService) {
    synchronized(this) { this.service = service; retryWrite = true }
    request()
  }

  override fun onServiceDied(service: XposedService) {
    synchronized(this) {
      if (this.service === service) {
        this.service = null
        status = "共享服务断开；浏览器保留已同步配置"
      }
    }
  }

  @Synchronized fun request() {
    if (!::app.isInitialized || !unlocked()) return
    status = "正在发布共享配置…"
    pending?.cancel(false)
    pending = executor.schedule({ publish() }, 120, TimeUnit.MILLISECONDS)
  }

  private fun publish() {
    val current = synchronized(this) { service }
    val local = synchronized(this) { prefs }
    if (current == null || local == null) {
      status = "共享服务未连接；仍可使用原同步接口"
      return
    }
    try {
      check(current.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) { "框架不支持共享存储" }
      val bytes = PolicySnapshot.encode(local.all)
      val hash = PolicySnapshot.digest(bytes)
      val remote = current.getRemotePreferences(PolicySnapshot.GROUP)
      val previous = remote.getString(PolicySnapshot.POINTER, "") ?: ""
      if (retryWrite || previous.substringAfter(':', "") != hash) {
        val slot = if (previous.startsWith("a:")) "b" else "a"
        // Write the inactive slot completely, then commit the small generation pointer.
        ParcelFileDescriptor.AutoCloseOutputStream(current.openRemoteFile("policy-$slot.json")).use { out ->
          out.channel.truncate(0)
          out.channel.position(0)
          out.write(bytes)
          out.flush()
          out.fd.sync()
        }
        check(remote.edit().putString(PolicySnapshot.POINTER, "$slot:$hash").commit()) { "共享版本提交失败" }
      }
      retryWrite = false
      status = "共享配置已发布 · " + hash.take(8)
    } catch (t: Throwable) {
      retryWrite = true
      status = "共享发布失败：" + (t.message ?: t.javaClass.simpleName)
      android.util.Log.e("ScriptHub", "Shared policy publication failed", t)
    }
  }
}
