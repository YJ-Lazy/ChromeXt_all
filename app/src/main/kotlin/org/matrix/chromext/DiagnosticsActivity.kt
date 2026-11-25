package org.matrix.chromext

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.matrix.chromext.utils.LogRecorder

class DiagnosticsActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var logText: TextView
  private lateinit var recording: Switch

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    actionBar?.hide()
    window.setDecorFitsSystemWindows(true)
    title = "ChromeXt 诊断日志"

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    root.addView(TextView(this).apply {
      text = "ChromeXt 诊断日志"
      textSize = 22f
    })
    root.addView(TextView(this).apply {
      text = "通过 Root 读取 ChromeXt Logcat 与 LSPosed 日志。"
      textSize = 14f
    })
    status = TextView(this).apply { textSize = 13f }
    root.addView(status)

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    actions.addView(actionButton("刷新") { refreshLogs() })
    actions.addView(actionButton("分享日志") { shareLogs() })
    actions.addView(actionButton("删除日志") { deleteLogs() })
    root.addView(actions)
    root.addView(actionButton("一键复制适配日志") {
      val report = "Android " + android.os.Build.VERSION.RELEASE + " / SDK " + android.os.Build.VERSION.SDK_INT +
          "\\nModule " + BuildConfig.VERSION_NAME + "\\n" + logText.text.toString()
      val clipboard = getSystemService(android.content.ClipboardManager::class.java)
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("浏览器适配日志", report))
      android.widget.Toast.makeText(this, "适配日志已复制", android.widget.Toast.LENGTH_SHORT).show()
    })

    val scroll = ScrollView(this)
    logText = TextView(this).apply {
      textSize = 11f
      typeface = Typeface.MONOSPACE
      setTextIsSelectable(true)
      setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    scroll.addView(logText)
    root.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    root.setOnApplyWindowInsetsListener { v, insets ->
      val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
      v.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom)
      insets
    }
    root.requestApplyInsets()
    refreshLogs()
  }

  private fun actionButton(label: String, action: () -> Unit): Button =
      Button(this).apply {
        text = label
        setOnClickListener { action() }
      }

  private fun refreshLogs() {
    status.text = "正在读取 Root 日志…"
    Thread {
      val result = LogRecorder.read(this)
      runOnUiThread { logText.text = result; status.text = if (result.isBlank()) "暂无新日志" else "Root 读取结束" }
    }.start()
  }

  private fun deleteLogs() {
    android.app.AlertDialog.Builder(this)
      .setTitle("删除诊断日志")
      .setMessage("清除本模块已读取的历史日志，新日志仍会继续显示。不会删除其他模块或系统日志。")
      .setNegativeButton("取消", null)
      .setPositiveButton("删除") { _, _ ->
        status.text = "正在清除历史日志…"
        Thread {
          val ok = LogRecorder.clear(this)
          runOnUiThread {
            if (ok) logText.text = ""
            status.text = if (ok) "历史日志已清除" else "清除失败，请检查 Root 权限"
          }
        }.start()
      }.show()
  }

  private fun shareLogs() {
    startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_SUBJECT, "ChromeXt 浏览器适配日志")
          putExtra(Intent.EXTRA_TEXT, logText.text.toString().takeLast(200000))
        },
        "分享诊断日志",
    ))
  }
}
