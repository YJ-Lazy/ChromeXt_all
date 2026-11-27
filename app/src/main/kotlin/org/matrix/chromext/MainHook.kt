package org.matrix.chromext

import android.app.Application
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.matrix.chromext.hook.BaseHook
import org.matrix.chromext.hook.ContextMenuHook
import org.matrix.chromext.hook.FloatingEntryHook
import org.matrix.chromext.hook.PageInfoHook
import org.matrix.chromext.hook.PageMenuHook
import org.matrix.chromext.hook.PreferenceHook
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.LogRecorder
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.hookAfter

val supportedPackages =
    arrayOf(
        "app.vanadium.browser",
        "com.android.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.chrome.beta",
        "com.chrome.canary",
        "com.chrome.dev",
        "com.coccoc.trinhduyet",
        "com.coccoc.trinhduyet_beta",
        "com.herond.android.browser",
        "com.kiwibrowser.browser",
        "com.naver.whale",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "io.github.jqssun.helium",
        "org.axpos.aosmium",
        "org.bromite.bromite",
        "org.chromium.chrome",
        "org.chromium.thorium",
        "org.cromite.cromite",
        "org.greatfire.freebrowser",
        "org.triple.banana",
        "us.spotco.mulch",
    )

object BrowserRuntime {
  fun load(packageName: String, processName: String, classLoader: ClassLoader, application: Application) {
    Log.d(processName + " started")
    if (packageName == "org.matrix.chromext") return
    if (packageName == "android" || packageName == "com.android.systemui") return
    if (packageName == "com.quark.browser") {
      val suffix = processName.substringAfter(':', "")
      // These known helper processes do not own browser tabs or the floating UI.
      if (suffix.startsWith("sandboxed_") || suffix.startsWith("privileged_process") ||
          suffix == "gpu_process" || suffix == "channel" || suffix == "MediaPlayerService") return
    }
    runCatching {
      LogRecorder.initialize(application)
    }.onFailure { Log.ex(it, "Diagnostic initialization failed") }
    Log.i("[CX-ADAPTER] package=${packageName}; process=${processName}; sdk=${android.os.Build.VERSION.SDK_INT}")
    val chromium = runCatching {
      classLoader.loadClass("org.chromium.ui.base.WindowAndroid")
    }.getOrNull()
    Log.i("[CX-ADAPTER] chromium=${chromium != null}; known=${supportedPackages.contains(packageName)}")
    if (chromium != null && packageName != "com.quark.browser") {
      // Every constructor taking a Context, rather than a fixed index into an order the JLS leaves
      // unspecified: browsers do not agree on how many constructors WindowAndroid has, and Whale in
      // particular has no one-argument overload, so index 1 there is a test-only constructor the
      // browser never calls and the whole module stays inert. The overloads delegate into one
      // another, so hooking all of them just means the callback can run twice, which Chrome.init
      // and initHooks both already absorb.
      val windowAndroid = chromium
      val constructors =
          windowAndroid.declaredConstructors.filter {
            val first = it.parameterTypes.firstOrNull()
            first != null && Context::class.java.isAssignableFrom(first)
          }
      if (constructors.isEmpty()) Log.e("No WindowAndroid constructor takes a Context")
      constructors.forEach { constructor ->
        constructor.hookAfter {
          Chrome.init(it.args[0] as Context, packageName)
          // Keep the menu hooks reachable even when the UserScript hook cannot be installed
          runCatching { initHooks(UserScriptHook) }.onFailure { Log.ex(it) }
          if (ContextMenuHook.isInit) return@hookAfter
          // The settings screen and the browser menu are independent features, kept in separate
          // runCatching blocks so a browser that cannot host our preferences still gets its menu
          // entries.
          if (!Chrome.isVivaldi) {
            runCatching { initHooks(PreferenceHook) }
                .onFailure { Log.ex(it) }
          }
          runCatching {
            initHooks(
                when {
                  // Herond (Compose) and Whale (custom views) replace the app menu with a surface
                  // we cannot inject into, so fall back to the selection action mode like the
                  // WebView based browsers do.
                  Chrome.isHerond || Chrome.isWhale -> ContextMenuHook
                  Chrome.isCocCoc -> PageInfoHook
                  else -> PageMenuHook
                })
          }
              .onFailure {
                initHooks(ContextMenuHook)
                if (BuildConfig.DEBUG) Log.ex(it)
              }
        }
      }
    } else {
      Log.i("[CX-ADAPTER] trying WebView adapter for ${packageName}")
      val ctx = application

      Chrome.isMi =
          Chrome.isMi ||
              packageName == "com.mi.globalbrowser" ||
              packageName == "com.android.browser"
      Chrome.isQihoo = packageName == "com.qihoo.contents"

      Chrome.init(ctx, ctx.packageName)

      // Quark uses UC/U4 WebView rather than android.webkit.WebView or Chromium's
      // WindowAndroid. Its Application may not exist yet during handleLoadPackage, so defer
      // initialization until Application.attach supplies a valid browser context.
      if (packageName == "com.quark.browser") {
        run { 
          runCatching {
            Chrome.init(application, packageName)
            LogRecorder.initialize(application)
            WebViewHook.WebView = classLoader.loadClass("com.uc.webview.export.WebView")
            WebViewHook.ViewClient =
                classLoader.loadClass("com.uc.webview.export.WebViewClient")
            WebViewHook.ChromeClient =
                classLoader.loadClass("com.uc.webview.export.WebChromeClient")
            // Quark does not expose Chromium's WindowAndroid/app-menu classes and its
            // selection toolbar does not reliably expose Android ActionMode callbacks.
            // Use a native floating entry attached directly to each UC/U4 WebView instead.
            runCatching { initHooks(WebViewHook) }.onFailure { Log.ex(it, "[CX-ADAPTER] UC/U4 WebView Hook failed") }
            runCatching { initHooks(FloatingEntryHook) }.onFailure { Log.ex(it, "[CX-ADAPTER] floating entry Hook failed") }
            Log.i("[CX-ADAPTER] UC/U4 WebView=${WebViewHook.isInit}; floating=${FloatingEntryHook.isInit}")
          }.onFailure {
            Log.ex(it, "Failed to initialize UC/U4 WebView adapter")
          }
        }
        return
      }

      if (Chrome.isMi) {
        WebViewHook.WebView = Chrome.load("com.miui.webkit.WebView")
        runCatching {
          WebViewHook.ViewClient = Chrome.load("com.android.browser.tab.TabWebViewClient")
          WebViewHook.ChromeClient = Chrome.load("com.android.browser.tab.TabWebChromeClient")
        }
            .onFailure {
              val miuiAutologinBar = Chrome.load("com.android.browser.MiuiAutologinBar")
              // Use MiuiAutologinBar to find `com.android.browser.tab.Tab`, which can located by
              // searching the string "X-MiOrigin"
              val fields = miuiAutologinBar.declaredFields.map { it.type }
              val tab =
                  miuiAutologinBar.declaredMethods
                      .find {
                        it.parameterCount == 2 &&
                            it.parameterTypes[1] == Boolean::class.java &&
                            !fields.contains(it.parameterTypes[0])
                      }!!
                      .run { parameterTypes[0] }
              tab.declaredFields.forEach {
                if (findMethodOrNull(it.type) {
                  // Found by searching the string "Console: "
                  it.name == "onGeolocationPermissionsHidePrompt"
                } != null)
                    WebViewHook.ChromeClient = it.type
                if (findMethodOrNull(it.type) {
                  // Found by searching the string "Tab.MainWebViewClient"
                  it.name == "onReceivedHttpAuthRequest"
                } != null)
                    WebViewHook.ViewClient = it.type
              }
            }

        hookWebView()
        return
      }

      if (Chrome.isQihoo) {
        WebViewHook.WebView = Chrome.load("com.qihoo.webkit.WebView")
        WebViewHook.ViewClient = Chrome.load("com.qihoo.webkit.WebViewClient")
        WebViewHook.ChromeClient = Chrome.load("com.qihoo.webkit.WebChromeClient")
        hookWebView()
        return
      }

      WebViewClient::class.java.declaredConstructors[0].hookAfter {
        if (it.thisObject::class != WebViewClient::class) {
          WebViewHook.ViewClient = it.thisObject::class.java
          hookWebView()
        }
      }

      WebChromeClient::class.java.declaredConstructors[0].hookAfter {
        if (it.thisObject::class != WebChromeClient::class) {
          WebViewHook.ChromeClient = it.thisObject::class.java
          hookWebView()
        }
      }
    }
  }

  private fun hookWebView() {
    if (WebViewHook.ChromeClient == null || WebViewHook.ViewClient == null) return
    if (WebViewHook.WebView == null) {
      runCatching {
        WebViewHook.WebView = WebView::class.java
        WebView.setWebContentsDebuggingEnabled(true)
      }
          .onFailure { if (BuildConfig.DEBUG) Log.ex(it) }
    }
    initHooks(WebViewHook, ContextMenuHook)
  }

  private fun initHooks(vararg hook: BaseHook) {
    hook.forEach {
      if (it.isInit) return@forEach
      it.init()
      if (it.isInit) Log.d("${it.javaClass.simpleName} hooked")
    }
  }
}
