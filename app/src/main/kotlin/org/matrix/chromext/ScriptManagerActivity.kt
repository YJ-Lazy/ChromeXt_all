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
  private val navButtons = mutableListOf<Button>()
  private val canvasColor get() = Color.parseColor(if(dark) "#101625" else "#F3F5FB")
  private val soft get() = Color.parseColor(if(dark) "#263653" else "#E8EEFF")
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
    text = value; isAllCaps = false; textSize = 14f; setTextColor(accent)
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22345DC5),panel(soft,12),null)
    minHeight = dp(44); minimumHeight = dp(44); setPadding(dp(12),dp(8),dp(12),dp(8))
    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8); bottomMargin = dp(4) }
    setOnClickListener { run() }
  }
  private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  private fun card(): LinearLayout = column().apply {
    background = panel(surface,20); setPadding(dp(18),dp(18),dp(18),dp(18))
    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) }
  }
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    actionBar?.hide()
    val root = column().apply {
      setBackgroundColor(canvasColor)
      setPadding(dp(16),dp(12),dp(16),dp(12))
    }
    window.statusBarColor = canvasColor
    window.navigationBarColor = canvasColor
    if(android.os.Build.VERSION.SDK_INT >= 30) {
      val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
      window.insetsController?.setSystemBarsAppearance(if(dark) 0 else flags,flags)
    } else if(android.os.Build.VERSION.SDK_INT >= 26) {
      window.decorView.systemUiVisibility = if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    root.addView(LinearLayout(this).apply {
      gravity = android.view.Gravity.CENTER_VERTICAL
      addView(ImageView(this@ScriptManagerActivity).apply { setImageResource(R.mipmap.ic_launcher) },LinearLayout.LayoutParams(dp(48),dp(48)).apply { rightMargin=dp(12) })
      addView(column().apply { addView(label("ScriptHub",27f,true)); addView(label("跨浏览器脚本管理",12f)) },LinearLayout.LayoutParams(0,-2,1f))
      addView(label("v"+BuildConfig.VERSION_NAME,12f).apply { setTextColor(accent) })
    })
    root.addView(View(this),LinearLayout.LayoutParams(1,dp(14)))
    summary = label("",15f,true)
    root.addView(card().apply { background=panel(soft,20); addView(label("脚本库概览",12f,true)); addView(summary); addView(label("配置将在下次页面注入时生效",12f)) })
    search = EditText(this).apply { hint = "搜索脚本或浏览器"; setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(muted); background=panel(surface,14); setPadding(dp(16),dp(12),dp(16),dp(12)); minHeight=dp(48) }
    root.addView(search)
    root.addView(LinearLayout(this).apply {
      addView(action("↓  导入脚本") { chooseImport() }.apply { setTextColor(Color.WHITE); background=panel(Color.parseColor("#345DC5"),14) },LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(6); topMargin=dp(10); bottomMargin=dp(12) })
      addView(action("↑  导出脚本") { selectExport() },LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(6); topMargin=dp(10); bottomMargin=dp(12) })
    })
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    list = column()
    root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
    val nav = LinearLayout(this).apply { background=panel(surface,20); setPadding(dp(4),dp(6),dp(4),dp(6)) }
    fun tab(name: String, run: () -> Unit) { val b=action(name,run); navButtons.add(b); nav.addView(b,LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(2); rightMargin=dp(2) }) }
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


  private var exportData: String? = null
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    // Keep large backup contents out of the Activity state Binder transaction.
    outState.putBoolean("exportPending", exportData != null)
  }
  private fun chooseImport() {
    AlertDialog.Builder(this).setTitle("导入脚本")
      .setItems(arrayOf("网址导入","本地文件（支持多选）")) { _,i ->
        if (i == 0) importDialog() else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        }, 701)
      }.show()
  }
  private fun selectExport() {
    val items = ids()
    if (items.isEmpty()) { Toast.makeText(this,"暂无可导出的脚本",Toast.LENGTH_SHORT).show(); return }
    val selected = BooleanArray(items.size)
    val dialog = AlertDialog.Builder(this).setTitle("选择导出脚本")
      .setMultiChoiceItems(items.map { it.substringAfterLast(':') }.toTypedArray(),selected) { _,i,v -> selected[i]=v }
      .setNeutralButton("全选",null).setNegativeButton("取消",null)
      .setPositiveButton("导出",null).create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        val value = !selected.all { it }
        selected.indices.forEach { selected[it]=value; dialog.listView.setItemChecked(it,value) }
      }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val sources = items.filterIndexed { i,_ -> selected[i] }.mapNotNull { prefs.getString("source:$it",null) }
        if (sources.isEmpty()) { Toast.makeText(this,"请选择脚本",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
        val single = sources.size == 1
        val data = if (single) sources.first() else org.json.JSONObject().put("format","ScriptHub-scripts-v1").put("scripts",JSONArray(sources)).toString()
        if (data.toByteArray(Charsets.UTF_8).size > 16*1024*1024) { Toast.makeText(this,"备份过大，请分批导出",Toast.LENGTH_LONG).show(); return@setOnClickListener }
        exportData = data
        dialog.dismiss()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE); type = if(single) "application/javascript" else "application/json"
          putExtra(Intent.EXTRA_TITLE,if(single) "script.user.js" else "ScriptHub-backup.json")
        },702)
      }
    }
    dialog.show()
  }
  private fun backupId(source: String): String? {
    if (source.toByteArray(Charsets.UTF_8).size > 180000) return null
    val header = Regex("""// ==UserScript==\r?\n([\s\S]*?)\r?\n// ==/UserScript==\s+""").find(source) ?: return null
    val fields = header.groupValues[1].lineSequence().mapNotNull {
      Regex("""^//\s+@([\w-]+)\s+(.+)""").matchEntire(it)?.let { m -> m.groupValues[1] to m.groupValues[2] }
    }.toList()
    if (fields.none { it.first == "match" || it.first == "include" }) return null
    return (fields.lastOrNull { it.first == "namespace" }?.second ?: "ChromeXt")+":"+
      (fields.lastOrNull { it.first == "name" }?.second ?: "sample").replace(":","")
  }
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode,resultCode,data)
    if (requestCode == 702) {
      val payload = exportData; exportData = null
      val uri = data?.data
      if (resultCode != RESULT_OK || uri == null) return
      if (payload == null) { Toast.makeText(this,"导出会话已失效，请重试",Toast.LENGTH_LONG).show(); return }
      Thread {
        val result = runCatching { requireNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(payload.toByteArray(Charsets.UTF_8)) } }
        runOnUiThread { Toast.makeText(this,if(result.isSuccess) "导出成功" else "导出失败",Toast.LENGTH_LONG).show() }
      }.start()
    }
    if (requestCode != 701 || resultCode != RESULT_OK || data == null) return
    val uris = mutableListOf<android.net.Uri>()
    data.clipData?.let { clip -> for(i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
    data.data?.let { if(it !in uris) uris.add(it) }
    Thread {
      val candidates = linkedMapOf<String,String>()
      var skipped = 0
      var total = 0
      for (uri in uris.take(200)) {
        runCatching {
          val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while(true) {
              val n = stream.read(buffer); if(n < 0) break
              require(total+n <= 16*1024*1024) { "备份大小超限" }
              total += n; out.write(buffer,0,n)
            }; out.toByteArray()
          }
          val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
          val sources = if(raw.trimStart().startsWith("{")) {
            val obj = org.json.JSONObject(raw)
            require(obj.getString("format") == "ScriptHub-scripts-v1")
            val array = obj.getJSONArray("scripts")
            require(array.length() <= 5000)
            (0 until array.length()).map { array.getString(it) }
          } else listOf(raw)
          for(source in sources) {
            val id = backupId(source)
            if(id == null || prefs.contains("source:$id") || candidates.containsKey(id)) skipped++
            else candidates[id]=source
          }
        }.onFailure { skipped++ }
      }
      runOnUiThread {
        if(isFinishing || isDestroyed) return@runOnUiThread
        val items = candidates.keys.toList()
        if(items.isEmpty()) { Toast.makeText(this,"没有可导入的新脚本，跳过 $skipped 项",Toast.LENGTH_LONG).show(); return@runOnUiThread }
        val selected = BooleanArray(items.size) { true }
        AlertDialog.Builder(this).setTitle("选择导入脚本（跳过 $skipped 项）")
          .setMultiChoiceItems(items.toTypedArray(),selected) { _,i,v -> selected[i]=v }
          .setNegativeButton("取消",null).setPositiveButton("导入（默认不启用）") { _,_ ->
            val edit = prefs.edit(); var count = 0
            items.forEachIndexed { i,id ->
              if(selected[i] && !prefs.contains("source:$id")) { edit.putString("source:$id",candidates.getValue(id)); count++ }
            }
            edit.apply()
            org.matrix.chromext.utils.LogRecorder.writeLocal(this,"INFO","[CX-IMPORT] local backup imported $count scripts")
            browserTab=false; search.setText(""); refresh()
            Toast.makeText(this,"已导入 $count 个脚本，请选择适用浏览器",Toast.LENGTH_LONG).show()
          }.show()
      }
    }.start()
  }

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
    text = title; textSize=14f; setTextColor(ink); minHeight=dp(48); isChecked = enabled(pkg,id)
    thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted))
    trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0x667B9EFF,0x33607087))
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
    navButtons.forEachIndexed { i,b -> val active=i==(if(browserTab) 1 else 0); b.background=panel(if(active) soft else surface,14); b.setTextColor(if(active) accent else muted) }
    val browsers = browsers(); val ids = ids()
    updateSummary()
    val q = search.text.toString().trim()
    if (browserTab) {
      for (pkg in browsers.filter { browserName(it).contains(q,true)||it.contains(q,true) }) {
        list.addView(card().apply {
          addView(LinearLayout(this@ScriptManagerActivity).apply { gravity=android.view.Gravity.CENTER_VERTICAL; addView(ImageView(this@ScriptManagerActivity).apply { runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) } },LinearLayout.LayoutParams(dp(42),dp(42)).apply { rightMargin=dp(12) }); addView(column().apply { addView(label(browserName(pkg),19f,true)); addView(label(pkg,12f)) },LinearLayout.LayoutParams(0,-2,1f)) })
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
          addView(View(this@ScriptManagerActivity).apply { setBackgroundColor(if(dark) 0x334F6380 else 0xFFE8EDF5.toInt()) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(12); bottomMargin=dp(8) })
          addView(label("适用浏览器",12f,true))
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
