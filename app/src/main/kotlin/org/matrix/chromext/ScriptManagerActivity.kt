package org.matrix.chromext

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.*
import org.json.JSONArray

class ScriptManagerActivity : Activity() {
  private lateinit var list: LinearLayout
  private val prefs by lazy { getSharedPreferences("script_policy", 0) }
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    title = "脚本管理"
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val p = (16 * resources.displayMetrics.density).toInt()
      setPadding(p,p,p,p)
    }
    root.addView(TextView(this).apply { text = "脚本与浏览器"; textSize = 24f })
    root.addView(TextView(this).apply { text = "打开浏览器并访问网页后同步脚本。开关在下次页面注入时生效。" })
    root.addView(Button(this).apply { text = "刷新脚本"; setOnClickListener { refresh() } })
    root.addView(Button(this).apply { text = "诊断日志"; setOnClickListener { startActivity(Intent(this@ScriptManagerActivity, DiagnosticsActivity::class.java)) } })
    list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
    setContentView(root)
  }
  override fun onResume() { super.onResume(); refresh() }
  private fun refresh() {
    list.removeAllViews()
    val browsers = resources.getStringArray(R.array.xposedscope).filter {
      packageManager.getLaunchIntentForPackage(it) != null || prefs.contains("inventory:$it")
    }
    val ids = prefs.all.keys.filter { it.startsWith("source:") }.map { it.removePrefix("source:") }.sorted()
    if (ids.isEmpty()) list.addView(TextView(this).apply { text = "暂无同步脚本，请先在浏览器安装脚本并刷新网页。" })
    for (id in ids) {
      list.addView(TextView(this).apply { text = id; textSize = 20f })
      for (pkg in browsers) {
        val inventory = JSONArray(prefs.getString("inventory:$pkg", "[]"))
        val installed = (0 until inventory.length()).any { inventory.getString(it) == id }
        val assigned = "assigned:$pkg:$id"
        val enabled = "enabled:$pkg:$id"
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
        list.addView(Switch(this).apply {
          text = label + if (installed) " · 已安装" else " · 分配"
          isChecked = prefs.getBoolean(enabled,true) && (installed || prefs.getBoolean(assigned,false))
          setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(assigned,checked).putBoolean(enabled,checked).apply()
          }
        })
      }
    }
  }
}
