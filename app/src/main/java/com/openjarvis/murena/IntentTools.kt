package com.openjarvis.murena

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

interface ToolGateway {
    suspend fun definitions(): List<ToolDefinition>
    suspend fun execute(call: ToolCall, providerLabel: String): String
}

/** Only normal public Android intents. No UI clicking, privilege escalation or unattended sends. */
class IntentTools(private val context: Context, private val store: ProfileStore, private val gate: ConfirmationGate) : ToolGateway {
    private data class Remote(val profile: ConnectionProfile, val original: ToolDefinition, val client: McpConnection)
    private val remote = mutableMapOf<String, Remote>()
    private val available = mutableMapOf<String, ToolDefinition>()
    val discoveryWarnings = mutableListOf<String>()
    private fun definition(name: String, description: String, vararg parameters: Pair<String, String>, required: List<String> = parameters.map { it.first }) =
        ToolDefinition(name, description, schema(*parameters, required = required))

    override suspend fun definitions(): List<ToolDefinition> {
        val list = mutableListOf(
            definition("android_apps", "Chercher une application installée. La liste n'est transmise au modèle qu'après confirmation.", "query" to "string", required = emptyList()),
            definition("android_open_app", "Ouvrir une application par son nom exact ou son package, après confirmation.", "application" to "string"),
            definition("android_settings", "Ouvrir le panneau Android wifi, internet, bluetooth, location, sound ou applications. Aucun réglage protégé n'est modifié automatiquement.", "panel" to "string"),
            definition("android_alarm", "Ouvrir l'horloge pour préparer une alarme. Vérification finale dans l'application Horloge.", "hour" to "integer", "minute" to "integer", "label" to "string", required = listOf("hour", "minute")),
            definition("android_timer", "Ouvrir l'horloge pour préparer un minuteur.", "seconds" to "integer", "label" to "string", required = listOf("seconds")),
            definition("android_dial", "Préparer un numéro dans le composeur. L'utilisateur déclenche lui-même l'appel.", "number" to "string"),
            definition("android_message", "Préparer un SMS. L'utilisateur effectue lui-même l'envoi dans sa messagerie.", "number" to "string", "message" to "string"),
            definition("android_map", "Ouvrir une destination dans l'application de cartes.", "destination" to "string"),
            definition("android_volume", "Régler uniquement le volume multimédia et relire sa valeur.", "percent" to "integer"),
            definition("android_media", "Transmettre play, pause, play_pause, next ou previous au lecteur multimédia.", "command" to "string")
        )
        val homes = store.profiles().filter { it.kind == "homeassistant" && it.allowlist.isNotEmpty() }
        if (homes.isNotEmpty()) {
            list.add(definition("homeassistant_entities", "Lister les entités autorisées. Profils : " + homes.joinToString { "${it.name} (${it.id})" }, "profile_id" to "string"))
            list.add(definition("homeassistant_state", "Lire l'état d'une entité explicitement autorisée.", "profile_id" to "string", "entity_id" to "string"))
            list.add(definition("homeassistant_service", "Appeler un service sur UNE entité autorisée, après confirmation ; relire ensuite son état.", "profile_id" to "string", "entity_id" to "string", "service" to "string", "data" to "object", required = listOf("profile_id", "entity_id", "service")))
        }
        remote.clear(); discoveryWarnings.clear()
        store.profiles().filter { it.kind == "mcp" && it.allowlist.isNotEmpty() }.take(5).forEach { profile ->
            val client = McpConnection(profile)
            try {
                client.listTools().filter { it.name in profile.allowlist }.take(30).forEach { original ->
                    val hash = MessageDigest.getInstance("SHA-256").digest((profile.id + ":" + original.name).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                    val name = "mcp_$hash"
                    remote[name] = Remote(profile, original, client)
                    list.add(ToolDefinition(name, "${profile.name} — ${original.name}. ${original.description}".take(1500), original.parameters))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { discoveryWarnings.add("Serveur MCP indisponible : ${profile.name}") }
        }
        available.clear(); list.forEach { available[it.name] = it }
        return list
    }
    override suspend fun execute(call: ToolCall, providerLabel: String): String {
        val definition = available[call.name] ?: error("Outil non disponible ou non autorisé.")
        ArgumentValidation.validate(call.arguments, definition.parameters)
        val arguments = JSONObject(call.arguments.toString())
        val remoteTarget = remote[call.name]
        val profile = remoteTarget?.profile ?: if (call.name.startsWith("homeassistant_"))
            store.profile(arguments.getString("profile_id"))?.takeIf { it.kind == "homeassistant" } ?: error("Profil Home Assistant invalide.") else null
        val details = "Opération : ${remoteTarget?.original?.name ?: call.name}\n\n${arguments.toString(2)}" +
            (profile?.let { "\n\nServeur choisi : ${it.name}\n${it.url}" } ?: "") +
            "\n\nLe résultat sera transmis à : $providerLabel.\nRefuser ou laisser expirer ne lance pas l'opération."
        if (!gate.request("Autoriser cette opération ?", details)) return JSONObject().put("executed", false).put("reason", "Refus ou expiration de la confirmation.").toString()
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Déverrouillez le téléphone avant de continuer." }
        if (profile != null) check(store.profile(profile.id) == profile) { "Le profil a changé après la confirmation." }
        if (remoteTarget != null) return remoteTarget.client.callTool(remoteTarget.original.name, arguments).toString()
        if (profile != null) {
            val home = HomeAssistantConnection(profile)
            return when (call.name) {
                "homeassistant_entities" -> JSONArray(home.selectedEntities().map { it.json() }).toString()
                "homeassistant_state" -> home.read(arguments.getString("entity_id")).json().toString()
                "homeassistant_service" -> home.call(arguments.getString("entity_id"), arguments.getString("service"), arguments.optJSONObject("data") ?: JSONObject()).toString()
                else -> error("Outil distant inconnu.")
            }
        }
        return withContext(Dispatchers.Main) {
            when (call.name) {
                "android_apps" -> JSONArray(apps(context).filter { it.second.contains(arguments.optString("query"), true) || it.first.contains(arguments.optString("query"), true) }.take(80)
                    .map { JSONObject().put("package", it.first).put("name", it.second) }).toString()
                "android_open_app" -> {
                    val name = arguments.getString("application").trim()
                    val matches = apps(context).filter { it.first == name || it.second.equals(name, true) }
                    require(matches.size == 1) { "Application absente ou nom ambigu. Demandez une recherche d'applications." }
                    launch(context.packageManager.getLaunchIntentForPackage(matches.single().first) ?: error("Application sans écran de lancement."))
                }
                "android_settings" -> launch(Intent(when (arguments.getString("panel")) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS; "internet" -> Settings.ACTION_WIRELESS_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS; "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                    else -> error("Panneau Android non pris en charge.")
                }))
                "android_alarm" -> {
                    val hour = arguments.getInt("hour"); val minute = arguments.getInt("minute"); require(hour in 0..23 && minute in 0..59)
                    launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, arguments.optString("label", "Jarvis")).putExtra(AlarmClock.EXTRA_SKIP_UI, false))
                }
                "android_timer" -> {
                    val seconds = arguments.getInt("seconds"); require(seconds in 1..86400)
                    launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, arguments.optString("label", "Jarvis")).putExtra(AlarmClock.EXTRA_SKIP_UI, false))
                }
                "android_dial" -> launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone(arguments.getString("number")), null)))
                "android_message" -> launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone(arguments.getString("number")), null)).putExtra("sms_body", arguments.getString("message")))
                "android_map" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(arguments.getString("destination")))))
                "android_volume" -> {
                    val percent = arguments.getInt("percent"); require(percent in 0..100)
                    val audio = context.getSystemService(AudioManager::class.java); val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent * maximum / 100, AudioManager.FLAG_SHOW_UI)
                    JSONObject().put("volume_observed", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("maximum", maximum).toString()
                }
                "android_media" -> {
                    val code = when (arguments.getString("command")) {
                        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> error("Commande média inconnue.")
                    }
                    val audio = context.getSystemService(AudioManager::class.java)
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    "Commande envoyée au lecteur actif. L'état de lecture n'est pas vérifié."
                }
                else -> error("Aucune implémentation disponible ; aucune action exécutée.")
            }
        }
    }
    private fun launch(intent: Intent): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        check(intent.resolveActivity(context.packageManager) != null) { "Aucune application installée ne peut traiter cette demande." }
        context.startActivity(intent)
        return "L'interface de l'application cible a été demandée à Android. Terminez ou vérifiez l'opération dans cette application ; aucun envoi ni appel automatique."
    }
    private fun phone(value: String): String = value.trim().also { require(it.matches(Regex("\\+?[0-9 ()-]{3,30}"))) { "Numéro invalide. Les codes spéciaux ne sont pas acceptés." } }
    companion object {
        fun apps(context: Context): List<Pair<String, String>> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
}
