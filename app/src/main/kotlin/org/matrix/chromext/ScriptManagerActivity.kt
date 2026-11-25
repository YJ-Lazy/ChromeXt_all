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
    val browsers = resources.getStringArray(R.array.xposedscope)
    for (pkg in browsers) {
      val launch = packageManager.getLaunchIntentForPackage(pkg)
      val inventory = prefs.getString("inventory:$pkg", null)
      if (launch == null && inventory == null) continue
      val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
      list.addView(TextView(this).apply { text = label; textSize = 20f })
      if (launch != null) list.addView(Button(this).apply { text = "打开 $label"; setOnClickListener { startActivity(launch) } })
      val ids = JSONArray(inventory ?: "[]")
      if (ids.length() == 0) list.addView(TextView(this).apply { text = "暂无同步脚本" })
      for (i in 0 until ids.length()) {
        val id = ids.getString(i)
        val key = "enabled:$pkg:$id"
        list.addView(Switch(this).apply {
          text = id
          isChecked = prefs.getBoolean(key,true)
          setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean(key,enabled).apply() }
        })
      }
    }
  }
}
