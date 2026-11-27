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
        "has" -> Bundle().apply { putBoolean("exists",(prefs.contains(key) || prefs.getBoolean("deleted:$id",false))) }
        "read" -> {
          // Use a single preference snapshot: edits/deletions may race a chunk request.
          val state = prefs.all
          val revision = state["revision:$id"] as? String ?: ""
          val inventory = JSONArray(state["inventory:$pkg"] as? String ?: "[]")
          val authorized = state["assigned:$pkg:$id"] == true ||
              (revision.isNotEmpty() && (0 until inventory.length()).any { inventory.optString(it) == id })
          val source = state[key] as? String
          val status = when {
            state["deleted:$id"] == true -> "deleted"
            !authorized -> "not-assigned"
            extras.containsKey("revision") && extras.getString("revision") != revision -> "revision-changed"
            source.isNullOrEmpty() -> "missing-source"
            offset >= source.length -> "invalid-offset"
            else -> "ok"
          }
          Bundle().apply {
            putString("status", status)
            if (status == "ok" && source != null) {
              putString("revision", revision)
              putInt("length", source.length)
              putString("chunk", source.substring(offset, minOf(source.length, offset + CHUNK)))
            }
          }
        }
        "write" -> {
          if (!prefs.contains(key) && !prefs.getBoolean("deleted:$id",false) && !prefs.contains("revision:$id")) {
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
    val incoming = JSONObject(extras?.getString("sources") ?: "{}")
    val edit = prefs.edit()
    for (id in incoming.keys()) {
      val source = incoming.getString(id)
      if (source.length <= 200000 && !prefs.contains("source:$id") && !prefs.getBoolean("deleted:$id", false) && !prefs.contains("revision:$id")) {
        edit.putString("source:$id", source)
      }
    }
    edit.apply()
    val state = prefs.all
    val installed = (0 until ids.length()).map { ids.getString(it) }.toSet()
    val assigned = JSONObject()
    val pending = linkedSetOf<String>()
    val blocked = linkedSetOf<String>()
    val removed = JSONArray()
    val missing = JSONArray()
    val revisions = JSONObject()
    val known = JSONObject(extras?.getString("revisions") ?: "{}")
    val chunked = extras?.getBoolean("chunked") == true
    fun queue(id: String) {
      pending.add(id)
      revisions.put(id, state["revision:$id"] as? String ?: "")
    }
    for ((key, value) in state) {
      if (!key.startsWith("assigned:$pkg:") || value != true) continue
      val id = key.removePrefix("assigned:$pkg:")
      if (id in installed || state["deleted:$id"] == true) continue
      val source = state["source:$id"] as? String ?: continue
      if (chunked) {
        queue(id)
        if (state["enabled:$pkg:$id"] == false) blocked.add(id)
      } else if (assigned.toString().length + source.length < 200000) {
        assigned.put(id, source)
        if (state["enabled:$pkg:$id"] == false) blocked.add(id)
      }
    }
    for (id in installed) {
      if (state["deleted:$id"] == true) {
        removed.put(id)
        blocked.add(id)
        continue
      }
      val revision = state["revision:$id"] as? String
      val source = state["source:$id"] as? String
      if (source.isNullOrEmpty()) {
        if (revision == null) missing.put(id) else blocked.add(id)
      } else if (revision != null && chunked && known.optString(id) != revision) {
        queue(id)
      }
      if (state["enabled:$pkg:$id"] == false) blocked.add(id)
    }
    prefs.edit().putString("inventory:$pkg", ids.toString()).apply()
    return Bundle().apply {
      putString("disabled", JSONArray(blocked.toList()).toString())
      putString("assigned", assigned.toString())
      putString("assignedIds", JSONArray(pending.toList()).toString())
      putString("removed", removed.toString())
      putString("revisions", revisions.toString())
      putString("missingSources", missing.toString())
    }
  }
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object ScriptPolicy {
  @Synchronized
  fun disabled(scripts: List<org.matrix.chromext.script.Script>, remove: (String) -> Unit,
      currentScripts: () -> List<org.matrix.chromext.script.Script>,
      accept: (String, String) -> Boolean): Set<String> {
    val ctx = Chrome.getContext()
    val blockOnFailure = scripts.map { it.id }.toMutableSet()
    var sharedResult: PolicySnapshot.Result? = null
    runCatching {
      val data = PolicySnapshot.read()
      if (data != null) {
        val result = PolicySnapshot.apply(data, ctx.packageName, scripts, remove, accept)
        sharedResult = result
        PolicySnapshot.remember(ctx, currentScripts(), result.blocked)
        if (!result.unknown) return result.blocked
      }
    }.onFailure { org.matrix.chromext.utils.Log.ex(it, "[CX-POLICY] shared snapshot unavailable") }
    return runCatching {
      val ids = scripts.map { it.id }
      val resolver = Chrome.getContext().contentResolver
      // Only fall back when a provider is unavailable, never when it rejects a request.
      val primary = resolver.acquireUnstableContentProviderClient(Uri.parse("content://org.matrix.chromext.diagnostics"))
      val provider = primary ?: requireNotNull(
          resolver.acquireUnstableContentProviderClient(Uri.parse("content://org.matrix.chromext.scriptpolicy"))) {
        "Script policy provider unavailable"
      }
      val method = if (primary != null) "sync_policy" else "sync"
      try {
        fun call(request: Bundle): Bundle = requireNotNull(provider.call(method, null, request))
        fun operation(op: String, id: String, offset: Int = 0) = Bundle().apply {
          putString("op", op); putString("id", id); putInt("offset", offset)
        }
        val local = Chrome.getContext().getSharedPreferences("scripthub_revisions", 0)
        val known = JSONObject()
        for (id in ids) local.getString(id, null)?.let { known.put(id, it) }
        val result = call(Bundle().apply {
          putString("revisions", known.toString())
          putString("ids", JSONArray(ids).toString())
          putBoolean("chunked", true)
        })
        val disabled = JSONArray(requireNotNull(result.getString("disabled")))
        val blocked = (0 until disabled.length()).map { disabled.getString(it) }.toMutableSet()
        val assigned = JSONObject(requireNotNull(result.getString("assigned")))
        val removed = JSONArray(result.getString("removed") ?: "[]")
        val revisions = JSONObject(result.getString("revisions") ?: "{}")
        val pending = JSONArray(result.getString("assignedIds") ?: "[]")
        val pendingIds = (0 until pending.length()).map { pending.getString(it) }.distinct()
        blockOnFailure.addAll(pendingIds)
        blockOnFailure.addAll(assigned.keys().asSequence().toList())
        for (i in 0 until removed.length()) {
          val id = removed.getString(i)
          blocked.add(id)
          remove(id)
          local.edit().remove(id).apply()
        }

        // Upload only missing local sources (one inventory request instead of N existence calls).
        val missing = result.getString("missingSources")?.let { JSONArray(it) }
        val byId = scripts.associateBy { it.id }
        val uploadIds = if (missing != null) {
          (0 until missing.length()).map { missing.getString(it) }
        } else {
          // Compatibility with an older companion APK.
          ids.filter { !call(operation("has", it)).getBoolean("exists") }
        }
        for (id in uploadIds) {
          if (id in blocked) continue
          val script = byId[id] ?: continue
          runCatching {
            val source = script.meta + script.code
            if (source.toByteArray(Charsets.UTF_8).size > ScriptPolicyProvider.MAX_SOURCE) return@runCatching
            var offset = 0
            while (offset < source.length) {
              val end = minOf(offset + 32768, source.length)
              call(operation("write", id, offset).apply {
                putString("chunk", source.substring(offset, end))
                putBoolean("last", end == source.length)
              })
              offset = end
            }
          }.onFailure { org.matrix.chromext.utils.Log.ex(it, "[CX-SYNC] source upload failed: $id") }
        }
        for (id in pendingIds) {
          if ((0 until removed.length()).any { removed.optString(it) == id }) continue
          var offset = 0
          val expected = revisions.optString(id, "")
          runCatching {
            val source = StringBuilder()
            var total = -1
            do {
              val part = call(operation("read", id, source.length).apply {
                if (revisions.has(id)) putString("revision", expected)
              })
              val status = part.getString("status") ?: "ok"
              check(status == "ok") { "read rejected: $status" }
              if (part.containsKey("revision") && revisions.has(id))
                check(part.getString("revision") == expected) { "revision changed" }
              val length = part.getInt("length")
              require(length in 1..ScriptPolicyProvider.MAX_SOURCE) { "invalid source length: $length" }
              if (total == -1) total = length
              check(length == total) { "source length changed" }
              val chunk = requireNotNull(part.getString("chunk")) { "missing source chunk" }
              require(chunk.isNotEmpty() && source.length + chunk.length <= total) { "invalid source chunk" }
              source.append(chunk)
              offset = source.length
            } while (source.length < total)
            check(source.toString().toByteArray(Charsets.UTF_8).size <= ScriptPolicyProvider.MAX_SOURCE) { "source exceeds limit" }
            check(accept(id, source.toString())) { "script ID or metadata mismatch" }
            if (expected.isNotEmpty()) local.edit().putString(id, expected).apply()
          }.onFailure {
            // Do not run a stale revision, or a newly imported script whose policy is uncertain.
            blocked.add(id)
            org.matrix.chromext.utils.Log.ex(it, "[CX-SYNC] source download failed: id=$id; offset=$offset; revision=$expected")
          }
        }
        for (id in assigned.keys()) {
          if (!accept(id, assigned.getString(id))) blocked.add(id)
        }
        org.matrix.chromext.utils.Log.d("[CX-POLICY] sync acknowledged; pending=${pendingIds.size}; blocked=${blocked.size}")
        PolicySnapshot.remember(ctx, currentScripts(), blocked)
        blocked.toSet()
      } finally {
        provider.close()
      }
    }.getOrElse { error ->
      org.matrix.chromext.utils.Log.ex(error, "[CX-POLICY] provider sync failed")
      // A rejected caller is not an offline condition. Do not bypass explicit denial.
      if (generateSequence(error) { it.cause }.any { it is SecurityException }) {
        PolicySnapshot.invalidate(ctx)
        currentScripts().map { it.id }.toSet()
      } else {
        val cached = PolicySnapshot.offline(ctx, currentScripts())
        cached + (sharedResult?.blocked ?: emptySet())
      }
    }
  }
}
