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
    val allowed = ctx.resources.getStringArray(R.array.xposedscope).toSet() +
        ctx.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(android.content.Intent.CATEGORY_BROWSABLE), 0).map { it.activityInfo.packageName }
    if (pkg !in allowed || pkg !in ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty())
      throw SecurityException("Unsupported caller")
    if (method != "sync") throw SecurityException("Unsupported method")
    return syncPolicy(ctx, pkg, extras)
  }

  companion object {
  const val MAX_SOURCE = 5 * 1024 * 1024
  private const val CHUNK = 32768
  @Synchronized
  internal fun syncPolicy(ctx: android.content.Context, pkg: String, extras: Bundle?): Bundle {
    val prefs = ctx.getSharedPreferences("script_policy", 0)
    val op = extras?.getString("op")
    if (op != null) {
      val id = requireNotNull(extras.getString("id"))
      require(id.length <= 2048)
      val key = "source:$id"
      val offset = extras.getInt("offset")
      require(offset >= 0)
      return when(op) {
        "has" -> Bundle().apply { putBoolean("exists",prefs.contains(key)) }
        "read" -> {
          require(prefs.getBoolean("assigned:$pkg:$id",false))
          val source = requireNotNull(prefs.getString(key,null))
          require(offset <= source.length)
          Bundle().apply { putString("chunk",source.substring(offset,minOf(source.length,offset+CHUNK))); putInt("length",source.length) }
        }
        "write" -> {
          if (!prefs.contains(key)) {
            val chunk = requireNotNull(extras.getString("chunk"))
            require(chunk.length <= CHUNK)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest((pkg+"\u0000"+id).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val file = java.io.File(ctx.cacheDir,"script-upload-$digest")
            // Store UTF-16 code units to preserve surrogate pairs across chunk boundaries.
            java.io.RandomAccessFile(file,"rw").use { out ->
              if(offset==0) out.setLength(0)
              require(out.length()==offset.toLong()*2)
              require(offset+chunk.length <= MAX_SOURCE)
              out.seek(out.length()); out.writeChars(chunk)
              if(extras.getBoolean("last")) {
                out.seek(0)
                val source=CharArray((out.length()/2).toInt()) { out.readChar() }.concatToString()
                require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE)
                prefs.edit().putString(key,source).apply()
              }
            }
            if(extras.getBoolean("last")) file.delete()
          }
          Bundle.EMPTY
        }
        else -> throw IllegalArgumentException("Unknown script operation")
      }
    }
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
    val assignedIds = JSONArray()
    for ((key, value) in prefs.all) {
      if (!key.startsWith("assigned:$pkg:") || value != true) continue
      val id = key.removePrefix("assigned:$pkg:")
      if ((0 until ids.length()).any { ids.getString(it) == id }) continue
      val source = prefs.getString("source:$id", null) ?: continue
      if (extras?.getBoolean("chunked") == true) {
        assignedIds.put(id)
        if (!prefs.getBoolean("enabled:$pkg:$id", true)) disabled.put(id)
      } else if (assigned.toString().length + source.length < 200000) {
        assigned.put(id, source)
        if (!prefs.getBoolean("enabled:$pkg:$id", true)) disabled.put(id)
      }
    }
    for (i in 0 until ids.length()) {
      val id = ids.getString(i)
      if (!prefs.getBoolean("enabled:$pkg:$id", true)) disabled.put(id)
    }
    prefs.edit().putString("inventory:$pkg", ids.toString()).apply()
    return Bundle().apply { putString("disabled", disabled.toString()); putString("assigned", assigned.toString()); putString("assignedIds", assignedIds.toString()) }
  }
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
    val resolver = Chrome.getContext().contentResolver
    fun call(request: Bundle): Bundle = try {
      requireNotNull(resolver.call(Uri.parse("content://org.matrix.chromext.diagnostics"), "sync_policy", null, request))
    } catch (e: IllegalArgumentException) {
      requireNotNull(resolver.call(Uri.parse("content://org.matrix.chromext.scriptpolicy"), "sync", null, request))
    }
    fun operation(op:String,id:String,offset:Int=0)=Bundle().apply {
      putString("op",op); putString("id",id); putInt("offset",offset)
    }
    for(script in scripts) {
      runCatching {
        val source=script.meta+script.code
        if(source.toByteArray(Charsets.UTF_8).size <= ScriptPolicyProvider.MAX_SOURCE &&
          !call(operation("has",script.id)).getBoolean("exists")) {
          var offset=0
          while(offset<source.length) {
            val end=minOf(offset+32768,source.length)
            call(operation("write",script.id,offset).apply {
              putString("chunk",source.substring(offset,end)); putBoolean("last",end==source.length)
            })
            offset=end
          }
        }
      }.onFailure { org.matrix.chromext.utils.Log.ex(it,"[CX-SYNC] source upload failed") }
    }
    val result=call(Bundle().apply { putString("ids",JSONArray(ids).toString()); putBoolean("chunked",true) })
    val assigned = JSONObject(requireNotNull(result.getString("assigned")))
    val pending = JSONArray(result.getString("assignedIds") ?: "[]")
    for(i in 0 until pending.length()) {
      val id=pending.getString(i)
      runCatching {
        val source=StringBuilder()
        do {
          val part=call(operation("read",id,source.length))
          val length=part.getInt("length")
          require(length in 1..ScriptPolicyProvider.MAX_SOURCE)
          val chunk=requireNotNull(part.getString("chunk"))
          require(chunk.isNotEmpty() && source.length+chunk.length<=length)
          source.append(chunk)
        } while(source.length<length)
        accept(id,source.toString())
      }.onFailure { org.matrix.chromext.utils.Log.ex(it,"[CX-SYNC] source download failed") }
    }
    val array = JSONArray(requireNotNull(result.getString("disabled")))
    for (id in assigned.keys()) accept(id, assigned.getString(id))
    org.matrix.chromext.utils.Log.d("[CX-POLICY] sync acknowledged")
    (0 until array.length()).map { array.getString(it) }.toSet()
  }.onFailure { org.matrix.chromext.utils.Log.ex(it, "[CX-POLICY] sync failed; blocking scripts") }
    .getOrElse { scripts.map { it.id }.toSet() }
}
