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
    for (i in 0 until ids.length()) {
      val id = ids.getString(i)
      if (!prefs.getBoolean("enabled:$pkg:$id", true)) disabled.put(id)
    }
    prefs.edit().putString("inventory:$pkg", ids.toString()).apply()
    return Bundle().apply { putString("disabled", disabled.toString()) }
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object ScriptPolicy {
  fun disabled(ids: List<String>): Set<String> = runCatching {
    val result = Chrome.getContext().contentResolver.call(
      Uri.parse("content://org.matrix.chromext.scriptpolicy"), "sync", null,
      Bundle().apply { putString("ids", JSONArray(ids).toString()) })
    val array = JSONArray(requireNotNull(result?.getString("disabled")))
    (0 until array.length()).map { array.getString(it) }.toSet()
  }.onFailure { org.matrix.chromext.utils.Log.ex(it, "[CX-POLICY] sync failed; blocking scripts") }
    .getOrElse { ids.toSet() }
}
