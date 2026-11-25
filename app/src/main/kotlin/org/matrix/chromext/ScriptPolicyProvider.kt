package org.matrix.chromext

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject

class ScriptPolicyProvider : ContentProvider() {
  override fun onCreate() = true
  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val ctx = requireNotNull(context)
    val pkg = callingPackage ?: throw SecurityException("Missing caller")
    val allowed = ctx.resources.getStringArray(R.array.xposedscope).toSet()
    if (pkg !in allowed || pkg !in ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty())
      throw SecurityException("Unsupported caller")
    if (method != "sync") throw SecurityException("Unsupported method")
    val prefs = ctx.getSharedPreferences("script_policy", 0)
    val ids = JSONArray(extras?.getString("ids") ?: "[]")
    require(ids.length() <= 5000)
    val disabled = JSONArray()
    val incoming = JSONObject(extras?.getString("sources") ?: "{}")
    val edit = prefs.edit()
    for (id in incoming.keys()) {
      val source = incoming.getString(id)
      if (source.length <= 200000 && !prefs.contains("source:$id")) {
        edit.putString("source:$id", source)
      }
    }
    edit.apply()
    val assigned = JSONObject()
    for ((key, value) in prefs.all) {
      if (!key.startsWith("assigned:$pkg:") || value != true) continue
      val id = key.removePrefix("assigned:$pkg:")
      if ((0 until ids.length()).any { ids.getString(it) == id }) continue
      val source = prefs.getString("source:$id", null) ?: continue
      if (assigned.toString().length + source.length < 200000) assigned.put(id, source)
    }
    for (i in 0 until ids.length()) {
      val id = ids.getString(i)
      if (!prefs.getBoolean("enabled:$pkg:$id", true)) disabled.put(id)
    }
    prefs.edit().putString("inventory:$pkg", ids.toString()).apply()
    return Bundle().apply { putString("disabled", disabled.toString()); putString("assigned", assigned.toString()) }
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object ScriptPolicy {
  fun disabled(scripts: List<org.matrix.chromext.script.Script>, accept: (String, String) -> Unit): Set<String> = runCatching {
    val ids = scripts.map { it.id }
    val sources = JSONObject()
    for (script in scripts) {
      val source = script.meta + script.code
      if (sources.toString().length + source.length < 200000) sources.put(script.id, source)
    }
    val result = Chrome.getContext().contentResolver.call(
      Uri.parse("content://org.matrix.chromext.scriptpolicy"), "sync", null,
      Bundle().apply { putString("ids", JSONArray(ids).toString()); putString("sources", sources.toString()) })
    val assigned = JSONObject(requireNotNull(result?.getString("assigned")))
    for (id in assigned.keys()) accept(id, assigned.getString(id))
    val array = JSONArray(requireNotNull(result?.getString("disabled")))
    (0 until array.length()).map { array.getString(it) }.toSet()
  }.onFailure { org.matrix.chromext.utils.Log.ex(it, "[CX-POLICY] sync failed; blocking scripts") }
    .getOrElse { scripts.map { it.id }.toSet() }
}
