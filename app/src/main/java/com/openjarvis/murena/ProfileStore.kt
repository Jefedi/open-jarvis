package com.openjarvis.murena

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Secrets, roles and history use one Keystore-backed store. There is no plaintext fallback. */
class ProfileStore private constructor(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext, FILE_NAME,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision
    private fun changed() { revision.value = revision.value + 1 }
    private fun commit(edit: SharedPreferences.Editor) {
        check(edit.commit()) { "Impossible d'enregistrer la configuration chiffrée." }
        changed()
    }
    @Synchronized fun profiles(): List<ConnectionProfile> = JSONArray(prefs.getString("profiles", "[]")).objects().map(ConnectionProfile::fromJson)
    fun profile(id: String): ConnectionProfile? = profiles().firstOrNull { it.id == id }
    @Synchronized fun save(profile: ConnectionProfile) {
        profile.validate()
        val all = profiles().toMutableList()
        val index = all.indexOfFirst { it.id == profile.id }
        if (index < 0) { require(all.size < 40) { "Limite de 40 profils atteinte." }; all.add(profile) } else all[index] = profile
        val edit = prefs.edit().putString("profiles", JSONArray(all.map { it.json() }).toString())
        if (selected(Roles.BRAIN).isBlank() && Providers.info(profile.kind).capability == "llm") edit.putString("role.${Roles.BRAIN}", profile.id)
        commit(edit)
    }
    @Synchronized fun delete(id: String) {
        val edit = prefs.edit().putString("profiles", JSONArray(profiles().filterNot { it.id == id }.map { it.json() }).toString())
        Roles.labels.keys.forEach { if (selected(it) == id) edit.remove("role.$it") }
        edit.putString("fallback", JSONArray(fallbackIds().filterNot { it == id }).toString())
        commit(edit)
    }
    fun selected(role: String): String = prefs.getString("role.$role", if (role == Roles.STT || role == Roles.TTS) Roles.ANDROID else "").orEmpty()
    fun chosen(role: String): ConnectionProfile? = profile(selected(role))
    @Synchronized fun select(role: String, id: String) {
        require(role in Roles.labels) { "Rôle inconnu." }
        if (id.isNotEmpty() && id != Roles.ANDROID && id != Roles.SILENT) {
            val capability = Providers.info(profile(id)?.kind ?: error("Profil supprimé.")).capability
            val expected = when (role) { Roles.STT -> "stt"; Roles.TTS -> "tts"; Roles.VISION -> "vision"; Roles.EMBEDDINGS -> "embeddings"; else -> "llm" }
            require(capability == expected) { "Ce profil ne fournit pas la capacité demandée." }
        } else if (id == Roles.ANDROID || id == Roles.SILENT) {
            require(role == Roles.STT || role == Roles.TTS) { "Le moteur Android est réservé à la voix." }
        }
        commit(prefs.edit().putString("role.$role", id))
    }
    fun fallbackIds(): List<String> = JSONArray(prefs.getString("fallback", "[]")).strings()
    @Synchronized fun setFallback(ids: List<String>) {
        require(ids.distinct().size == ids.size && ids.size <= 10) { "Liste de secours invalide." }
        ids.forEach { require(Providers.info(profile(it)?.kind ?: error("Profil inconnu.")).capability == "llm") }
        commit(prefs.edit().putString("fallback", JSONArray(ids).toString()))
    }
    fun flag(key: String, default: Boolean = false): Boolean = prefs.getBoolean("flag.$key", default)
    @Synchronized fun setFlag(key: String, value: Boolean) = commit(prefs.edit().putBoolean("flag.$key", value))
    fun text(key: String, default: String = ""): String = prefs.getString("setting.$key", default).orEmpty()
    @Synchronized fun setText(key: String, value: String) {
        require(value.length <= 20000)
        commit(prefs.edit().putString("setting.$key", value))
    }
    fun speechSpeed(): Float = text("speed", "1.0").toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
    fun speechVolume(): Float = text("volume", "1.0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    fun language(): String = text("language", "fr-FR")

    data class HistoryEntry(val role: String, val text: String, val time: Long = System.currentTimeMillis(), val provider: String = "")
    @Synchronized fun history(): List<HistoryEntry> = JSONArray(prefs.getString("history", "[]")).objects().map {
        HistoryEntry(it.getString("role"), it.getString("text"), it.getLong("time"), it.optString("provider"))
    }
    @Synchronized fun append(entry: HistoryEntry) {
        if (!flag("history", true)) return
        val entries = (history() + entry.copy(text = entry.text.take(24000))).takeLast(60).toMutableList()
        while (entries.sumOf { it.text.length } > 180000 && entries.size > 1) entries.removeAt(0)
        commit(prefs.edit().putString("history", JSONArray(entries.map {
            JSONObject().put("role", it.role).put("text", it.text).put("time", it.time).put("provider", it.provider)
        }).toString()))
    }
    @Synchronized fun clearHistory() = commit(prefs.edit().remove("history"))
    @Synchronized fun clearAll() = commit(prefs.edit().clear())

    /** Export contains no secrets or custom headers. Import credentials manually on the new device. */
    fun exportRedacted(): String = JSONObject().put("version", 1).put("profiles", JSONArray(profiles().map {
        it.copy(secret = "", headers = emptyMap()).json()
    })).toString(2)
    companion object {
        const val FILE_NAME = "murena_secure_v1"
        @Volatile private var instance: ProfileStore? = null
        fun get(context: Context): ProfileStore = instance ?: synchronized(this) {
            instance ?: ProfileStore(context.applicationContext).also { instance = it }
        }
    }
}
