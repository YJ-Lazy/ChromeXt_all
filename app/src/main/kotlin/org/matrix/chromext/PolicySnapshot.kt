package org.matrix.chromext

import android.content.Context
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.script.Script
import org.matrix.chromext.utils.ModernHookRuntime
import java.security.MessageDigest

/** Framework-owned transport, plus a private last-known-good decision cache in each host. */
internal object PolicySnapshot {
  const val GROUP = "scripthub_policy_v1"
  const val POINTER = "published"
  const val MAX_BYTES = 32 * 1024 * 1024
  private const val FORMAT = "scripthub-policy-v1"
  private const val CACHE = "scripthub_offline_policy_v1"
  private var pointer = ""
  private var decoded: JSONObject? = null

  fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

  private fun fingerprint(script: Script) = digest((script.meta + script.code).toByteArray(Charsets.UTF_8))
  private val header = Regex("""(?m)^[ \t]*//[ \t]*==UserScript==[ \t]*\r?\n[\s\S]*?^[ \t]*//[ \t]*==/UserScript==[ \t]*(?:\r?\n|$)""")
  private fun fingerprint(source: String): String? {
    val text = source.removePrefix("\uFEFF")
    val match = header.find(text) ?: return null
    return digest(text.substring(match.range.first).toByteArray(Charsets.UTF_8))
  }

  /** Publish only execution policy and sources known/assigned to browsers; no GM state or UI settings. */
  fun encode(state: Map<String, *>): ByteArray {
    val policies = sortedMapOf<String, MutableSet<String>>()
    val deleted = sortedSetOf<String>()
    for ((key, value) in state) {
      when {
        key.startsWith("inventory:") && value is String -> {
          val pkg = key.removePrefix("inventory:")
          val ids = JSONArray(value)
          require(ids.length() <= 5000)
          val set = policies.getOrPut(pkg) { sortedSetOf() }
          for (i in 0 until ids.length()) set.add(ids.getString(i))
        }
        (key.startsWith("assigned:") || key.startsWith("enabled:")) && value is Boolean -> {
          val rest = key.substringAfter(':')
          val pkg = rest.substringBefore(':')
          val id = rest.substringAfter(':', "")
          if (id.isNotEmpty() && (key.startsWith("enabled:") || value))
            policies.getOrPut(pkg) { sortedSetOf() }.add(id)
        }
        key.startsWith("deleted:") && value == true -> deleted.add(key.removePrefix("deleted:"))
      }
    }
    val browserJson = JSONObject()
    val sources = JSONObject()
    for ((pkg, ids) in policies) {
      require(pkg.matches(Regex("[A-Za-z0-9_.]+")) && ids.size <= 5000)
      val entries = JSONObject()
      val installed = inventory(state, pkg)
      for (id in ids) {
        if (id in deleted) continue
        val source = state["source:$id"] as? String ?: continue
        require(source.toByteArray(Charsets.UTF_8).size <= ScriptPolicyProvider.MAX_SOURCE)
        entries.put(id, state["enabled:$pkg:$id"] != false &&
            (state["assigned:$pkg:$id"] == true || id in installed))
        if (!sources.has(id)) sources.put(id, source)
      }
      browserJson.put(pkg, entries)
    }
    return JSONObject().put("format", FORMAT).put("browsers", browserJson)
        .put("sources", sources).put("deleted", JSONArray(deleted.toList()))
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
  }

  private fun inventory(state: Map<String, *>, pkg: String): Set<String> {
    val array = JSONArray(state["inventory:$pkg"] as? String ?: "[]")
    return (0 until array.length()).map { array.getString(it) }.toSet()
  }

  data class Result(val blocked: Set<String>, val unknownIds: Set<String>)
  val generation: String get() = pointer

  /** Read the small generation pointer each sync; parse the file only when it changes. */
  fun read(): JSONObject? {
    val module = ModernHookRuntime.module ?: return null
    val remote = module.getRemotePreferences(GROUP)
    val revision = remote.getString(POINTER, null) ?: return null
    val parts = revision.split(':')
    require(parts.size == 2 && parts[0] in listOf("a", "b") && parts[1].matches(Regex("[0-9a-f]{64}")))
    if (revision == pointer && decoded != null) return decoded
    val bytes = ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile("policy-" + parts[0] + ".json")).use { input ->
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(65536)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(out.size() + n <= MAX_BYTES)
        out.write(buffer, 0, n)
      }
      out.toByteArray()
    }
    check(digest(bytes) == parts[1]) { "Shared policy checksum mismatch" }
    val data = JSONObject(bytes.toString(Charsets.UTF_8))
    require(data.getString("format") == FORMAT)
    data.getJSONObject("browsers")
    data.getJSONObject("sources")
    data.getJSONArray("deleted")
    pointer = revision
    decoded = data
    return data
  }

  fun apply(data: JSONObject, pkg: String, scripts: List<Script>,
      remove: (String) -> Unit, accept: (String, String) -> Boolean): Result {
    val entries = data.getJSONObject("browsers").optJSONObject(pkg) ?: JSONObject()
    val sources = data.getJSONObject("sources")
    val tombstones = data.getJSONArray("deleted")
    val deleted = (0 until tombstones.length()).map { tombstones.getString(it) }.toSet()
    val local = scripts.associateBy { it.id }
    val blocked = mutableSetOf<String>()
    val unknown = mutableSetOf<String>()
    for (id in local.keys) {
      if (id in deleted) {
        blocked.add(id)
        runCatching { remove(id) }
      } else if (!entries.has(id)) {
        blocked.add(id)
        unknown.add(id)
      }
    }
    for (id in entries.keys()) {
      if (id in deleted || !entries.getBoolean(id)) { blocked.add(id); continue }
      val source = sources.optString(id, "")
      val expected = fingerprint(source)
      if (expected == null || source.toByteArray(Charsets.UTF_8).size > ScriptPolicyProvider.MAX_SOURCE) {
        blocked.add(id)
        continue
      }
      if (local[id]?.let { fingerprint(it) } != expected) {
        if (!runCatching { accept(id, source) }.getOrDefault(false)) blocked.add(id)
      }
    }
    return Result(blocked, unknown)
  }

  /** Save only exact code fingerprints that were authorized, not an unconditional allow-all. */
  fun remember(ctx: Context, scripts: List<Script>, blocked: Set<String>, generation: String = "") {
    val allowed = JSONObject()
    for (script in scripts.sortedBy { it.id }) {
      if (script.id !in blocked) allowed.put(script.id, fingerprint(script))
    }
    val prefs = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
    val value = allowed.toString()
    if (prefs.getString("allowed", null) != value || prefs.getString("generation", "") != generation)
      check(prefs.edit().putString("allowed", value).putString("generation", generation).commit()) { "Offline policy cache write failed" }
  }

  fun offline(ctx: Context, scripts: List<Script>, requiredGeneration: String? = null): Set<String> {
    val cached = runCatching {
      val prefs = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
      if (requiredGeneration != null && prefs.getString("generation", "") != requiredGeneration) JSONObject()
      else JSONObject(prefs.getString("allowed", "{}") ?: "{}")
    }.getOrElse { JSONObject() }
    return scripts.filter { cached.optString(it.id) != fingerprint(it) }.map { it.id }.toSet()
  }

  fun invalidate(ctx: Context) {
    ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().clear().commit()
  }
}
