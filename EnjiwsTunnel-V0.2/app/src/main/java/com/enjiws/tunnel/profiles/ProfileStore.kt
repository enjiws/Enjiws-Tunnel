package com.enjiws.tunnel.profiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Keeps imported .hc files as opaque source artifacts. HTTP Custom formats can be
 * encrypted/version-specific; this class deliberately does not attempt to decrypt
 * credentials or infer tunnel settings from undocumented binary data.
 */
data class ImportedProfile(
    val id: String,
    val name: String,
    val storedFile: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: String = "HC_OPAQUE"
)

object ProfileStore {
    private const val PREFS = "enjiws_profiles"
    private const val KEY = "imports"
    private const val MAX_IMPORT_BYTES = 10L * 1024L * 1024L

    fun all(context: Context): List<ImportedProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                ImportedProfile(
                    id = o.getString("id"), name = o.getString("name"),
                    storedFile = o.getString("storedFile"),
                    sizeBytes = o.getLong("sizeBytes"), sha256 = o.getString("sha256"),
                    format = o.optString("format", "HC_OPAQUE")
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun importHc(context: Context, uri: Uri): ImportedProfile {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "Imported-config.hc"
        require(displayName.endsWith(".hc", ignoreCase = true)) {
            "Pilih file dengan ekstensi .hc"
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_IMPORT_BYTES) { "Ukuran file melebihi batas 10 MB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("File tidak dapat dibaca")
        require(bytes.isNotEmpty()) { "File konfigurasi kosong" }

        val dir = File(context.filesDir, "imported_profiles").apply { mkdirs() }
        val safeBase = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = "hc_${System.currentTimeMillis()}"
        val destination = File(dir, "${id}_$safeBase")
        destination.writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val profile = ImportedProfile(id, displayName, destination.name, bytes.size.toLong(), digest)
        val existing = all(context).filterNot { it.id == id }
        val array = JSONArray()
        (existing + profile).forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("storedFile", p.storedFile)
                put("sizeBytes", p.sizeBytes); put("sha256", p.sha256); put("format", p.format)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
        return profile
    }

    fun delete(context: Context, profile: ImportedProfile) {
        File(File(context.filesDir, "imported_profiles"), profile.storedFile).delete()
        val array = JSONArray()
        all(context).filterNot { it.id == profile.id }.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("storedFile", p.storedFile)
                put("sizeBytes", p.sizeBytes); put("sha256", p.sha256); put("format", p.format)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }
}
