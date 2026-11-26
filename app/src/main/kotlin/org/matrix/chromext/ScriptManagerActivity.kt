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
  private val stats = mutableListOf<TextView>()
  private lateinit var search: EditText
  private val prefs by lazy { getSharedPreferences("script_policy", 0) }
  private var browserTab = false
  private val navButtons = mutableListOf<LinearLayout>()
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
    stateListAnimator=null; backgroundTintList=null
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22345DC5),panel(soft,12),null)
    minHeight = dp(44); minimumHeight = dp(44); setPadding(dp(12),dp(8),dp(12),dp(8))
    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8); bottomMargin = dp(4) }
    setOnClickListener { run() }
  }

  private inner class Glyph(private val kind: String, private val color: Int): android.graphics.drawable.Drawable() {
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      this.color=this@Glyph.color; style=android.graphics.Paint.Style.STROKE; strokeWidth=1.8f
      strokeCap=android.graphics.Paint.Cap.ROUND; strokeJoin=android.graphics.Paint.Join.ROUND
    }
    override fun getIntrinsicWidth()=dp(22)
    override fun getIntrinsicHeight()=dp(22)
    override fun setAlpha(alpha: Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter=filter; invalidateSelf() }
    override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    override fun draw(c: android.graphics.Canvas) {
      c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/24f,bounds.height()/24f)
      fun line(x:Float,y:Float,a:Float,b:Float)=c.drawLine(x,y,a,b,paint)
      when(kind) {
        "search" -> { c.drawCircle(10f,10f,6f,paint); line(14.5f,14.5f,20f,20f) }
        "globe" -> { c.drawCircle(12f,12f,9f,paint); c.drawOval(8f,3f,16f,21f,paint); line(3f,12f,21f,12f) }
        "refresh" -> { c.drawArc(4f,4f,20f,20f,35f,295f,false,paint); line(20f,4f,20f,10f); line(20f,10f,14f,10f) }
        "download","upload" -> {
          val down=kind=="download"; val y=if(down) 15f else 3f; val base=if(down) 9f else 9f
          line(12f,3f,12f,15f); line(6f,base,12f,y); line(18f,base,12f,y)
          line(4f,16f,4f,21f); line(4f,21f,20f,21f); line(20f,21f,20f,16f)
        }
        else -> { c.drawRoundRect(5f,3f,19f,21f,2f,2f,paint)
          if(kind=="file") { line(10f,9f,7f,12f); line(7f,12f,10f,15f); line(14f,9f,17f,12f); line(17f,12f,14f,15f) }
          else { line(8f,8f,16f,8f); line(8f,12f,16f,12f); line(8f,16f,14f,16f) }
        }
      }; c.restore()
    }
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
      addView(column().apply { addView(label("v"+BuildConfig.VERSION_NAME,12f).apply { setTextColor(accent) }); addView(action("设置") { gestureSettings() }) },LinearLayout.LayoutParams(-2,-2))
    })
    root.addView(View(this),LinearLayout.LayoutParams(1,dp(14)))
    root.addView(card().apply {
      background=panel(soft,20)
      addView(label("脚本库概览",12f,true))
      addView(LinearLayout(this@ScriptManagerActivity).apply {
        setPadding(0,dp(8),0,dp(4))
        for(name in listOf("脚本","浏览器","已启用")) {
          addView(column().apply {
            val number=label("0",28f,true).apply { setTextColor(accent) }
            stats.add(number); addView(number); addView(label(name,12f))
          },LinearLayout.LayoutParams(0,-2,1f))
        }
      })
    })
    search = EditText(this).apply { hint = "搜索脚本或浏览器"; setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(muted); background=panel(surface,14); setPadding(dp(16),dp(12),dp(16),dp(12)); minHeight=dp(48) }
    search.setCompoundDrawablesWithIntrinsicBounds(Glyph("search",muted),null,null,null)
    search.compoundDrawablePadding=dp(10)
    root.addView(search)
    root.addView(LinearLayout(this).apply {
      addView(action("导入脚本") { chooseImport() }.apply { setTextColor(Color.WHITE); background=panel(Color.parseColor("#345DC5"),14); setCompoundDrawablesWithIntrinsicBounds(Glyph("download",Color.WHITE),null,null,null); compoundDrawablePadding=dp(4) },LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(6); topMargin=dp(10); bottomMargin=dp(12) })
      addView(action("导出脚本") { selectExport() }.apply { setCompoundDrawablesWithIntrinsicBounds(Glyph("upload",accent),null,null,null); compoundDrawablePadding=dp(4) },LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(6); topMargin=dp(10); bottomMargin=dp(12) })
    })
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    list = column()
    root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
    val nav = LinearLayout(this).apply { background=panel(surface,20); setPadding(dp(4),dp(6),dp(4),dp(6)) }
    fun tab(name: String, glyph: String, run: () -> Unit) {
      val item=column().apply {
        gravity=android.view.Gravity.CENTER; isClickable=true; isFocusable=true
        contentDescription=name
        addView(ImageView(this@ScriptManagerActivity).apply { setImageDrawable(Glyph(glyph,muted)) },LinearLayout.LayoutParams(dp(22),dp(22)))
        addView(label(name,11f).apply { gravity=android.view.Gravity.CENTER },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        setOnClickListener { run() }
      }
      navButtons.add(item); nav.addView(item,LinearLayout.LayoutParams(0,dp(56),1f).apply { leftMargin=dp(2); rightMargin=dp(2) })
    }
    tab("脚本","file") { browserTab = false; refresh() }
    tab("浏览器","globe") { browserTab = true; refresh() }
    tab("日志","log") { startActivity(Intent(this,DiagnosticsActivity::class.java)) }
    tab("刷新","refresh") { refresh() }
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
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("导入脚本")
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
    val dialog = AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("选择导出脚本")
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
    if (source.toByteArray(Charsets.UTF_8).size > ScriptPolicyProvider.MAX_SOURCE) return null
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
        AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("选择导入脚本（跳过 $skipped 项）")
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
    val input = EditText(android.view.ContextThemeWrapper(this,R.style.ScriptHubDialog)).apply { background=panel(soft,14); setTextColor(ink); setHintTextColor(muted); setPadding(dp(14),dp(12),dp(14),dp(12)); hint = "脚本链接或目标网站"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("网址导入").setView(column().apply { setPadding(dp(24),dp(12),dp(24),dp(8)); addView(input,LinearLayout.LayoutParams(-1,-2)) })
      .setMessage("支持 .user.js、Greasy Fork、Sleazy Fork、ScriptCat、GitHub、GitLab、Gist 及自定义 HTTPS 源码链接；普通网站可搜索适用脚本。")
      .setNegativeButton("取消",null).setNeutralButton("粘贴源码") { _,_ ->
        val source=EditText(this).apply { minLines=5; hint="// ==UserScript=="; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("粘贴脚本源码").setView(source).setNegativeButton("取消",null).setPositiveButton("导入") { _,_ ->
          val value=source.text.toString().removePrefix("\uFEFF")
          if(value.toByteArray(Charsets.UTF_8).size<=ScriptPolicyProvider.MAX_SOURCE) previewImport(value) else Toast.makeText(this,"脚本超过 5 MiB",Toast.LENGTH_LONG).show()
        }.show()
      }
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
    if(raw.startsWith("// ==UserScript==")) { previewImport(raw); return }
    val uri = android.net.Uri.parse(if (raw.contains("://")) raw else "https://$raw")
    val host = uri.host.orEmpty().lowercase()
    val parts = uri.pathSegments
    val targets = linkedSetOf<String>()
    val fork = listOf("greasyfork.org","sleazyfork.org").firstOrNull { host==it || host=="www.$it" || host=="update.$it" }
    val direct=uri.path.orEmpty().endsWith(".js",true)
    if(direct) targets.add(uri.toString())
    if(fork!=null) {
      val i=parts.indexOf("scripts")
      val id=parts.getOrNull(i+1)?.substringBefore('-')?.takeIf { i>=0 && it.isNotEmpty() && it.all(Char::isDigit) }
      if(id!=null) {
        targets.add("https://$fork/scripts/$id/code/script.user.js")
        targets.add("https://update.$fork/scripts/$id/script.user.js")
      }
    } else if(host=="scriptcat.org" || host=="www.scriptcat.org") {
      val i=parts.indexOf("script")
      val id=parts.getOrNull(i+1)?.takeIf { i>=0 && it.isNotEmpty() && it.all(Char::isDigit) }
      if(id!=null) targets.add("https://scriptcat.org/scripts/code/$id/script.user.js")
    } else if(host=="github.com" && parts.size>=5 && parts[2] in listOf("blob","raw")) {
      targets.clear()
      targets.add("https://raw.githubusercontent.com/"+(parts.take(2)+parts.drop(3)).joinToString("/") { android.net.Uri.encode(it) })
    } else if(host=="gitlab.com" && parts.contains("blob")) {
      targets.clear()
      targets.add(uri.toString().replace("/-/blob/","/-/raw/"))
    } else if(host=="gist.github.com" && parts.size>=2 && !direct) {
      targets.add("https://gist.githubusercontent.com/"+parts.take(2).joinToString("/") { android.net.Uri.encode(it) }+"/raw")
    }
    if(targets.isEmpty()) targets.add(uri.toString())
    val progress = AlertDialog.Builder(this,R.style.ScriptHubDialog).setMessage("正在下载脚本…").setCancelable(false).show()
    Thread {
      fun fetch(target:String):String {
        var url=java.net.URL(java.net.URI(target.replace(" ","%20")).toASCIIString())
        repeat(8) {
          require(url.protocol=="https" && url.userInfo==null) { "仅支持 HTTPS 下载" }
          val conn=url.openConnection() as java.net.HttpURLConnection
          try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=20000; conn.readTimeout=30000
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept","application/javascript, text/javascript, text/plain, */*;q=0.5")
            conn.setRequestProperty("Connection","close")
            val status=conn.responseCode
            if(status in listOf(301,302,303,307,308)) {
              url=java.net.URL(url,requireNotNull(conn.getHeaderField("Location")))
            } else {
              if(status==429 || status>=500) throw java.io.IOException("HTTP $status")
              require(status in 200..299) { "HTTP $status" }
              val out=java.io.ByteArrayOutputStream()
              conn.inputStream.use { stream ->
                val buffer=ByteArray(8192)
                while(true) {
                  val n=stream.read(buffer)
                  if(n<0) break
                  require(out.size()+n<=ScriptPolicyProvider.MAX_SOURCE) { "脚本超过 5 MiB" }
                  out.write(buffer,0,n)
                }
              }
              val source=out.toString("UTF-8").removePrefix("\uFEFF")
              require(source.contains("// ==UserScript==")) { "返回内容不是用户脚本（可能是网页或验证页面）" }
              return source
            }
          } finally { conn.disconnect() }
        }
        error("重定向次数过多")
      }
      val result=runCatching {
        var last:Exception?=null
        var source:String?=null
        for(target in targets) {
          for(attempt in 0..1) {
            try { source=fetch(target); break }
            catch(e:Exception) {
              last=e
              org.matrix.chromext.utils.LogRecorder.writeLocal(this,"WARN","[CX-IMPORT] "+java.net.URL(target).host+" attempt="+(attempt+1)+" "+e.javaClass.simpleName+": "+e.message)
              if(e !is java.io.IOException || e is javax.net.ssl.SSLHandshakeException) break
              if(attempt==0) Thread.sleep(600)
            }
          }
          if(source!=null) break
        }
        source ?: throw (last ?: java.io.IOException("下载失败"))
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        progress.dismiss()
        result.onSuccess { previewImport(it) }.onFailure {
          org.matrix.chromext.utils.LogRecorder.writeLocal(this,"ERROR","[CX-IMPORT] "+it.javaClass.simpleName+": "+it.message)
          AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("导入失败").setMessage((it.message ?: "未知错误")+"\n\n可重试，或使用浏览器下载后本地导入。").setNeutralButton("浏览器打开") { _,_ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW,uri)) } }.setNegativeButton("关闭",null).setPositiveButton("重试") { _,_ -> downloadScript(raw) }.show()
        }
      }
    }.start()
  }

  private fun showSource(title: String, source: String, back: (() -> Unit)? = null) {
    val pageSize=24000
    val pages=mutableListOf<Int>(0)
    while(pages.last()<source.length) {
      var end=minOf(pages.last()+pageSize,source.length)
      if(end<source.length && Character.isHighSurrogate(source[end-1])) end--
      pages.add(end)
    }
    if(pages.size==1) pages.add(0)
    var page=0
    val body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(12)) }
    val info=label("",12f)
    body.addView(info)
    val editor=EditText(this).apply {
      setTextColor(ink); textSize=12f; typeface=Typeface.MONOSPACE
      background=panel(soft,12); setPadding(dp(12),dp(12),dp(12),dp(12))
      gravity=android.view.Gravity.TOP or android.view.Gravity.START
      inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
      keyListener=null; setTextIsSelectable(true); isFocusable=true; isFocusableInTouchMode=true
      showSoftInputOnFocus=false; isCursorVisible=false
      setHorizontallyScrolling(false)
    }
    body.addView(editor,LinearLayout.LayoutParams(-1, minOf(dp(420),resources.displayMetrics.heightPixels/2)))
    fun copy(value:String) {
      if(value.toByteArray(Charsets.UTF_8).size>256*1024) {
        Toast.makeText(this,"源码过大，请使用导出脚本保存完整文件",Toast.LENGTH_LONG).show(); return
      }
      runCatching {
        getSystemService(android.content.ClipboardManager::class.java)
          .setPrimaryClip(android.content.ClipData.newPlainText("脚本源码",value))
      }.onSuccess { Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show() }
       .onFailure { Toast.makeText(this,"复制失败，请使用导出脚本",Toast.LENGTH_LONG).show() }
    }
    fun render() {
      editor.setText(source.substring(pages[page],pages[page+1]))
      editor.scrollTo(0,0)
      info.text="第 ${page+1} / ${pages.size-1} 页 · 长按选择文本"
    }
    body.addView(LinearLayout(this).apply {
      addView(action("上一页") { if(page>0) { page--; render() } },LinearLayout.LayoutParams(0,-2,1f))
      addView(action("下一页") { if(page<pages.size-2) { page++; render() } },LinearLayout.LayoutParams(0,-2,1f))
    })
    body.addView(action("复制选中内容") {
      val a=editor.selectionStart; val b=editor.selectionEnd
      if(a>=0 && b>=0 && a!=b) copy(editor.text.substring(minOf(a,b),maxOf(a,b)))
      else Toast.makeText(this,"请先长按选择文本",Toast.LENGTH_SHORT).show()
    })
    body.addView(action("复制全部") { copy(source) })
    render()
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle(title).setView(body)
      .setPositiveButton(if(back==null) "关闭" else "返回") { _,_ -> back?.invoke() }
      .show().window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
  }

  private fun previewImport(source: String) {
    val header = Regex("""// ==UserScript==\r?\n([\s\S]*?)\r?\n// ==/UserScript==\s+""").find(source)
    val metadata = header?.groupValues?.get(1)?.lineSequence()?.mapNotNull {
      Regex("""^//\s+@([\w-]+)\s+(.+)""").matchEntire(it)?.let { m -> m.groupValues[1] to m.groupValues[2] }
    }?.toList().orEmpty()
    fun field(key: String, fallback: String) = metadata.lastOrNull { it.first == key }?.second ?: fallback
    val matches = metadata.filter { it.first == "match" || it.first == "include" }.map { it.second }
    if (header == null || matches.isEmpty()) {
      AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("无效脚本").setMessage("缺少用户脚本元数据或匹配规则").setPositiveButton("关闭",null).show(); return
    }
    val name = field("name","sample").replace(":","")
    val id = field("namespace","ChromeXt")+":"+name
    if (prefs.contains("source:$id")) {
      AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("更新已有脚本")
        .setMessage("覆盖源码为 "+field("version","未知版本")+"？保留浏览器分配及启用状态。")
        .setNeutralButton("查看源码") { _,_ -> showSource("新源码",source) { previewImport(source) } }
        .setNegativeButton("取消",null).setPositiveButton("确认更新") { _,_ ->
          prefs.edit().putString("source:$id",source).putString("revision:$id",java.util.UUID.randomUUID().toString()).apply()
          refresh()
        }.show(); return
    }
    val targets = browsers()
    val selected = BooleanArray(targets.size)
    val grants = metadata.filter { it.first == "grant" }.joinToString("\n") { it.second }
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle(name+" · "+field("version","未知版本"))
      .setMessage("匹配规则：\n"+matches.joinToString("\n")+"\n\n声明权限：\n"+grants.ifBlank { "无" }+"\n\n仅导入可信脚本。")
      .setNeutralButton("查看源码") { _,_ ->
        showSource("脚本源码",source) { previewImport(source) }
      }.setNegativeButton("取消",null).setPositiveButton("选择浏览器") { _,_ ->
        AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("分配浏览器（可稍后设置）")
          .setMultiChoiceItems(targets.map { browserName(it) }.toTypedArray(),selected) { _,i,checked -> selected[i]=checked }
          .setNegativeButton("取消",null).setPositiveButton("确认导入") { _,_ ->
            val edit = prefs.edit().putString("source:$id",source).remove("deleted:$id").putString("revision:$id",java.util.UUID.randomUUID().toString())
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
    val counts=listOf(ids.size,browsers.size,ids.count { id -> browsers.any { enabled(it,id) } })
    stats.forEachIndexed { i,v -> v.text=counts[i].toString() }
  }
  private fun deleteScript(id:String) {
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("删除脚本")
      .setMessage("从 ScriptHub 和已同步浏览器删除 "+id.substringAfterLast(':')+"？")
      .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
        val edit=prefs.edit().remove("source:$id").remove("revision:$id").putBoolean("deleted:$id",true)
        for(key in prefs.all.keys) if((key.startsWith("assigned:") || key.startsWith("enabled:")) && key.substringAfter(':').substringAfter(':')==id) edit.remove(key)
        edit.apply(); refresh()
      }.show()
  }
  private fun editScript(id:String) {
    val original=prefs.getString("source:$id",null) ?: return
    val pages=mutableListOf<String>()
    var offset=0
    while(offset<original.length) {
      var end=minOf(offset+24000,original.length)
      if(end<original.length && Character.isHighSurrogate(original[end-1])) end--
      pages.add(original.substring(offset,end)); offset=end
    }
    if(pages.isEmpty()) pages.add("")
    var page=0
    val body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(8)) }
    val info=label("",12f)
    body.addView(info)
    val editor=EditText(this).apply {
      textSize=12f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(ink)
      background=panel(soft,12)
      setPadding(dp(12),dp(12),dp(12),dp(12))
      gravity=android.view.Gravity.TOP or android.view.Gravity.START
      inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      setHorizontallyScrolling(false)
    }
    body.addView(editor,LinearLayout.LayoutParams(-1,minOf(dp(380),resources.displayMetrics.heightPixels/2)))
    fun render() { info.text="第 ${page+1} / ${pages.size} 页"; editor.setText(pages[page]); editor.setSelection(0) }
    fun move(delta:Int) { val next=page+delta; if(next in pages.indices) { pages[page]=editor.text.toString(); page=next; render() } }
    body.addView(LinearLayout(this).apply {
      addView(action("上一页") { move(-1) },LinearLayout.LayoutParams(0,-2,1f))
      addView(action("下一页") { move(1) },LinearLayout.LayoutParams(0,-2,1f))
    })
    render()
    val dialog=AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("编辑脚本").setView(body)
      .setNegativeButton("取消",null).setPositiveButton("保存",null).create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        pages[page]=editor.text.toString()
        val source=pages.joinToString("")
        if(backupId(source)!=id) {
          Toast.makeText(this,"脚本无效、超出大小限制或标识已改变",Toast.LENGTH_LONG).show()
        } else if(prefs.getString("source:$id",null)!=original) {
          Toast.makeText(this,"脚本已发生变化，请重新打开编辑",Toast.LENGTH_LONG).show()
        } else {
          prefs.edit().putString("source:$id",source).putString("revision:$id",java.util.UUID.randomUUID().toString()).apply()
          dialog.dismiss(); refresh()
        }
      }
    }
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
  }
  private val gesturePrefs by lazy { getSharedPreferences("scripthub_ui",0) }
  private fun gestureSettings() {
    val options=arrayOf("编辑脚本","删除脚本","关闭手势")
    val values=arrayOf("edit","delete","none")
    val body=column().apply { setPadding(dp(20),dp(8),dp(20),dp(8)) }
    fun picker(title:String,key:String,fallback:String):Spinner {
      body.addView(label(title,14f,true))
      return Spinner(this).apply {
        adapter=ArrayAdapter(this@ScriptManagerActivity,android.R.layout.simple_spinner_dropdown_item,options)
        setSelection(values.indexOf(gesturePrefs.getString(key,fallback)).coerceAtLeast(0))
        body.addView(this,LinearLayout.LayoutParams(-1,dp(52)))
      }
    }
    val left=picker("左滑","swipe_left","edit")
    val right=picker("右滑","swipe_right","delete")
    AlertDialog.Builder(this,R.style.ScriptHubDialog).setTitle("手势设置").setView(body)
      .setNeutralButton("恢复默认") { _,_ -> gesturePrefs.edit().remove("swipe_left").remove("swipe_right").apply(); refresh() }
      .setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
        gesturePrefs.edit().putString("swipe_left",values[left.selectedItemPosition]).putString("swipe_right",values[right.selectedItemPosition]).apply()
        refresh()
      }.show()
  }
  private fun swipeCard(id:String, content:View):View {
    val ctx=this
    val width=dp(88)
    val left=gesturePrefs.getString("swipe_left","edit") ?: "edit"
    val right=gesturePrefs.getString("swipe_right","delete") ?: "delete"
    if(left=="none" && right=="none") return content
    var rightAction:View?=null
    var leftAction:View?=null
    fun reveal(value:Float) {
      content.translationX=value
      rightAction?.visibility=if(value>0f) View.VISIBLE else View.INVISIBLE
      leftAction?.visibility=if(value<0f) View.VISIBLE else View.INVISIBLE
    }
    val frame=object:FrameLayout(ctx) {
      var touchStartX=0f; var touchStartY=0f; var initial=0f; var dragging=false
      override fun onInterceptTouchEvent(e:android.view.MotionEvent):Boolean {
        when(e.actionMasked) {
          0 -> { touchStartX=e.x; touchStartY=e.y; content.animate().cancel(); initial=content.translationX; dragging=false }
          2 -> if((initial!=0f || (e.x>touchStartX && right!="none") || (e.x<touchStartX && left!="none")) && kotlin.math.abs(e.x-touchStartX)>dp(16) && kotlin.math.abs(e.x-touchStartX)>kotlin.math.abs(e.y-touchStartY)*1.5f) {
            dragging=true; parent.requestDisallowInterceptTouchEvent(true); return true
          }
        }
        return false
      }
      override fun onTouchEvent(e:android.view.MotionEvent):Boolean {
        when(e.actionMasked) {
          2 -> reveal((initial+e.x-touchStartX).coerceIn(if(left=="none") 0f else -width.toFloat(),if(right=="none") 0f else width.toFloat()))
          1,3 -> { reveal(if(e.actionMasked==3) 0f else when { content.translationX>width/2 -> width.toFloat(); content.translationX< -width/2 -> -width.toFloat(); else -> 0f }); parent.requestDisallowInterceptTouchEvent(false); dragging=false }
        }
        return true
      }
    }
    frame.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }
    fun addGesture(kind:String,gravity:Int) {
      if(kind=="none") return
      val button=Button(this).apply {
        visibility=View.INVISIBLE; stateListAnimator=null; elevation=0f; translationZ=0f
        text=if(kind=="delete") "删除" else "编辑"
        setTextColor(Color.WHITE)
        background=panel(if(kind=="delete") 0xFFC43C46.toInt() else 0xFF345DC5.toInt(),12)
        setOnClickListener { if(kind=="delete") deleteScript(id) else editScript(id) }
      }
      frame.addView(button,FrameLayout.LayoutParams(width,-1,gravity))
      if(gravity==android.view.Gravity.LEFT) rightAction=button else leftAction=button
    }
    addGesture(right,android.view.Gravity.LEFT)
    addGesture(left,android.view.Gravity.RIGHT)
    content.elevation=dp(1).toFloat(); content.translationZ=0f
    frame.clipChildren=true
    frame.addView(content,FrameLayout.LayoutParams(-1,-2))
    reveal(0f)
    return frame
  }
  private fun refresh() {
    if (!::list.isInitialized) return
    list.removeAllViews()
    navButtons.forEachIndexed { i,b ->
      val active=i==(if(browserTab) 1 else 0)
      val color=if(active) accent else muted
      b.background=panel(if(active) soft else surface,14)
      (b.getChildAt(0) as ImageView).setImageDrawable(Glyph(listOf("file","globe","log","refresh")[i],color))
      (b.getChildAt(1) as TextView).setTextColor(color)
      b.isSelected=active
    }
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
        list.addView(swipeCard(id,card().apply {
          addView(label(id.substringAfterLast(':'),19f,true))
          addView(label(id.substringBeforeLast(':') + (version?.let { " · v$it" } ?: "")))
          addView(View(this@ScriptManagerActivity).apply { setBackgroundColor(if(dark) 0x334F6380 else 0xFFE8EDF5.toInt()) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(12); bottomMargin=dp(8) })
          addView(label("适用浏览器",12f,true))
          for (pkg in browsers) addView(switchFor(pkg,id,browserName(pkg)+if(installed(pkg,id)) " · 已安装" else " · 分配"))
          addView(action("查看源码") {
            showSource(id.substringAfterLast(':'),source)
          })
        }))
      }
    }
    if (list.childCount == 0) list.addView(card().apply { addView(label(if(q.isNotEmpty()) "没有匹配结果" else if(browserTab) "未发现浏览器" else "暂无脚本，点击导入脚本或在浏览器安装。",16f,true)) })
  }
}
