package org.matrix.chromext

import org.json.JSONObject
import org.matrix.chromext.script.Script
import org.matrix.chromext.utils.Log
import java.security.MessageDigest

// Kept for the unused build-intermediate UI's source-size checks.
object ScriptPolicyProvider { const val MAX_SOURCE = 5 * 1024 * 1024 }

object ScriptPolicy {
  private data class Entry(val id: String, val source: String, val targets: Set<String>, val deleted: Boolean)
  private var entries: List<Entry>? = null

  @Synchronized fun initialize(bytes: ByteArray) {
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    check(root.getString("format") == "ChromeXt-library-v1")
    val list = root.getJSONArray("scripts")
    require(list.length() <= 5000)
    val parsed = (0 until list.length()).map { i ->
      val record = list.getJSONObject(i)
      val id = record.getString("id")
      val source = record.optString("source")
      val deleted = record.optBoolean("deleted")
      val targets = record.getJSONArray("targets")
      require(id.isNotBlank() && id.length <= 2048 && source.toByteArray(Charsets.UTF_8).size <= ScriptPolicyProvider.MAX_SOURCE)
      require(deleted || source.isNotEmpty())
      Entry(id, source, (0 until targets.length()).map { targets.getString(it) }.toSet(), deleted)
    }
    require(parsed.map { it.id }.distinct().size == parsed.size)
    entries = parsed
    Log.i("[CX-POLICY] loaded standalone library: ${parsed.size}")
  }

  @Synchronized fun disabled(scripts: List<Script>, remove: (String) -> Unit, accept: (String, String) -> Boolean): Set<String> {
    val records = entries ?: return scripts.map { it.id }.toSet()
    val pkg = Chrome.getContext().packageName
    val local = scripts.associateBy { it.id }
    val blocked = mutableSetOf<String>()
    for (entry in records) {
      if (entry.deleted) {
        blocked.add(entry.id)
        if (entry.id in local) runCatching { remove(entry.id) }.onFailure { Log.ex(it, "[CX-SYNC] remove failed") }
      } else if (pkg !in entry.targets) {
        blocked.add(entry.id)
      } else {
        val old = local[entry.id]
        val revision = digest(entry.source)
        val prefs = Chrome.getContext().getSharedPreferences("chromext_standalone_revisions", 0)
        if (old == null || prefs.getString(entry.id, null) != revision ||
            prefs.getString("local:" + entry.id, null) != digest(old.meta + old.code)) {
          runCatching {
            check(accept(entry.id, entry.source)) { "Script metadata ID mismatch" }
            val updated = org.matrix.chromext.script.ScriptDbManager.scripts.first { it.id == entry.id }
            prefs.edit().putString(entry.id, revision).putString("local:" + entry.id, digest(updated.meta + updated.code)).apply()
          }.onFailure { blocked.add(entry.id); Log.ex(it, "[CX-SYNC] import failed: ${entry.id}") }
        }
      }
    }
    return blocked
  }
  private fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
      .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
