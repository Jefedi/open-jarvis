#!/usr/bin/env python3
"""Apply the reviewed Murena wiring once and commit resulting sources in the source CI job."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
marker = root / 'docs/MURENA_WIRING_APPLIED'
if marker.exists(): raise SystemExit(0)
def put(path, content):
    file = root / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content)
def edit(path, old, new):
    file = root / path
    content = file.read_text()
    if old not in content: raise RuntimeError('Source mismatch: ' + path + ': ' + old[:90])
    file.write_text(content.replace(old, new))
base = 'app/src/main/java/com/openjarvis/'
def kt(name): return base + name + '.kt'
edit(kt('vision/VisionModule'), 'confidence = Float.NaN // This ML Kit version does not expose block confidence,', 'confidence = Float.NaN, // This ML Kit version does not expose block confidence.')
edit(kt('murena/Models'), 'val toolName: String = "")', 'val toolName: String = "", val raw: JSONArray? = null, val protocol: String = "")')
edit(kt('murena/Models'), 'data class Completion(val text: String, val calls: List<ToolCall> = emptyList())', 'data class Completion(val text: String, val calls: List<ToolCall> = emptyList(), val raw: JSONArray? = null, val protocol: String = "")')
edit(kt('murena/Models'), 'interface SpeechOutputProvider { suspend fun synthesize(profile: ConnectionProfile, text: String, speed: Float): PcmAudio }', '''interface SpeechOutputProvider {
    suspend fun synthesize(profile: ConnectionProfile, text: String, speed: Float): PcmAudio
    suspend fun stream(profile: ConnectionProfile, text: String, speed: Float, onChunk: (PcmAudio) -> Unit) {
        onChunk(synthesize(profile, text, speed))
    }
}''')
edit(kt('murena/Models'), '"voxtral-mini-latest", "stt")', '"voxtral-mini-latest", "stt", true)')
edit(kt('murena/Models'), '"voxtral-mini-tts-2603", "tts")', '"voxtral-mini-tts-2603", "tts", true)')
edit(kt('murena/AudioController'), '        val selected = store.selected(Roles.STT)\n        inputJob = scope.launch {\n            try {', '        val selected = store.selected(Roles.STT)\n        val previousInput = inputJob\n        inputJob = scope.launch {\n            previousInput?.join()\n            try {')
edit(kt('murena/AudioController'), '        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Le microphone n\'a pas pu être initialisé." }', '''        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); audioRecord = null
            error("Le microphone n'a pas pu être initialisé.")
        }''')
edit(kt('murena/AudioController'), '    fun speak(text: String) {\n        stopSpeech()', '    fun speak(text: String) {\n        val previousOutput = outputJob\n        stopSpeech()')
edit(kt('murena/AudioController'), '        playbackCancelled = false\n        outputJob = scope.launch {\n            try {', '        outputJob = scope.launch {\n            previousOutput?.join()\n            playbackCancelled = false\n            try {')
edit(kt('murena/AudioController'), '''                        SpeechRegistry.output(profile).stream(profile, segment, store.speechSpeed()) { audio ->
                            check(!playbackCancelled) { "Lecture arrêtée." }
                            writeAudio(audio, if (profile.kind == "voxtral-tts") store.speechSpeed() else 1f)
                        }''', '''                        withContext(Dispatchers.IO) {
                            SpeechRegistry.output(profile).stream(profile, segment, store.speechSpeed()) { audio ->
                                check(!playbackCancelled) { "Lecture arrêtée." }
                                try { writeAudio(audio, if (profile.kind == "voxtral-tts") store.speechSpeed() else 1f) }
                                finally { audio.samples.fill(0) }
                            }
                        }''')
edit(kt('murena/AudioController'), '    private var writtenFrames = 0L', '    @Volatile private var writtenFrames = 0L')
edit(kt('murena/IntentTools'), 'private val gate: ConfirmationGate) : ToolGateway', 'private val gate: ConfirmationGate, private val includeRemote: Boolean = true) : ToolGateway')
edit(kt('murena/IntentTools'), '        val homes = store.profiles().filter', '        val homes = (if (includeRemote) store.profiles() else emptyList()).filter')
edit(kt('murena/IntentTools'), '        store.profiles().filter { it.kind == "mcp"', '        (if (includeRemote) store.profiles() else emptyList()).filter { it.kind == "mcp"')
edit(kt('murena/AssistantRuntime'), '''                val gateway = IntentTools(context, store, gate)
                val local = LocalCommands.parse(command)''', '''                val local = LocalCommands.parse(command)
                val localOnly = local != null && (mode == "local" || profiles.isEmpty() || store.flag("local_shortcuts", true))
                val gateway = IntentTools(context, store, gate, includeRemote = !localOnly)''')
# A composable Context lookup must not be called from a coroutine callback.
edit(kt('murena/MurenaUi'), 'private fun ProfilesView(runtime: AssistantRuntime, revision: Long) {', 'private fun ProfilesView(runtime: AssistantRuntime, revision: Long) {\n    val context = LocalContext.current')
edit(kt('murena/MurenaUi'), 'runtimeContext(runtime).contentResolver', 'context.contentResolver')
p = root / kt('murena/MurenaUi')
s = p.read_text()
start = s.index('        OutlinedButton(onClick = { openSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION')
end = s.index('        Divider(); Text("Confidentialité"', start)
s = s[:start] + '''        Text("Le lancement reste disponible depuis l'icône de l'application et le raccourci d'assistant Android lorsque la ROM accepte une activité ACTION_ASSIST. Le bouton flottant, la tuile et le service d'écoute permanent ne sont pas intégrés à cette version.")
''' + s[end:]
p.write_text(s)
put(kt('ui/MainActivity'), '''package com.openjarvis.ui

import android.os.Bundle
import android.content.Intent
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openjarvis.murena.AssistantRuntime
import com.openjarvis.murena.MurenaApp
import com.openjarvis.ui.theme.OpenJarvisTheme

class MainActivity : ComponentActivity() {
    private var runtime: AssistantRuntime? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        try { runtime = AssistantRuntime.get(applicationContext) } catch (_: Exception) { }
        setContent {
            OpenJarvisTheme {
                val ready = runtime
                if (ready != null) MurenaApp(ready)
                else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Stockage chiffré indisponible", style = MaterialTheme.typography.headlineSmall)
                        Text("Déverrouillez le téléphone et réessayez. Jarvis n'utilisera pas de stockage non chiffré. Une restauration depuis un autre appareil peut nécessiter la réinitialisation des données dans les réglages Android.")
                        Button(onClick = { recreate() }) { Text("Réessayer") }
                    }
                }
            }
        }
    }
    override fun onStart() { super.onStart(); runtime?.audio?.foreground = true }
    override fun onStop() { runtime?.audio?.foreground = false; super.onStop() }
}
''')
put(kt('ui/SettingsActivity'), '''package com.openjarvis.ui
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
''')
put(kt('agent/AgentCore'), '''package com.openjarvis.agent
import android.content.Context
import com.openjarvis.murena.AssistantRuntime
import com.openjarvis.murena.IntentTools
import com.openjarvis.murena.Roles
import kotlinx.coroutines.flow.StateFlow

/** Legacy entry points use the same confirmed runtime, never the old unguarded executor. */
class AgentCore(private val context: Context) {
    private val runtime = AssistantRuntime.get(context)
    val state: StateFlow<AgentState> get() = runtime.state
    fun executeTask(command: String) = runtime.submit(command)
    fun getStateFlow(): StateFlow<AgentState> = state
    fun getCurrentProviderName(): String = runtime.provider.value.ifBlank { "Non configuré" }
    suspend fun testConnection(): Result<Long> = runCatching {
        val start = System.nanoTime()
        val profile = runtime.store.chosen(Roles.BRAIN) ?: error("Choisissez un profil principal.")
        runtime.test(profile)
        (System.nanoTime() - start) / 1000000
    }
    suspend fun getAnalyzedAppCount(): Int = IntentTools.apps(context).size
    suspend fun getAIAppCount(): Int = IntentTools.apps(context).count { it.first in setOf("com.openai.chatgpt", "com.anthropic.claude", "ai.mistral.chat") }
}
''')
put('app/src/main/AndroidManifest.xml', '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="com.android.alarm.permission.SET_ALARM" />
    <queries>
        <intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>
        <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
        <intent><action android:name="android.speech.RecognitionService" /></intent>
        <intent><action android:name="android.intent.action.SET_ALARM" /></intent>
        <intent><action android:name="android.intent.action.SET_TIMER" /></intent>
        <intent><action android:name="android.intent.action.DIAL" /><data android:scheme="tel" /></intent>
        <intent><action android:name="android.intent.action.SENDTO" /><data android:scheme="smsto" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="geo" /></intent>
    </queries>
    <application android:allowBackup="false" android:fullBackupContent="false" android:usesCleartextTraffic="true"
        android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name" android:supportsRtl="true" android:theme="@style/Theme.OpenJarvis">
        <activity android:name=".ui.MainActivity" android:exported="true" android:launchMode="singleTop">
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
            <intent-filter><action android:name="android.intent.action.ASSIST" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
        </activity>
        <activity android:name=".ui.SettingsActivity" android:exported="false" android:parentActivityName=".ui.MainActivity" />
        <service android:name=".murena.JarvisSystemTtsService" android:exported="true" android:label="Open Jarvis — voix choisie">
            <intent-filter><action android:name="android.intent.action.TTS_SERVICE" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
            <meta-data android:name="android.speech.tts" android:resource="@xml/murena_tts_engine" />
        </service>
    </application>
</manifest>
''')
put('app/src/main/res/xml/murena_tts_engine.xml', '''<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" android:settingsActivity="com.openjarvis.ui.MainActivity" />
''')
edit('app/build.gradle', 'versionCode 1\n        versionName "1.0.0"', 'versionCode 2\n        versionName "0.2.0-murena-preview"')
edit('app/build.gradle', "    androidTestImplementation 'androidx.test.ext:junit:1.1.5'", '''    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test:runner:1.5.2'
    androidTestImplementation 'androidx.test:rules:1.5.0'
    androidTestImplementation 'androidx.test:core:1.5.0'
    androidTestImplementation 'androidx.compose.ui:ui-test-junit4'
    androidTestImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0' ''')
marker.write_text('Murena runtime wiring revision 1 applied. Compilation and executed tests remain required.\n')
print('Runtime wiring applied to actual sources.')
