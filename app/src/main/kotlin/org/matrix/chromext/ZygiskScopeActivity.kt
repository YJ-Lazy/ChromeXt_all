package org.matrix.chromext

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Zygisk process scope, separate from per-script assignment. Root is requested only here. */
class ZygiskScopeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val allowed by lazy { resources.getStringArray(R.array.xposedscope).toSet() }
    private val checked = linkedSetOf<String>()
    private val switches = linkedMapOf<String, Switch>()
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var save: Button
    private lateinit var reload: Button
    private lateinit var all: Button
    private lateinit var none: Button
    private var loaded = false
    private var busy = false
    private var saved = emptySet<String>()
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val canvas get() = Color.parseColor(if (dark) "#101625" else "#F3F5FB")
    private val surface get() = Color.parseColor(if (dark) "#222B3C" else "#FFFFFF")
    private val ink get() = Color.parseColor(if (dark) "#E8EAF2" else "#18243A")
    private val muted get() = Color.parseColor(if (dark) "#AAB4C8" else "#607087")
    private val accent get() = Color.parseColor(if (dark) "#9BB8FF" else "#345DC5")
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun panel(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, strong: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (strong) ink else muted)
        if (strong) setTypeface(null, Typeface.BOLD)
    }
    private fun button(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; setTextColor(accent)
        setOnClickListener { click() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        actionBar?.hide()
        val root = column().apply { setBackgroundColor(canvas); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("返回") { finish() }, LinearLayout.LayoutParams(-2, -2))
            addView(text("浏览器注入管理", 23f, true), LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(text("只有已选择的浏览器主进程会被注入。此设置不会更改脚本分配。保存后彻底关闭并重新打开浏览器即可生效。", 14f))
        status = text("正在读取模块配置…", 13f).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(status)
        reload = button("重新读取") { loadScope() }
        all = button("全选已安装") { switches.forEach { (pkg, toggle) -> if (runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess) toggle.isChecked = true } }
        none = button("全部取消") { switches.values.forEach { it.isChecked = false } }
        root.addView(LinearLayout(this).apply {
            for (b in listOf(reload, all, none)) addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        })
        content = column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        save = button("保存注入范围") { saveScope() }.apply {
            setTextColor(Color.WHITE); background = panel(Color.parseColor("#345DC5"))
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(50)))
        window.statusBarColor = canvas; window.navigationBarColor = canvas
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else flags, flags)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(16)+bars.left, dp(12)+bars.top, dp(16)+bars.right, dp(12)+bars.bottom)
                insets
            }
        } else window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        root.requestApplyInsets()
        loadScope()
    }

    private fun updateControls() {
        reload.isEnabled = !busy
        all.isEnabled = loaded && !busy
        none.isEnabled = loaded && !busy
        save.isEnabled = loaded && !busy && checked != saved
        switches.values.forEach { it.isEnabled = loaded && !busy }
        if (loaded && !busy) status.text = "已选择 ${checked.size} 个浏览器" + if (checked != saved) " · 尚未保存" else " · 已保存"
    }

    private fun render() {
        content.removeAllViews()
        switches.clear()
        val installed = allowed.filter { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess }.toSet()
        // Keep previously selected but removed apps visible so they can be deselected.
        val packages = (installed + checked).sortedBy { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        if (packages.isEmpty()) content.addView(text("未检测到受支持的浏览器。", 16f, true))
        for (pkg in packages) {
            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = panel(surface); setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            row.addView(ImageView(this).apply { runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) } }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(column().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(name, 16f, true))
                addView(text(pkg + if (pkg !in installed) " · 未安装" else "", 11f))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = Switch(this).apply {
                contentDescription = "注入 $name"
                isChecked = pkg in checked
                setOnCheckedChangeListener { _, value ->
                    if (value) checked.add(pkg) else checked.remove(pkg)
                    updateControls()
                }
            }
            switches[pkg] = toggle
            row.addView(toggle)
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        updateControls()
    }

    // Only a fixed root-owned module path is writable. No package name enters shell code.
    private val checkModule = """
        d=/data/adb/modules/chromext_zygisk
        [ ! -d /data/adb/modules_update/chromext_zygisk ] || { echo '模块更新待重启，请先重启' >&2; exit 1; }
        [ -d "${'$'}d" ] && [ -f "${'$'}d/module.prop" ] || { echo '模块尚未激活，请刷入后重启' >&2; exit 1; }
        [ ! -L "${'$'}d" ] || exit 1
    """.trimIndent()
    private fun root(command: String, input: String = ""): String {
        val output = java.io.File.createTempFile("zygisk-scope-", ".txt", cacheDir)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Root 授权或执行超时")
            }
            val result = output.readText().take(8192)
            check(process.exitValue() == 0) { result.trim().ifEmpty { "Root 操作失败，请检查授权" } }
            return result
        } finally { output.delete(); process.destroy() }
    }
    private fun loadScope() {
        if (busy) return
        busy = true; loaded = false; updateControls()
        status.text = "正在读取模块配置，请允许 Root 授权…"
        worker.execute {
            val result = runCatching {
                val data = root(checkModule + "\n[ ! -L \"\$d/injection.list\" ] || exit 1\nif [ -f \"\$d/injection.list\" ]; then cat \"\$d/injection.list\"; fi")
                val values = data.lineSequence().filter { it.isNotEmpty() }.toSet()
                check(values.all { it in allowed }) { "模块配置无效，请检查模块版本" }
                values
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess { values ->
                    saved = values; checked.clear(); checked.addAll(values); loaded = true; render()
                }.onFailure { status.text = it.message ?: "读取失败"; updateControls() }
            }
        }
    }
    private fun saveScope() {
        if (!loaded || busy) return
        val values = checked.toSet()
        if (!values.all { it in allowed }) return
        busy = true; updateControls(); status.text = "正在保存…"
        worker.execute {
            val result = runCatching {
                val command = checkModule + "\n" + """
                    set -e
                    umask 077
                    t=${'$'}(mktemp "${'$'}d/.injection.XXXXXX")
                    trap 'rm -f "${'$'}t"' EXIT
                    cat > "${'$'}t"
                    chown 0:0 "${'$'}t"
                    chmod 600 "${'$'}t"
                    mv -f "${'$'}t" "${'$'}d/injection.list"
                """.trimIndent()
                root(command, values.sorted().joinToString("\n", postfix = if (values.isEmpty()) "" else "\n"))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess {
                    saved = values; updateControls()
                    Toast.makeText(this, "已保存，请彻底关闭并重新打开对应浏览器", Toast.LENGTH_LONG).show()
                }.onFailure { updateControls(); status.text = it.message ?: "保存失败" }
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
