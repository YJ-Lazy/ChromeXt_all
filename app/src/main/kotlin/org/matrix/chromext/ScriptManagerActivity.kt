package org.matrix.chromext

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONArray

class ScriptManagerActivity : Activity() {
  private lateinit var list: LinearLayout
  private lateinit var summary: TextView
  private lateinit var search: EditText
  private val prefs by lazy { getSharedPreferences("script_policy", 0) }
  private var browserTab = false
  private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
  private val ink get() = Color.parseColor(if (dark) "#E8EAF2" else "#18243A")
  private val muted get() = Color.parseColor(if (dark) "#AAB4C8" else "#607087")
  private val surface get() = Color.parseColor(if (dark) "#222B3C" else "#FFFFFF")
  private val accent get() = Color.parseColor(if (dark) "#9BB8FF" else "#345DC5")
  private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
  private fun panel(color: Int, radius: Int = 16) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
  }
  private fun label(value: String, size: Float = 14f, strong: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(if (strong) ink else muted)
    if (strong) setTypeface(null, Typeface.BOLD)
  }
  private fun action(value: String, run: () -> Unit) = Button(this).apply {
    text = value; isAllCaps = false; setTextColor(accent); setOnClickListener { run() }
  }
  private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  private fun card(): LinearLayout = column().apply {
    background = panel(surface); setPadding(dp(16),dp(14),dp(16),dp(14))
    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) }
  }
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    actionBar?.hide()
    val root = column().apply {
      setBackgroundColor(Color.parseColor(if (dark) "#151C29" else "#F1F4FA"))
      setPadding(dp(16),dp(12),dp(16),dp(12))
    }
    root.addView(label("ChromeXt",28f,true))
    root.addView(label("统一脚本管理 · LSPosed"))
    summary = label("",15f,true)
    root.addView(card().apply { addView(summary); addView(label("配置将在下次页面注入时生效")) })
    search = EditText(this).apply { hint = "搜索脚本或浏览器"; setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(muted) }
    root.addView(search)
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    list = column()
    root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
    val nav = LinearLayout(this)
    fun tab(name: String, run: () -> Unit) { nav.addView(action(name,run),LinearLayout.LayoutParams(0,-2,1f)) }
    tab("脚本") { browserTab = false; refresh() }
    tab("浏览器") { browserTab = true; refresh() }
    tab("日志") { startActivity(Intent(this,DiagnosticsActivity::class.java)) }
    tab("刷新") { refresh() }
    root.addView(nav)
    setContentView(root)
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      window.setDecorFitsSystemWindows(false)
      root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(12)+bars.bottom)
        insets
      }
      root.requestApplyInsets()
    }
  }
  override fun onResume() { super.onResume(); refresh() }
  private fun browsers(): List<String> {
    val detected = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),0).map { it.activityInfo.packageName }
    return (resources.getStringArray(R.array.xposedscope).toList()+detected).distinct().filter {
      packageManager.getLaunchIntentForPackage(it)!=null || prefs.contains("inventory:$it")
    }.sortedBy { browserName(it) }
  }
  private fun browserName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
  private fun installed(pkg: String,id: String): Boolean {
    val ids = JSONArray(prefs.getString("inventory:$pkg","[]"))
    return (0 until ids.length()).any { ids.optString(it)==id }
  }
  private fun enabled(pkg: String,id: String) = prefs.getBoolean("enabled:$pkg:$id",true) && (installed(pkg,id)||prefs.getBoolean("assigned:$pkg:$id",false))
  private fun toggle(pkg: String,id: String,checked: Boolean) {
    prefs.edit().putBoolean("assigned:$pkg:$id",checked).putBoolean("enabled:$pkg:$id",checked).apply()
  }
  private fun switchFor(pkg: String,id: String,title: String) = Switch(this).apply {
    text = title; setTextColor(ink); isChecked = enabled(pkg,id)
    setOnCheckedChangeListener { _,checked -> toggle(pkg,id,checked); updateSummary() }
  }
  private fun ids() = prefs.all.keys.filter { it.startsWith("source:") }.map { it.removePrefix("source:") }.sorted()
  private fun updateSummary() {
    val ids = ids(); val browsers = browsers()
    summary.text = "${ids.size} 个脚本   ·   ${browsers.size} 个浏览器   ·   ${ids.count { id -> browsers.any { enabled(it,id) } }} 已启用"
  }
  private fun refresh() {
    if (!::list.isInitialized) return
    list.removeAllViews()
    val browsers = browsers(); val ids = ids()
    updateSummary()
    val q = search.text.toString().trim()
    if (browserTab) {
      for (pkg in browsers.filter { browserName(it).contains(q,true)||it.contains(q,true) }) {
        list.addView(card().apply {
          addView(label(browserName(pkg),19f,true)); addView(label(pkg))
          addView(label(if (prefs.contains("inventory:$pkg")) "已同步过脚本清单" else "尚未同步 · 请检查 LSPosed 作用域"))
          packageManager.getLaunchIntentForPackage(pkg)?.let { launch -> addView(action("打开浏览器") { startActivity(launch) }) }
          for (id in ids) addView(switchFor(pkg,id,id.substringAfterLast(':')))
          addView(action("适配诊断") { startActivity(Intent(this@ScriptManagerActivity,DiagnosticsActivity::class.java)) })
        })
      }
    } else {
      for (id in ids.filter { it.contains(q,true) }) {
        val source = prefs.getString("source:$id","").orEmpty()
        val version = source.lineSequence().firstOrNull { it.trimStart().startsWith("// @version ") }?.substringAfter("@version")?.trim()
        list.addView(card().apply {
          addView(label(id.substringAfterLast(':'),19f,true))
          addView(label(id.substringBeforeLast(':') + (version?.let { " · v$it" } ?: "")))
          addView(label("适用浏览器",14f,true))
          for (pkg in browsers) addView(switchFor(pkg,id,browserName(pkg)+if(installed(pkg,id)) " · 已安装" else " · 分配"))
          addView(action("查看源码") {
            val text = TextView(this@ScriptManagerActivity).apply { this.text = source; setTextIsSelectable(true); setPadding(dp(16),dp(12),dp(16),dp(12)) }
            AlertDialog.Builder(this@ScriptManagerActivity).setTitle(id.substringAfterLast(':')).setView(ScrollView(this@ScriptManagerActivity).apply { addView(text) }).setPositiveButton("关闭",null).show()
          })
        })
      }
    }
    if (list.childCount == 0) list.addView(card().apply { addView(label(if(q.isNotEmpty()) "没有匹配结果" else if(browserTab) "未发现浏览器" else "暂无脚本，请在浏览器安装脚本后刷新网页。",16f,true)) })
  }
}
