#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def replace(path, old, new):
    p = root / path
    value = p.read_text()
    if old in value: p.write_text(value.replace(old, new))
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
# The insertion above must not duplicate an override on subsequent runs.
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
# Gemini v1beta accepts full JSON Schema through parametersJsonSchema, not OpenAPI parameters.
replace(base + 'murena/LlmBackends.kt', 'JSONArray(tools.map(::function))', 'JSONArray(tools.map { JSONObject().put("name", it.name).put("description", it.description).put("parametersJsonSchema", it.parameters) })')
replace(base + 'murena/LlmBackends.kt', '.put("response", JSONObject().put("result", m.text))))', '.put("response", JSONObject().put("result", m.text)).apply { if (m.toolId.isNotBlank()) put("id", m.toolId) }))')
print('Integrated compiler and test-source fixes applied if needed.')
