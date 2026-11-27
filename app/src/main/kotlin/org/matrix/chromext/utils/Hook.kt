package org.matrix.chromext.utils

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

typealias Hooker = (param: HookParam) -> Unit
typealias Unhook = XposedInterface.HookHandle

object ModernHookRuntime {
  @Volatile var module: XposedModule? = null

  fun log(priority: Int, message: String, error: Throwable? = null) {
    runCatching { module?.log(priority, "ChromeXt", message, error) }
  }

  fun install(executable: Executable, priority: Int, before: Hooker?, after: Hooker?): Unhook {
    val runtime = checkNotNull(module) { "Modern Xposed module not initialized" }
    return runtime.hook(executable).setPriority(priority)
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept { chain ->
          val param = HookParam(executable, chain.thisObject, chain.args.toTypedArray())
          if (before != null) {
            try {
              before(param)
            } catch (thr: Throwable) {
              Log.ex(thr, "[CX-ERROR] before ${param.method}")
              param.complete(null, null)
            }
          }
          if (!param.returnEarly) {
            try {
              param.complete(chain.proceed(param.args), null)
            } catch (thr: Throwable) {
              param.complete(null, thr)
            }
          }
          if (after != null) {
            val originalResult = param.result
            val originalError = param.throwable
            try {
              after(param)
            } catch (thr: Throwable) {
              param.complete(originalResult, originalError)
              Log.ex(thr, "[CX-ERROR] after ${param.method}")
            }
          }
          param.getResultOrThrowable()
        }
  }
}

fun Method.hookBefore(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hook: Hooker,
): Unhook = ModernHookRuntime.install(this, priority, hook, null)

fun Method.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hooker: Hooker,
): Unhook = ModernHookRuntime.install(this, priority, null, hooker)

fun Constructor<*>.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hooker: Hooker,
): Unhook = ModernHookRuntime.install(this, priority, null, hooker)

class XposedHookFactory {
  internal var beforeMethod: Hooker? = null
  internal var afterMethod: Hooker? = null
  fun before(before: Hooker) { beforeMethod = before }
  fun after(after: Hooker) { afterMethod = after }
}

fun Method.hookMethod(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hook: XposedHookFactory.() -> Unit,
): Unhook {
  val factory = XposedHookFactory().apply(hook)
  return ModernHookRuntime.install(this, priority, factory.beforeMethod, factory.afterMethod)
}
