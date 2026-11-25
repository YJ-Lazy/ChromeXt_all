package org.matrix.chromext.hook

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.matrix.chromext.Chrome
import org.matrix.chromext.Listener
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.isChromeXtFrontEnd
import org.matrix.chromext.utils.isUserScript

/** UC/U4 result callbacks replace the Chromium console source/line transport. */
object QuarkBridge {
  private val handler = Handler(Looper.getMainLooper())
  private class Page(val url: String) {
    var finished = false
    var polling = false
  }
  private val pages = WeakHashMap<Any, Page>()
  private fun normalized(url: String?) = url?.substringBefore('#')
  private fun current(view: Any, url: String): Boolean =
      runCatching { normalized(Chrome.getUrl(view)) == normalized(url) }.getOrDefault(false)

  private fun eligible(url: String): Boolean =
      url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file://")

  private fun evaluate(view: Any, code: String, result: (String?) -> Unit) {
    handler.post {
      var completed = false
      fun finish(value: String?) {
        if (completed) return
        completed = true
        runCatching { result(value) }.onFailure { Log.ex(it, "[CX-JS] result handler failed") }
      }
      runCatching {
        val method = findMethod(view.javaClass, true) {
          name == "evaluateJavascript" && parameterCount == 2 &&
              parameterTypes[0] == String::class.java && parameterTypes[1].isInterface &&
              parameterTypes[1].methods.any { it.name == "onReceiveValue" && it.parameterCount == 1 }
        }
        val type = method.parameterTypes[1]
        val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
          when (m.name) {
            "toString" -> "ChromeXt UC callback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "onReceiveValue" -> {
              handler.post { finish(args?.firstOrNull()?.toString()) }
              null
            }
            else -> null
          }
        }
        method.invoke(view, code, callback)
        handler.postDelayed({
          if (!completed) {
            Log.w("[CX-JS] UC result callback timed out")
            finish(null)
          }
        }, 4000L)
      }.onFailure {
        Log.ex(it, "[CX-JS] UC evaluation failed")
        finish(null)
      }
    }
  }

  private fun decode(raw: String?): Any? {
    if (raw == null) return null
    return runCatching { JSONTokener(raw).nextValue() }.getOrElse { raw }
  }

  private fun target(trusted: Boolean): String =
      if (trusted) "Symbol[\"ChromeXt\"]" else "Symbol.${Local.name}"

  private fun urlGuard(url: String): String =
      "if (location.href.split('#')[0] !== new URL(${JSONObject.quote(normalized(url))}, location.href).href.split('#')[0]) return null;"

  fun pageStarted(view: Any, url: String) {
    val page = Page(url)
    pages[view] = page
    Chrome.updateTab(view)
    Log.d("[CX-WEBVIEW] start; view=${System.identityHashCode(view)}")
    prepare(view, page)
  }

  fun pageFinished(view: Any, url: String) {
    if (!current(view, url)) return
    val existing = pages[view]
    val page = if (existing != null && normalized(existing.url) == normalized(url)) existing
        else Page(url).also { pages[view] = it }
    if (page.finished) return
    page.finished = true
    Chrome.updateTab(view)
    Log.d("[CX-WEBVIEW] finish; view=${System.identityHashCode(view)}")
    prepare(view, page)
  }

  fun resume(view: Any) {
    val page = pages[view] ?: return
    if (page.finished && !page.polling && current(view, page.url)) prepare(view, page)
  }

  fun probe(view: View) {
    evaluate(view, "6*7") { raw ->
      val value = decode(raw)
      Log.i("[CX-JS] native result probe=${if (value.toString() == "42") "42 (OK)" else "failed"}")
      Log.toast(view.context, if (value.toString() == "42") "JavaScript 回调正常：42" else "JavaScript 回调异常")
    }
  }

  private fun prepare(view: Any, page: Page) {
    if (isUserScript(page.url)) {
      Log.d("[CX-INSTALL] script page detected; native confirmation available")
      return
    }
    if (!eligible(page.url)) return
    runCatching { ScriptDbManager.invokeScript(page.url, view) }
        .onFailure { Log.ex(it, "[CX-INIT] preparation failed") }
  }

  fun inject(view: Any, url: String, init: String, scripts: List<Pair<String, String>>, trusted: Boolean) {
    val page = pages[view] ?: return
    if (normalized(page.url) != normalized(url)) return
    val access = target(trusted)
    val key = Local.key
    // Same-document guard avoids duplicate injection on start/finish and redirects.
    val code = """
      (function(){
        ${urlGuard(url)}
        const existing = $access;
        if (existing && typeof existing.quarkReady === 'function' && existing.quarkReady($key)) return 'ready';
        try {
          $init
          const installed = $access;
          if (!installed || typeof installed.quarkReady !== 'function' || !installed.quarkReady($key)) return 'missing';
          return 'initialized';
        } catch(e) { return 'error:' + (e && e.name || 'Error'); }
      })();
    """.trimIndent()
    evaluate(view, code) { raw ->
      if (pages[view] !== page || !current(view, url)) return@evaluate
      val status = decode(raw)?.toString()
      Log.d("[CX-INIT] result=${status ?: "no callback"}; finished=${page.finished}")
      if (status == "initialized" || status == "ready") {
        scripts.forEach { (id, script) ->
          val guarded = """
            (function(){
              ${urlGuard(url)}
              const bridge = $access;
              if (!bridge || typeof bridge.quarkClaim !== 'function') return 'bridge-missing';
              if (!bridge.quarkClaim($key, ${JSONObject.quote(id)})) return 'already-executed';
              try {
                $script
                return 'executed';
              } catch(e) { return 'error:' + (e && e.name || 'Error'); }
            })();
          """.trimIndent()
          evaluate(view, guarded) { raw ->
            if (pages[view] === page && current(view, url))
              Log.d("[CX-INJECT] id=$id; result=${decode(raw)}")
          }
        }
      }
      if ((status == "initialized" || status == "ready") && page.finished && !page.polling) {
        page.polling = true
        poll(view, page, trusted)
      }
    }
  }

  private fun poll(view: Any, page: Page, trusted: Boolean) {
    if (pages[view] !== page || !current(view, page.url)) return
    if (view is View && !view.isAttachedToWindow) {
      page.polling = false
      return
    }
    if (view is View && !view.isShown) {
      handler.postDelayed({ poll(view, page, trusted) }, 1000L)
      return
    }
    val access = target(trusted)
    val code = "(function(){${urlGuard(page.url)}const b=$access;return b&&typeof b.quarkDrain==='function'?b.quarkDrain(${Local.key}):null;})();"
    evaluate(view, code) { raw ->
      if (pages[view] !== page || !current(view, page.url)) return@evaluate
      val value = decode(raw)
      val batch = when (value) {
        is JSONArray -> value
        is String -> runCatching { JSONArray(value) }.getOrNull()
        else -> null
      }
      if (batch == null) {
        Log.w("[CX-BRIDGE] drain unavailable; retrying")
      } else if (batch.length() > 0) {
        Log.d("[CX-BRIDGE] drained=${batch.length()}")
        for (index in 0 until minOf(batch.length(), 64)) {
          if (!current(view, page.url)) break
          val message = batch.optString(index)
          if (message.isNotEmpty()) Listener.startAction(message, view, null, "local://ChromeXt/init")
        }
      }
      handler.postDelayed({ poll(view, page, trusted) }, if (batch == null) 1500L else 350L)
    }
  }

  fun installCurrent(view: View, url: String) {
    if (!isUserScript(url) || !current(view, url)) return
    val code = "(function(){${urlGuard(url)}return document.body ? JSON.stringify({source:document.body.innerText}) : null;})();"
    evaluate(view, code) { raw ->
      if (!current(view, url)) return@evaluate
      val value = decode(raw)
      val source = when (value) {
        is JSONObject -> value.optString("source")
        is String -> runCatching { JSONObject(value).optString("source") }.getOrNull()
        else -> null
      }
      if (source == null || !source.contains("// ==UserScript==") || !source.contains("// ==/UserScript==")) {
        Log.w("[CX-INSTALL] metadata not found in page text")
        Log.toast(view.context, "没有找到有效的 UserScript 元数据")
        return@evaluate
      }
      val title = source.lineSequence().firstOrNull { it.trimStart().startsWith("// @name") }
          ?.substringAfter("@name")?.trim()?.take(100) ?: "UserScript"
      AlertDialog.Builder(view.context)
          .setTitle("安装脚本")
          .setMessage("确认安装 $title？请仅安装你信任的脚本。")
          .setNegativeButton("取消", null)
          .setPositiveButton("安装") { _, _ ->
            if (current(view, url)) runCatching {
              installSource(view, source)
            }.onFailure {
              Log.ex(it, "[CX-INSTALL] persistence failed")
              Log.toast(view.context, "脚本安装失败")
            }
          }.show()
    }
  }

  private fun installSource(view: View, source: String) {
    val callback = Listener.on("installScript", source, view)
    if (callback != null) Log.toast(view.context, "无效的 UserScript 元数据")
  }

  fun showManager(view: View) {
    runCatching {
      val installed = ScriptDbManager.scripts.toList()
      Log.d("[CX-DB] native manager opened; installed=${installed.size}")
      val labels = if (installed.isEmpty()) arrayOf("暂无脚本") else installed.map { it.id }.toTypedArray()
      AlertDialog.Builder(view.context)
          .setTitle("ChromeXt 脚本管理 (${installed.size})")
          .setItems(labels) { _, index ->
            val script = installed.getOrNull(index) ?: return@setItems
            AlertDialog.Builder(view.context)
                .setTitle(script.id)
                .setMessage("匹配规则：\n" + script.match.joinToString("\n"))
                .setNegativeButton("返回") { _, _ -> showManager(view) }
                .setPositiveButton("删除") { _, _ ->
                  runCatching { ScriptDbManager.remove(script.id) }
                      .onFailure { Log.ex(it, "[CX-DB] delete failed"); Log.toast(view.context, "删除失败") }
                  showManager(view)
                }.show()
          }
          .setNegativeButton("关闭", null)
          .setPositiveButton("粘贴安装") { _, _ ->
            val input = EditText(view.context).apply {
              hint = "// ==UserScript=="
              minLines = 6
              inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            AlertDialog.Builder(view.context)
                .setTitle("粘贴可信脚本源码")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("安装") { _, _ ->
                  runCatching { installSource(view, input.text.toString()) }
                      .onFailure { Log.ex(it, "[CX-INSTALL] paste failed"); Log.toast(view.context, "安装失败") }
                }.show()
          }.show()
    }.onFailure { Log.ex(it, "[CX-DB] native manager failed") }
  }

  fun openEruda(view: View) {
    val url = Chrome.getUrl(view) ?: return
    if (!eligible(url) || isUserScript(url)) {
      Log.toast(view.context, "当前页面不支持开发者控制台")
      return
    }
    val access = target(isChromeXtFrontEnd(url) || org.matrix.chromext.utils.isDevToolsFrontEnd(url))
    evaluate(view, "(function(){${urlGuard(url)}const b=$access;return !!(b&&typeof b.quarkReady==='function'&&b.quarkReady(${Local.key}));})();") { raw ->
      if (!current(view, url)) return@evaluate
      if (decode(raw) != true) {
        Log.w("[CX-ERUDA] bridge not ready")
        Log.toast(view.context, "ChromeXt 尚未初始化，请刷新网页")
        return@evaluate
      }
      Log.d("[CX-ERUDA] bridge ready; requesting console")
      evaluate(view, "(function(){${urlGuard(url)}\n${Local.openEruda}\nreturn true;})();") {
        Log.d("[CX-ERUDA] request evaluated=${decode(it) == true}")
      }
    }
  }
}
