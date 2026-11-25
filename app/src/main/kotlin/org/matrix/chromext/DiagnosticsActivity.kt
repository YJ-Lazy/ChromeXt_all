package org.matrix.chromext

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.view.View
import android.os.Build
import android.widget.TextView
import org.matrix.chromext.utils.LogRecorder

class DiagnosticsActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var logText: TextView
  private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
  private val canvasColor get() = Color.parseColor(if(dark) "#101625" else "#F3F5FB")
  private val surface get() = Color.parseColor(if(dark) "#222B3C" else "#FFFFFF")
  private val soft get() = Color.parseColor(if(dark) "#263653" else "#E8EEFF")
  private val ink get() = Color.parseColor(if(dark) "#E8EAF2" else "#18243A")
  private val muted get() = Color.parseColor(if(dark) "#AAB4C8" else "#607087")
  private val accent get() = Color.parseColor(if(dark) "#9BB8FF" else "#345DC5")
  private fun panel(color:Int,radius:Int=16)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
  private fun label(value:String,size:Float,bold:Boolean=false)=TextView(this).apply {
    text=value; textSize=size; setTextColor(if(bold) ink else muted)
    if(bold) setTypeface(null,Typeface.BOLD)
  }
  private var generation=0

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    actionBar?.hide()
    title = "ScriptHub 诊断日志"
    window.statusBarColor=canvasColor
    window.navigationBarColor=canvasColor
    if(Build.VERSION.SDK_INT>=30) {
      window.setDecorFitsSystemWindows(false)
      val flags=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
      window.insetsController?.setSystemBarsAppearance(if(dark) 0 else flags,flags)
    } else if(Build.VERSION.SDK_INT>=26) {
      window.decorView.systemUiVisibility=if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    val root=LinearLayout(this).apply {
      orientation=LinearLayout.VERTICAL; setBackgroundColor(canvasColor)
      setPadding(dp(16),dp(12),dp(16),dp(12))
    }
    root.addView(LinearLayout(this).apply {
      gravity=android.view.Gravity.CENTER_VERTICAL
      addView(actionButton("‹") { finish() },LinearLayout.LayoutParams(dp(44),dp(44)).apply { rightMargin=dp(12) })
      addView(LinearLayout(this@DiagnosticsActivity).apply {
        orientation=LinearLayout.VERTICAL
        addView(label("诊断日志",25f,true)); addView(label("ScriptHub · v"+BuildConfig.VERSION_NAME,12f))
      },LinearLayout.LayoutParams(0,-2,1f))
    })
    root.addView(LinearLayout(this).apply {
      orientation=LinearLayout.VERTICAL; background=panel(soft,20); setPadding(dp(16),dp(14),dp(16),dp(14))
      addView(label("日志读取状态",12f,true))
      status=label("正在读取…",18f,true).apply { setTextColor(accent) }
      addView(status)
      addView(label("默认读取模块日志，无需 Root 授权",12f))
    },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(12) })
    val actions=LinearLayout(this)
    fun small(name:String,run:()->Unit) {
      actions.addView(actionButton(name,run),LinearLayout.LayoutParams(0,dp(46),1f).apply { leftMargin=dp(3); rightMargin=dp(3) })
    }
    small("刷新") { refreshLogs() }
    small("分享") { shareLogs() }
    small("删除") { deleteLogs() }
    root.addView(actions)
    root.addView(actionButton("Root 增强读取") { refreshLogs(true) },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(8) })
    root.addView(actionButton("一键复制适配日志") {
      val report="Android "+Build.VERSION.RELEASE+" / SDK "+Build.VERSION.SDK_INT+"\nModule "+BuildConfig.VERSION_NAME+"\n"+logText.text
      val clipboard=getSystemService(android.content.ClipboardManager::class.java)
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("浏览器适配日志",report))
      android.widget.Toast.makeText(this,"适配日志已复制",android.widget.Toast.LENGTH_SHORT).show()
    }.apply { setTextColor(Color.WHITE); background=panel(Color.parseColor("#345DC5"),14) },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(8); bottomMargin=dp(12) })
    val logCard=LinearLayout(this).apply {
      orientation=LinearLayout.VERTICAL; background=panel(surface,20); setPadding(dp(14),dp(12),dp(14),dp(12))
      addView(label("日志内容",14f,true))
    }
    val scroll=ScrollView(this).apply { isFillViewport=true }
    logText=TextView(this).apply {
      textSize=12f; typeface=Typeface.MONOSPACE; setTextColor(ink); setTextIsSelectable(true)
      setPadding(0,dp(10),0,dp(8))
    }
    scroll.addView(logText)
    logCard.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    root.addView(logCard,LinearLayout.LayoutParams(-1,0,1f))
    setContentView(root)
    if(Build.VERSION.SDK_INT>=30) {
      root.setOnApplyWindowInsetsListener { v,insets ->
        val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars())
        v.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(12)+bars.bottom); insets
      }
      root.requestApplyInsets()
    }
    refreshLogs()
  }

  private fun actionButton(label: String, action: () -> Unit): Button =
      Button(this).apply {
        text=label; isAllCaps=false; textSize=14f; setTextColor(accent)
        stateListAnimator=null; backgroundTintList=null
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22345DC5),panel(soft,14),null)
        minHeight=dp(44); minimumHeight=dp(44); setPadding(dp(8),dp(6),dp(8),dp(6))
        setOnClickListener { action() }
      }

  private fun refreshLogs(rootMode: Boolean = false) {
    val token=++generation
    status.text = if (rootMode) "正在读取 Root 增强日志…" else "正在读取模块日志…"
    Thread {
      val result = if (rootMode) LogRecorder.readRoot(this) else LogRecorder.read(this)
      runOnUiThread { if(token==generation && !isDestroyed) { logText.text = result; status.text = if (result.isBlank()) "暂无新日志" else if(rootMode) "增强读取完成" else "读取完成" } }
    }.start()
  }

  private fun deleteLogs() {
    android.app.AlertDialog.Builder(this)
      .setTitle("删除诊断日志")
      .setMessage("删除本模块保存的历史日志，新日志仍会继续显示。不会删除其他模块或系统日志；Root 增强读取仍可能显示原始历史记录。")
      .setNegativeButton("取消", null)
      .setPositiveButton("删除") { _, _ ->
        ++generation
        status.text = "正在清除历史日志…"
        Thread {
          val ok = LogRecorder.clear(this)
          runOnUiThread {
            if (ok) logText.text = ""
            status.text = if (ok) "历史日志已清除" else "清除失败，请重试"
          }
        }.start()
      }.show()
  }

  private fun shareLogs() {
    startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_SUBJECT, "ScriptHub 浏览器适配日志")
          putExtra(Intent.EXTRA_TEXT, logText.text.toString().takeLast(200000))
        },
        "分享诊断日志",
    ))
  }
}
