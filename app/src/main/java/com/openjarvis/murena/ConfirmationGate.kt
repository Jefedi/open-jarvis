package com.openjarvis.murena

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The language model never holds a reference to approve(). Only visible user controls do. */
class ConfirmationGate(private val clock: () -> Long = System::currentTimeMillis) {
    data class Pending(val id: String, val title: String, val details: String, val expiresAt: Long)
    private val queue = Mutex()
    private val current = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = current
    private var response: CompletableDeferred<Boolean>? = null
    suspend fun request(title: String, details: String, timeoutMs: Long = 120000): Boolean = queue.withLock {
        require(timeoutMs in 1..300000)
        require(details.length <= 60000) { "Action trop volumineuse pour être confirmée." }
        val deferred = CompletableDeferred<Boolean>()
        val item = Pending(UUID.randomUUID().toString(), title, details, clock() + timeoutMs)
        synchronized(this) { response = deferred; current.value = item }
        try { withTimeoutOrNull(timeoutMs) { deferred.await() } == true }
        finally { synchronized(this) { if (current.value?.id == item.id) { current.value = null; response = null } } }
    }
    @Synchronized fun approve(id: String, allowed: Boolean): Boolean {
        val item = current.value ?: return false
        if (item.id != id) return false
        return response?.complete(allowed && clock() < item.expiresAt) == true
    }
    @Synchronized fun cancel() { response?.complete(false) }
}

object ArgumentValidation {
    fun validate(arguments: JSONObject, schema: JSONObject) {
        require(arguments.toString().length <= 50000) { "Paramètres d'outil trop volumineux." }
        visit(arguments, schema, 0)
    }
    private fun visit(value: Any, definition: JSONObject, depth: Int) {
        require(depth <= 12) { "Paramètres trop imbriqués." }
        definition.optJSONArray("enum")?.let { values ->
            require((0 until values.length()).any { values.get(it).toString() == value.toString() }) { "Valeur hors de la liste autorisée." }
        }
        when (definition.optString("type")) {
            "object" -> {
                require(value is JSONObject) { "Un objet de paramètres est requis." }
                val properties = definition.optJSONObject("properties") ?: JSONObject()
                definition.optJSONArray("required")?.strings()?.forEach { require(value.has(it) && !value.isNull(it)) { "Paramètre requis absent : $it" } }
                value.keys().asSequence().forEach { key ->
                    if (definition.has("additionalProperties") && definition.opt("additionalProperties") == false) require(properties.has(key)) { "Paramètre non autorisé : $key" }
                    properties.optJSONObject(key)?.let { visit(value.get(key), it, depth + 1) }
                }
            }
            "string" -> {
                require(value is String && value.length <= definition.optInt("maxLength", 20000)) { "Texte d'outil invalide ou trop long." }
                require(value.length >= definition.optInt("minLength", 0)) { "Texte d'outil trop court." }
            }
            "number", "integer" -> {
                require(value is Number && value.toDouble().isFinite()) { "Nombre invalide." }
                if (definition.optString("type") == "integer") require(value.toDouble() % 1.0 == 0.0) { "Un nombre entier est requis." }
                require(value.toDouble() >= definition.optDouble("minimum", -Double.MAX_VALUE) && value.toDouble() <= definition.optDouble("maximum", Double.MAX_VALUE)) { "Nombre hors limites." }
            }
            "boolean" -> require(value is Boolean) { "Valeur booléenne requise." }
            "array" -> {
                require(value is JSONArray && value.length() <= definition.optInt("maxItems", 100)) { "Liste invalide ou trop longue." }
                val itemSchema = definition.optJSONObject("items")
                if (itemSchema != null) for (i in 0 until value.length()) visit(value.get(i), itemSchema, depth + 1)
            }
        }
    }
}
