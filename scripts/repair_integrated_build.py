#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def replace(path, old, new):
    p = root / path
    value = p.read_text()
    if old in value and new not in value: p.write_text(value.replace(old, new))
base = 'app/src/main/java/com/openjarvis/'
replace(base + 'accessibility/JarvisAccessibilityService.kt', 'AccessibilityEvent.TYPE_ALL_MASK', 'AccessibilityEvent.TYPES_ALL_MASK')
replace(base + 'murena/AudioController.kt', 'recorder.read(buffer, 0, buffer.size())', 'recorder.read(buffer, 0, buffer.size)')
replace(base + 'murena/SafeHttp.kt', '''        .dns(Dns { host ->
            val addresses = Dns.SYSTEM.lookup(host)
            if (!validateUrl(profile.url, profile.allowLocalHttp).isHttps && !addresses.all(::isLocal)) {
                throw UnknownHostException("HTTP is restricted to explicitly enabled local networks")
            }
            addresses
        }).build()''', '''        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (!validateUrl(profile.url, profile.allowLocalHttp).isHttps && !addresses.all(::isLocal)) {
                    throw UnknownHostException("HTTP is restricted to explicitly enabled local networks")
                }
                return addresses
            }
        }).build()''')
replace(base + 'murena/AudioController.kt', '''        stopSpeech()
        if (!foreground)''', '''        val previousSpeech = outputJob
        stopSpeech()
        if (!foreground)''')
replace(base + 'murena/AudioController.kt', '''        inputJob = scope.launch {
            previousInput?.join()
            try {''', '''        inputJob = scope.launch {
            previousInput?.join()
            previousSpeech?.join()
            try {''')
replace(base + 'murena/SystemVoiceEngine.kt', '''    override fun onGetLanguage(): Array<String> = arrayOf("fra", "FRA", "")''', '''    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): MutableSet<String> =
        mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    override fun onGetLanguage(): Array<String> = arrayOf("fra", "FRA", "")''')
p = root / (base + 'murena/SystemVoiceEngine.kt')
s = p.read_text()
feature = '    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): MutableSet<String> =\n        mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)\n'
while feature + feature in s: s = s.replace(feature + feature, feature)
p.write_text(s)
replace('app/build.gradle', "    androidTestImplementation 'androidx.compose.ui:ui-test-junit4'", "    androidTestImplementation platform('androidx.compose:compose-bom:2023.10.01')\n    androidTestImplementation 'androidx.compose.ui:ui-test-junit4'")
p = root / 'app/build.gradle'; s = p.read_text()
bom = "    androidTestImplementation platform('androidx.compose:compose-bom:2023.10.01')\n"
while bom + bom in s: s = s.replace(bom + bom, bom)
p.write_text(s)
replace('app/src/test/java/com/openjarvis/graphify/AnalysisEngineTest.kt', 'confidence > 0`()', 'positive confidence`()')
replace('app/src/test/java/com/openjarvis/graphify/AnalysisEngineTest.kt', 'import kotlinx.coroutines.test.runTest', 'import kotlinx.coroutines.runBlocking')
replace('app/src/test/java/com/openjarvis/graphify/AnalysisEngineTest.kt', 'runTest(Dispatchers.IO)', 'runBlocking(Dispatchers.IO)')
replace(base + 'murena/LlmBackends.kt', 'JSONArray(tools.map(::function))', 'JSONArray(tools.map { JSONObject().put("name", it.name).put("description", it.description).put("parametersJsonSchema", it.parameters) })')
replace(base + 'murena/LlmBackends.kt', '.put("response", JSONObject().put("result", m.text))))', '.put("response", JSONObject().put("result", m.text)).apply { if (m.toolId.isNotBlank()) put("id", m.toolId) }))')
replace(base + 'murena/LlmBackends.kt', '.apply { if (m.toolId.isNotBlank()) put("id", m.toolId) }', '.apply { if (messages.any { previous -> previous.protocol == "gemini" && previous.raw?.objects()?.any { part -> part.optJSONObject("functionCall")?.nullableString("id") == m.toolId } == true }) put("id", m.toolId) }')
p = root / (base + 'murena/AudioController.kt')
s = p.read_text()
declaration = '        val previousSpeech = outputJob\n'
while declaration + declaration in s: s = s.replace(declaration + declaration, declaration)
p.write_text(s)
replace(base + 'murena/AudioController.kt', '''        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,''', '''        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("L'autorisation du microphone a été retirée.")
        }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,''')
replace('app/src/test/java/com/openjarvis/murena/ProtocolTests.kt', 'val j = JSONObject(request.body.readUtf8())', 'val j = JSONObject(request.body.clone().readUtf8())')
replace(base + 'watch/ScreenWatcher.kt', '''    private fun showNotification(title: String, body: String) {''', '''    private fun showNotification(title: String, body: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }''')
# These two upstream services have no callers and are intentionally not registered in
# the Murena manifest. Keep their source as reference rather than grant unused background privileges.
for name in ('ui/OverlayService.kt', 'local/DownloadService.kt'):
    source = root / (base + name)
    target = root / ('app/src/legacyReference/' + name + '.txt')
    if source.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text())
        source.unlink()
print('Integrated compiler, permission and test-source fixes applied if needed.')
