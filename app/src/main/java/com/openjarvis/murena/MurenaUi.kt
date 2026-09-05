package com.openjarvis.murena

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.openjarvis.agent.AgentState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@Composable
fun MurenaApp(runtime: AssistantRuntime) {
    val revision by runtime.store.changes.collectAsState()
    val state by runtime.state.collectAsState()
    val pending by runtime.gate.pending.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Assistant", "Connexions", "Voix", "Outils", "Accès")
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("OPEN JARVIS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Murena · vos modèles, votre voix", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = runtime::cancel, modifier = Modifier.testTag("stop_all")) { Text("Arrêter") }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = index == tab, onClick = { tab = index }, text = { Text(label) }, modifier = Modifier.testTag("tab_$index"))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> ChatView(runtime, revision, state)
                    1 -> ProfilesView(runtime, revision)
                    2 -> VoiceView(runtime, revision)
                    3 -> ToolsView(runtime, revision)
                    else -> AccessView(runtime)
                }
            }
        }
        pending?.let { item ->
            AlertDialog(onDismissRequest = { runtime.gate.approve(item.id, false) },
                title = { Text(item.title) },
                text = { Text(item.details, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("confirmation_details")) },
                confirmButton = { Button(onClick = { runtime.gate.approve(item.id, true) }, modifier = Modifier.testTag("confirm_action")) { Text("Autoriser cette fois") } },
                dismissButton = { TextButton(onClick = { runtime.gate.approve(item.id, false) }, modifier = Modifier.testTag("deny_action")) { Text("Refuser") } })
        }
    }
}

@Composable
private fun ChatView(runtime: AssistantRuntime, revision: Long, state: AgentState) {
    val context = LocalContext.current
    val partial by runtime.partial.collectAsState()
    val provider by runtime.provider.collectAsState()
    val journal by runtime.journal.collectAsState()
    val voice by runtime.audio.status.collectAsState()
    val history = remember(revision) { runtime.store.history() }
    var input by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("auto") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runtime.audio.startMicrophone { runtime.submit(it, mode) }
        else Toast.makeText(context, "Le clavier reste disponible sans microphone.", Toast.LENGTH_LONG).show()
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Choice("Mode", mode, listOf("auto" to "Automatique", "fast" to "Rapide", "powerful" to "Puissant", "private" to "Privé : aucun secours", "local" to "Commandes locales"), { mode = it }, Modifier.weight(1f))
            TextButton(onClick = runtime::newConversation) { Text("Effacer") }
        }
        if (provider.isNotBlank()) Text(provider, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("active_provider"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), reverseLayout = true) {
            if (partial.isNotBlank()) item { MessageCard("Réponse en cours", partial) }
            if (state is AgentState.Error) item { MessageCard("À vérifier", state.message) }
            if (state is AgentState.Running) item { Text(state.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("running_status")) }
            if (history.isEmpty()) item {
                MessageCard("Bienvenue", "Ajoutez votre fournisseur dans Connexions, puis choisissez votre voix. Aucune clé n'est incluse dans l'application.\n\nSans compte IA, essayez : « Ouvre les réglages Wi-Fi » ou « Volume à 30 % ». Chaque opération demande votre confirmation.")
            }
            items(history.reversed()) { entry -> MessageCard(if (entry.role == "user") "Vous" else if (entry.role == "error") "À vérifier" else "Jarvis", entry.text) }
        }
        if (journal.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer le journal" else "Journal des opérations (${journal.size})") }
            if (expanded) Text(journal.joinToString("\n\n"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
        }
        if (voice.detail.isNotBlank()) Text(voice.detail, style = MaterialTheme.typography.bodySmall)
        if (voice.transcript.isNotBlank() && voice.stage != "idle") Text(voice.transcript.takeLast(400), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(input, { input = it.take(20000) }, label = { Text("Votre demande") }, maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("command_input"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (voice.stage == "listening") runtime.audio.finishMicrophone()
                else if (voice.stage == "transcribing") runtime.audio.cancelMicrophone()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    runtime.audio.startMicrophone { runtime.submit(it, mode) }
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.weight(1f).testTag("microphone")) { Text(if (voice.stage == "listening") "Terminer l'écoute" else if (voice.stage == "transcribing") "Annuler" else "Parler") }
            Button(onClick = { val command = input; input = ""; runtime.submit(command, mode) }, enabled = input.isNotBlank() && state !is AgentState.Running,
                modifier = Modifier.weight(1f).testTag("send_command")) { Text("Envoyer") }
        }
    }
}

@Composable private fun MessageCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable
private fun ProfilesView(runtime: AssistantRuntime, revision: Long) {
    val profiles = remember(revision) { runtime.store.profiles() }
    var editing by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deleting by remember { mutableStateOf<ConnectionProfile?>(null) }
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { try {
            withContext(Dispatchers.IO) { runtimeContext(runtime).contentResolver.openOutputStream(uri)?.use { it.write(runtime.store.exportRedacted().toByteArray()) } }
            result = "Configuration exportée sans clés ni en-têtes personnalisés."
        } catch (_: Exception) { result = "L'export n'a pas pu être enregistré." } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Vos connexions", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Les clés, les profils et l'historique sont chiffrés avec Android Keystore. Aucun cookie ChatGPT ou jeton privé Codex n'est importé.") }
        item { Button(onClick = { editing = Providers.example("mistral") }, modifier = Modifier.testTag("add_profile")) { Text("Ajouter un profil") } }
        items(profiles, key = { it.id }) { profile ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text("${Providers.info(profile.kind).name}\n${profile.model.ifBlank { "Modèle non choisi" }}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { editing = profile }) { Text("Modifier") }
                    TextButton(enabled = !testing, onClick = { testing = true; scope.launch { try { result = runtime.test(profile) } catch (e: Exception) { result = e.message ?: "Échec du test." } finally { testing = false } } }) { Text("Tester l'API") }
                    TextButton(onClick = { deleting = profile }) { Text("Supprimer") }
                }
            } }
        }
        item { Text("Tester l'API peut consommer le quota du fournisseur sélectionné. Aucun appel payant n'est lancé automatiquement à l'ouverture des réglages.", style = MaterialTheme.typography.bodySmall) }
        if (testing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (result.isNotBlank()) item { MessageCard("Résultat", result) }
        item { Divider(); Text("Affectation des modèles", style = MaterialTheme.typography.titleLarge) }
        items(listOf(Roles.BRAIN, Roles.FAST, Roles.POWERFUL, Roles.PRIVATE, Roles.VISION, Roles.EMBEDDINGS)) { role -> RoleChoice(runtime.store, role, profiles) }
        item { Text("Secours autorisés (ordre affiché)", style = MaterialTheme.typography.titleMedium) }
        items(profiles.filter { Providers.info(it.kind).capability == "llm" }, key = { "fallback-${it.id}" }) { profile ->
            val order = runtime.store.fallbackIds()
            Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = profile.id in order, onCheckedChange = { checked -> runtime.store.setFallback(if (checked) order + profile.id else order - profile.id) })
                Text(profile.name + if (profile.id in order) " · ${order.indexOf(profile.id) + 1}" else "", Modifier.weight(1f).padding(top = 14.dp))
                if (order.indexOf(profile.id) > 0) TextButton(onClick = { val updated = order.toMutableList(); val index = updated.indexOf(profile.id); val previous = updated[index - 1]; updated[index - 1] = profile.id; updated[index] = previous; runtime.store.setFallback(updated) }) { Text("Monter") }
            }
        }
        item { Text("Le mode Privé utilise seulement le profil privé : aucun transfert vers un fournisseur de secours. Un serveur Ollama sur votre réseau n'est pas un modèle exécuté dans le téléphone.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = { export.launch("jarvis-profils-sans-secrets.json") }) { Text("Exporter sans secrets") } }
    }
    editing?.let { profile -> ProfileEditor(profile, onDismiss = { editing = null }, onSave = { runtime.store.save(it); editing = null }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer ${profile.name} ?") },
        text = { Text("Les identifiants et les affectations de ce profil seront supprimés du stockage chiffré.") },
        confirmButton = { Button(onClick = { runtime.store.delete(profile.id); deleting = null }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }) }
}

// Context is supplied explicitly by the composable; it is never serialized into a profile.
@Composable private fun runtimeContext(runtime: AssistantRuntime): Context = LocalContext.current

@Composable
private fun RoleChoice(store: ProfileStore, role: String, profiles: List<ConnectionProfile>) {
    val capability = when (role) { Roles.STT -> "stt"; Roles.TTS -> "tts"; Roles.VISION -> "vision"; Roles.EMBEDDINGS -> "embeddings"; else -> "llm" }
    val options = mutableListOf("" to "Non configuré")
    if (role == Roles.STT || role == Roles.TTS) options.add(Roles.ANDROID to "Moteur Android installé")
    if (role == Roles.TTS) options.add(Roles.SILENT to "Mode silencieux")
    options.addAll(profiles.filter { Providers.info(it.kind).capability == capability }.map { it.id to it.name })
    Choice(Roles.labels.getValue(role), store.selected(role), options, { store.select(role, it) }, Modifier.fillMaxWidth())
}

@Composable
private fun ProfileEditor(initial: ConnectionProfile, onDismiss: () -> Unit, onSave: (ConnectionProfile) -> Unit) {
    var value by remember(initial.id) { mutableStateOf(initial) }
    var timeout by remember { mutableStateOf(initial.timeoutSeconds.toString()) }
    var contextSize by remember { mutableStateOf(initial.contextTokens.toString()) }
    var output by remember { mutableStateOf(initial.outputTokens.toString()) }
    var temperature by remember { mutableStateOf(initial.temperature.toString()) }
    var headers by remember { mutableStateOf(JSONObject(initial.headers).toString()) }
    var allowlist by remember { mutableStateOf(initial.allowlist.joinToString("\n")) }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.93f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Profil de connexion", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice("Fournisseur", value.kind, Providers.catalog.map { it.id to it.name }, { kind ->
                        val preset = Providers.example(kind)
                        value = preset.copy(id = value.id, name = preset.name, secret = value.secret)
                        discovered = emptyList(); result = ""
                    }, Modifier.fillMaxWidth())
                    Field("Nom", value.name, { value = value.copy(name = it) }, "profile_name")
                    Field("URL de base", value.url, { value = value.copy(url = it.trim()) }, "profile_url")
                    Field("Modèle", value.model, { value = value.copy(model = it.trim()) }, "profile_model")
                    Field("Clé API / jeton", value.secret, { value = value.copy(secret = it.trim()) }, "profile_secret", secret = true)
                    val capability = Providers.info(value.kind).capability
                    if (capability == "llm" || value.kind == "voxtral-tts") {
                        OutlinedButton(enabled = !loading, onClick = { loading = true; scope.launch { try {
                            val p = value.copy(headers = parseHeaders(headers)); p.validate()
                            discovered = if (p.kind == "voxtral-tts") ApiSpeech().voices(p) else LlmRegistry.backend(p).models(p).take(300).map { it to it }
                            result = "${discovered.size} choix reçus. Les modèles et voix restent saisis manuellement si le serveur ne fournit pas de liste."
                        } catch (e: Exception) { result = e.message ?: "Découverte impossible." } finally { loading = false } } }) { Text(if (value.kind == "voxtral-tts") "Charger les voix prédéfinies" else "Charger les modèles") }
                    }
                    if (discovered.isNotEmpty()) Choice("Choix du serveur", if (value.kind == "voxtral-tts") value.voice else value.model, discovered, {
                        value = if (value.kind == "voxtral-tts") value.copy(voice = it) else value.copy(model = it)
                    }, Modifier.fillMaxWidth())
                    if (capability == "tts") Field("Identifiant de la voix", value.voice, { value = value.copy(voice = it.trim()) }, "profile_voice")
                    if (capability in setOf("llm", "stt", "tts")) Toggle("Réponse progressive / streaming", value.streaming, { value = value.copy(streaming = it) })
                    if (capability == "llm") Toggle("Appels d'outils natifs", value.tools, { value = value.copy(tools = it) })
                    Toggle("Autoriser HTTP sur réseau local uniquement", value.allowLocalHttp, { value = value.copy(allowLocalHttp = it) })
                    Text("HTTPS conserve toujours la vérification du certificat. HTTP est refusé vers les adresses publiques, même après activation.", style = MaterialTheme.typography.bodySmall)
                    Field("Délai maximal (secondes)", timeout, { timeout = it }, "profile_timeout")
                    if (capability == "llm" || capability == "vision") {
                        Field("Contexte configuré (tokens)", contextSize, { contextSize = it }, "profile_context")
                        Field("Limite de réponse (tokens)", output, { output = it }, "profile_output")
                        Field("Température", temperature, { temperature = it }, "profile_temperature")
                    }
                    Field("En-têtes personnalisés (objet JSON chiffré)", headers, { headers = it }, "profile_headers", secret = true, singleLine = false)
                    if (capability == "mcp" || capability == "homeassistant") {
                        Field(if (capability == "mcp") "Noms exacts des outils autorisés (un par ligne)" else "Entités autorisées (light.salon ou light.*)", allowlist, { allowlist = it }, "profile_allowlist", singleLine = false)
                        Text("Aucune entité ni aucun outil n'est accessible tant que cette liste est vide. Vous pouvez aussi les sélectionner dans Outils.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Annuler") }
                    Button(modifier = Modifier.testTag("save_profile"), onClick = { try {
                        val ready = value.copy(timeoutSeconds = timeout.toInt(), contextTokens = contextSize.toInt(), outputTokens = output.toInt(), temperature = temperature.replace(',', '.').toDouble(),
                            headers = parseHeaders(headers), allowlist = allowlist.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.distinct())
                        ready.validate(); onSave(ready)
                    } catch (e: Exception) { result = e.message ?: "Vérifiez les paramètres." } }) { Text("Enregistrer") }
                }
            }
        }
    }
}

private fun parseHeaders(value: String): Map<String, String> = JSONObject(value.ifBlank { "{}" }).let { obj ->
    obj.keys().asSequence().associateWith { obj.getString(it) }
}

@Composable private fun VoiceView(runtime: AssistantRuntime, revision: Long) {
    val store = runtime.store
    val profiles = remember(revision) { store.profiles() }
    val voice by runtime.audio.status.collectAsState()
    val context = LocalContext.current
    var transcript by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runtime.audio.startMicrophone { transcript = it }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Reconnaissance et synthèse indépendantes", style = MaterialTheme.typography.headlineSmall)
        RoleChoice(store, Roles.STT, profiles); RoleChoice(store, Roles.TTS, profiles)
        Field("Langue", store.language(), { store.setText("language", it) }, "voice_language")
        Choice("Moteur TTS Android", store.text("android_tts_engine"), listOf("" to "Moteur installé par défaut") + AudioController.installedTtsEngines(context), { store.setText("android_tts_engine", it) }, Modifier.fillMaxWidth())
        Toggle("Lire automatiquement les réponses", store.flag("auto_speak"), { store.setFlag("auto_speak", it) })
        Toggle("Terminer l'écoute après un silence", store.flag("voice_end_detection", true), { store.setFlag("voice_end_detection", it) })
        Text("Le microphone ne démarre qu'après appui sur un bouton, avec indicateur visible. Les enregistrements ne sont pas conservés. Parler interrompt la réponse vocale ; aucune écoute permanente n'est activée.")
        Text("Vitesse : ${"%.1f".format(store.speechSpeed())}")
        Slider(value = store.speechSpeed(), onValueChange = { store.setText("speed", it.toString()) }, valueRange = 0.5f..2f, steps = 14)
        Text("Volume de la voix : ${(store.speechVolume() * 100).toInt()} %")
        Slider(value = store.speechVolume(), onValueChange = { store.setText("volume", it.toString()) }, valueRange = 0f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runtime.audio.speak("Bonjour. Je suis Jarvis. Voici un test de votre voix française.") }) { Text("Tester la voix") }
            OutlinedButton(onClick = runtime.audio::stopAll) { Text("Tout arrêter") }
        }
        OutlinedButton(onClick = {
            if (voice.stage == "listening") runtime.audio.finishMicrophone()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runtime.audio.startMicrophone { transcript = it }
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text(if (voice.stage == "listening") "Terminer l'enregistrement" else "Tester le microphone et la transcription") }
        if (voice.detail.isNotBlank()) Text(voice.detail)
        if (transcript.isNotBlank()) MessageCard("Texte reconnu (non envoyé au cerveau IA)", transcript)
        Divider()
        Toggle("Proposer cette voix aux autres applications Android", store.flag("system_tts"), { enabled -> if (enabled) consent = true else store.setFlag("system_tts", false) })
        Text("Cette option fournit un moteur TTS système. Sélectionnez ensuite « Open Jarvis » dans les paramètres de synthèse vocale Android. Les applications qui possèdent leur propre moteur ne sont pas remplacées.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { openSettings(context, Intent("com.android.settings.TTS_SETTINGS")) }) { Text("Ouvrir la synthèse vocale Android") }
        Text("Les tests de voix et de transcription avec un fournisseur distant peuvent consommer son quota.", style = MaterialTheme.typography.bodySmall)
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Autoriser la voix système distante ?") },
        text = { Text("Les textes confiés au moteur Jarvis par d'autres applications seront transmis au profil TTS actuellement choisi. Ils peuvent contenir des données personnelles. Aucun texte ne sera transmis si le moteur distant n'est pas configuré. Désactivez cette option pour révoquer l'accès.") },
        confirmButton = { Button(onClick = { store.setFlag("system_tts", true); consent = false }) { Text("J'autorise ce traitement") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Refuser") } })
}

@Composable private fun ToolsView(runtime: AssistantRuntime, revision: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember(revision) { runtime.store.profiles().filter { it.kind == "mcp" || it.kind == "homeassistant" } }
    var server by remember { mutableStateOf<ConnectionProfile?>(null) }
    var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picture = it }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Vos services autorisés", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Home Assistant et MCP sont inaccessibles au modèle tant que vous n'avez pas sélectionné les entités ou outils. Chaque exécution conserve une confirmation humaine.") }
        if (profiles.isEmpty()) item { Text("Ajoutez un profil Home Assistant ou MCP dans Connexions.") }
        items(profiles) { profile -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text("${profile.allowlist.size} autorisations enregistrées")
            OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch { try {
                items = if (profile.kind == "mcp") McpConnection(profile).listTools().map { it.name to "${it.name} — ${it.description.take(140)}" }
                    else HomeAssistantConnection(profile).discover().map { it.id to "${it.name} (${it.id}) : ${it.state}" }
                selected = profile.allowlist.toSet(); server = profile
            } catch (e: Exception) { output = e.message ?: "Découverte indisponible." } finally { busy = false } } }) { Text("Découvrir et autoriser") }
        } } }
        item { Divider(); Text("Vision sur une image choisie", style = MaterialTheme.typography.titleLarge) }
        item { Text("L'image sélectionnée explicitement sera envoyée à votre profil Vision après une seconde confirmation. Aucun écran du téléphone n'est capturé.") }
        item { Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Choisir une image") } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (output.isNotBlank()) item { MessageCard("Résultat", output) }
        item { Divider(); Text("Commandes Android disponibles", style = MaterialTheme.typography.titleLarge) }
        item { Text("Ouverture d'applications, panneaux de réglages, préparation d'appels et de SMS, alarmes et minuteurs via l'horloge, destination dans les cartes, volume et commandes multimédias.\n\nLe contrôle générique des écrans, l'envoi automatique de messages et les privilèges système ne sont pas activés dans cette version.") }
        item { Toggle("Raccourcis déterministes sans modèle IA", runtime.store.flag("local_shortcuts", true), { runtime.store.setFlag("local_shortcuts", it) }) }
    }
    server?.let { profile -> Dialog(onDismissRequest = { server = null }) { Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxHeight(0.85f)) {
        Column(Modifier.padding(16.dp)) {
            Text("Autorisations — ${profile.name}", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f)) { items(items, key = { it.first }) { item ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (item.first in selected) selected - item.first else selected + item.first }) {
                    Checkbox(item.first in selected, { checked -> selected = if (checked) selected + item.first else selected - item.first })
                    Text(item.second, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            } }
            Row { TextButton(onClick = { server = null }) { Text("Annuler") }
                Button(onClick = { runtime.store.save(profile.copy(allowlist = selected.toList())); server = null }) { Text("Enregistrer") } }
        }
    } } }
    picture?.let { uri -> AlertDialog(onDismissRequest = { picture = null }, title = { Text("Envoyer l'image sélectionnée ?") },
        text = { Text("Destinataire : ${runtime.store.chosen(Roles.VISION)?.name ?: "aucun profil Vision configuré"}. Cette opération peut consommer le quota du fournisseur.") },
        confirmButton = { Button(onClick = { picture = null; busy = true; scope.launch { try {
            val profile = runtime.store.chosen(Roles.VISION) ?: error("Choisissez un profil Vision dans Connexions.")
            val bytes = withContext(Dispatchers.IO) { selectedImage(context, uri) }
            try { output = ImageApi.describe(profile, bytes, "Décris cette image en français, sans inventer les détails illisibles.") } finally { bytes.fill(0) }
        } catch (e: Exception) { output = e.message ?: "Analyse impossible." } finally { busy = false } } }) { Text("Envoyer cette image") } },
        dismissButton = { TextButton(onClick = { picture = null }) { Text("Annuler") } }) }
}

private fun selectedImage(context: Context, uri: Uri): ByteArray {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "Image non reconnue." }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 1600) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Image illisible.")
    return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() } } finally { bitmap.recycle() }
}

@Composable private fun AccessView(runtime: AssistantRuntime) {
    val context = LocalContext.current
    val store = runtime.store
    var erase by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Intégration Android", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = {
            val manager = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
            val intent = if (Build.VERSION.SDK_INT >= 29 && manager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            openSettings(context, intent)
        }) { Text("Choisir l'assistant par défaut") }
        Text("Le geste ou bouton d'assistant dépend de votre ROM. L'intégration ouvre Jarvis ; aucun microphone permanent n'est activé.")
        OutlinedButton(onClick = { openSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }) { Text("Autoriser le bouton flottant") }
        Button(onClick = {
            if (!Settings.canDrawOverlays(context)) openSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            else { if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(context, Intent(context, AssistantBubbleService::class.java)) }
        }) { Text("Afficher le bouton flottant") }
        OutlinedButton(onClick = { context.stopService(Intent(context, AssistantBubbleService::class.java)) }) { Text("Retirer le bouton flottant") }
        Text("Une tuile « Jarvis » est également disponible dans l'éditeur des réglages rapides Android.")
        Divider(); Text("Confidentialité", style = MaterialTheme.typography.titleLarge)
        Toggle("Conserver un historique chiffré limité", store.flag("history", true), { store.setFlag("history", it); if (!it) store.clearHistory() })
        Text("L'historique est limité à 60 entrées. Les sons du microphone ne sont pas enregistrés sur disque. Les requêtes restent soumises à la politique de conservation de chaque fournisseur distant.")
        Text("Vos clés sont saisies seulement sur le téléphone. Aucun accès Google Play Services n'est requis par les nouveaux fournisseurs réseau. Un moteur vocal Android tiers peut avoir ses propres dépendances.")
        OutlinedButton(onClick = { erase = true }) { Text("Effacer toutes les données Jarvis") }
        Text("Open Jarvis Murena · version de développement. La compilation et les essais Android ne garantissent pas les réponses d'un modèle distant ni les particularités de toutes les ROM.", style = MaterialTheme.typography.bodySmall)
    }
    if (erase) AlertDialog(onDismissRequest = { erase = false }, title = { Text("Tout effacer ?") }, text = { Text("Les profils, clés, autorisations et conversations enregistrés dans Jarvis seront supprimés. Les données déjà transmises aux fournisseurs ne sont pas effacées par cette opération.") },
        confirmButton = { Button(onClick = { runtime.cancel(); store.clearAll(); erase = false }) { Text("Effacer") } }, dismissButton = { TextButton(onClick = { erase = false }) { Text("Annuler") } })
}

@Composable private fun Field(label: String, value: String, change: (String) -> Unit, tag: String, secret: Boolean = false, singleLine: Boolean = true) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth().testTag(tag))
}
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp)); Switch(value, change)
    }
}
@Composable private fun Choice(label: String, selected: String, options: List<Pair<String, String>>, change: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "Non configuré" }, maxLines = 2)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { change(id); expanded = false }) }
        }
    }
}
private fun openSettings(context: Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: Exception) { Toast.makeText(context, "Ce réglage n'est pas disponible sur cette ROM.", Toast.LENGTH_LONG).show() }
}
