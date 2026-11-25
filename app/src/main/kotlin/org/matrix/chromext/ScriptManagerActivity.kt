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
    root.addView(label("ScriptHub",28f,true))
    root.addView(label("统一脚本管理 · LSPosed"))
    summary = label("",15f,true)
    root.addView(card().apply { addView(summary); addView(label("配置将在下次页面注入时生效")) })
    search = EditText(this).apply { hint = "搜索脚本或浏览器"; setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(muted) }
    root.addView(search)
    root.addView(action("导入脚本") { importDialog() })
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

  private fun importDialog() {
    val input = EditText(this).apply { hint = "脚本链接或目标网站"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
    AlertDialog.Builder(this).setTitle("导入脚本").setView(input)
      .setMessage("支持 .user.js、Greasy Fork、ScriptCat、GitHub 链接；普通网站可搜索适用脚本。")
      .setNegativeButton("取消",null).setNeutralButton("搜索脚本") { _,_ -> searchSite(input.text.toString()) }
      .setPositiveButton("解析并导入") { _,_ -> downloadScript(input.text.toString()) }.show()
  }
  private fun searchSite(value: String) {
    val site = android.net.Uri.parse(if (value.contains("://")) value else "https://$value").host.orEmpty()
    if (site.isBlank()) { Toast.makeText(this,"请输入有效网站",Toast.LENGTH_SHORT).show(); return }
    val url = "https://greasyfork.org/zh-CN/scripts/by-site/" + android.net.Uri.encode(site)
    runCatching { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))) }
      .onFailure { Toast.makeText(this,"无法打开脚本搜索",Toast.LENGTH_SHORT).show() }
  }
  private fun downloadScript(value: String) {
    val raw = value.trim()
    val uri = android.net.Uri.parse(if (raw.contains("://")) raw else "https://$raw")
    val host = uri.host.orEmpty().lowercase()
    val parts = uri.pathSegments
    val target = when {
      host == "greasyfork.org" || host == "www.greasyfork.org" -> {
        val i = parts.indexOf("scripts")
        val id = parts.getOrNull(i+1)?.substringBefore('-')?.takeIf { i >= 0 && it.all(Char::isDigit) && it.isNotEmpty() }
        if (id == null) null else "https://greasyfork.org/scripts/$id/code/script.user.js"
      }
      host == "scriptcat.org" -> {
        val i = parts.indexOf("script")
        val id = parts.getOrNull(i+1)?.takeIf { i >= 0 && it.isNotEmpty() && it.all(Char::isDigit) }
        if (id == null) null else "https://scriptcat.org/scripts/code/$id/script.user.js"
      }
      host == "github.com" && parts.size >= 5 && parts[2] == "blob" ->
        "https://raw.githubusercontent.com/" + (parts.take(2)+parts.drop(3)).joinToString("/") { android.net.Uri.encode(it) }
      uri.path.orEmpty().endsWith(".js",true) || host == "raw.githubusercontent.com" || host == "update.greasyfork.org" -> uri.toString()
      else -> null
    }
    if (target == null) { searchSite(raw); return }
    val progress = AlertDialog.Builder(this).setMessage("正在下载脚本…").setCancelable(false).show()
    Thread {
      val result = runCatching {
        var url = java.net.URL(target)
        var source: String? = null
        repeat(6) {
          if (source != null) return@repeat
          require(url.protocol == "https") { "仅支持 HTTPS 下载" }
          val c = url.openConnection() as java.net.HttpURLConnection
          try {
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 15000
            val status = c.responseCode
            if (status in 300..399) {
              url = java.net.URL(url, requireNotNull(c.getHeaderField("Location")))
            } else {
              require(status in 200..299) { "HTTP $status" }
              val out = java.io.ByteArrayOutputStream()
              c.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                  val n = stream.read(buffer)
                  if (n < 0) break
                  require(out.size()+n <= 180000) { "脚本超过当前同步大小限制" }
                  out.write(buffer,0,n)
                }
              }
              source = out.toString("UTF-8").removePrefix("\uFEFF")
            }
          } finally { c.disconnect() }
        }
        requireNotNull(source) { "重定向次数过多" }
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        progress.dismiss()
        result.onSuccess { previewImport(it) }.onFailure {
          org.matrix.chromext.utils.LogRecorder.writeLocal(this,"ERROR","[CX-IMPORT] "+it.javaClass.simpleName+": "+it.message)
          AlertDialog.Builder(this).setTitle("导入失败").setMessage(it.message).setPositiveButton("关闭",null).show()
        }
      }
    }.start()
  }
  private fun previewImport(source: String) {
    val header = Regex("""// ==UserScript==\r?\n([\s\S]*?)\r?\n// ==/UserScript==\s+""").find(source)
    val metadata = header?.groupValues?.get(1)?.lineSequence()?.mapNotNull {
      Regex("""^//\s+@([\w-]+)\s+(.+)""").matchEntire(it)?.let { m -> m.groupValues[1] to m.groupValues[2] }
    }?.toList().orEmpty()
    fun field(key: String, fallback: String) = metadata.lastOrNull { it.first == key }?.second ?: fallback
    val matches = metadata.filter { it.first == "match" || it.first == "include" }.map { it.second }
    if (header == null || matches.isEmpty()) {
      AlertDialog.Builder(this).setTitle("无效脚本").setMessage("缺少用户脚本元数据或匹配规则").setPositiveButton("关闭",null).show(); return
    }
    val name = field("name","sample").replace(":","")
    val id = field("namespace","ChromeXt")+":"+name
    if (prefs.contains("source:$id")) {
      AlertDialog.Builder(this).setTitle("脚本已存在").setMessage("当前同步机制不支持可靠覆盖更新，请管理已有脚本。").setPositiveButton("关闭",null).show(); return
    }
    val targets = browsers()
    val selected = BooleanArray(targets.size)
    val grants = metadata.filter { it.first == "grant" }.joinToString("\n") { it.second }
    AlertDialog.Builder(this).setTitle(name+" · "+field("version","未知版本"))
      .setMessage("匹配规则：\n"+matches.joinToString("\n")+"\n\n声明权限：\n"+grants.ifBlank { "无" }+"\n\n仅导入可信脚本。")
      .setNeutralButton("查看源码") { _,_ ->
        val view = TextView(this).apply { text = source; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("脚本源码").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("返回") { _,_ -> previewImport(source) }.show()
      }.setNegativeButton("取消",null).setPositiveButton("选择浏览器") { _,_ ->
        AlertDialog.Builder(this).setTitle("分配浏览器（可稍后设置）")
          .setMultiChoiceItems(targets.map { browserName(it) }.toTypedArray(),selected) { _,i,checked -> selected[i]=checked }
          .setNegativeButton("取消",null).setPositiveButton("确认导入") { _,_ ->
            val edit = prefs.edit().putString("source:$id",source)
            targets.forEachIndexed { i,pkg -> if (selected[i]) edit.putBoolean("assigned:$pkg:$id",true).putBoolean("enabled:$pkg:$id",true) }
            edit.apply()
            org.matrix.chromext.utils.LogRecorder.writeLocal(this,"INFO","[CX-IMPORT] saved script: $id")
            browserTab=false; search.setText(""); refresh()
          }.show()
      }.show()
  }

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
    if (list.childCount == 0) list.addView(card().apply { addView(label(if(q.isNotEmpty()) "没有匹配结果" else if(browserTab) "未发现浏览器" else "暂无脚本，点击导入脚本或在浏览器安装。",16f,true)) })
  }
}
