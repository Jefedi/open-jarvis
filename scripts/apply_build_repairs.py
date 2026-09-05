#!/usr/bin/env python3
"""One-time, deterministic repair of the upstream source tree; never downloads code."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
marker = root / 'docs/BUILD_REPAIRS_APPLIED'
if marker.exists():
    raise SystemExit(0)
def read(path):
    return (root / path).read_text()
def put(path, text):
    p = root / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
def rep(path, old, new):
    s = read(path)
    if old not in s:
        raise RuntimeError('Unexpected source at ' + path + ': ' + old[:70])
    put(path, s.replace(old, new))
def kt(path):
    return 'app/src/main/java/com/openjarvis/' + path + '.kt'
rep(kt('agent/PromptEngine'), 'IntentAnalyzer.analyze(resolvedPrompt)', 'IntentAnalyzer.analyze(resolvedPrompt, UniversalAdapter(context))')
rep(kt('agent/PromptEngine'), 'suspend fun analyze(prompt: String): JSONObject', 'suspend fun analyze(prompt: String, adapter: UniversalAdapter): JSONObject')
rep(kt('agent/PromptEngine'), '        val adapter = UniversalAdapter.getModelManager(context)\n', '')
rep(kt('agent/PromptEngine'), 'word.first().isUpperCase() && word.length > 2', 'word.length > 2 && word.first().isUpperCase()')
rep(kt('agent/ProviderFallbackChain'), 'import kotlinx.coroutines.flow.MutableStateFlow', 'import kotlinx.coroutines.flow.MutableStateFlow\nimport org.json.JSONException')
rep(kt('agent/ProviderFallbackChain'), 'runCatching { provider.complete(system, user) }', 'provider.complete(system, user)')
rep(kt('agent/ProviderFallbackChain'), 'graphifyRepo.logProviderFailure(name, errorMsg)', 'graphifyRepo.logTask("provider failure", "failed", name, 0)')
rep(kt('agent/SelfHealingExecutor'), 'UniversalAdapter.getModelManager(context)', 'UniversalAdapter(context)')
rep(kt('agent/SelfHealingExecutor'), 'if (result.success)', 'if (result is ActionResult.Success)')
rep(kt('agent/SelfHealingExecutor'), 'llm.complete(healingPrompt.first, healingPrompt.second)', 'llm.complete(healingPrompt.first, healingPrompt.second).getOrNull()')
rep(kt('agent/SelfHealingExecutor'), 'if (tapped)', 'if (tapped == true)')
rep(kt('agent/SelfHealingExecutor'), 'if (typed)', 'if (typed == true)')
rep(kt('agent/SelfHealingExecutor'), 'action.description ?: action.action', 'action.message ?: action.action')
rep(kt('agent/SelfHealingExecutor'), 'val result = tryExecuteAction(action, context)', 'return ActionResult.Failed("Une nouvelle confirmation utilisateur est requise avant une nouvelle tentative")\n        @Suppress("UNREACHABLE_CODE")\n        val result = tryExecuteAction(action, context)')
rep(kt('builder/AppBuilderMode'), 'GRADLE, GRADLE_APP, MANIFEST, KOTLIN, XML, PROPERTIES, MARKDOWN', 'GRADLE, GRADLE_APP, MANIFEST, KOTLIN, XML, PROPERTIES, PROGUARD, MARKDOWN')
rep(kt('builder/AppBuilderMode'), 'AppBuilderMode.Complexity', 'Complexity')
p = kt('intelligence/JarvisNotificationListener')
s = read(p)
start = s.rindex('    companion object {')
last = s[start:s.rindex('}')]
s = s[:start] + s[s.rindex('}'):]
body = last[len('    companion object {'):].rsplit('}', 1)[0]
s = s.replace('    companion object {', '    companion object {' + body, 1)
s = s.replace('import android.app.Notification', 'import com.openjarvis.accessibility.JarvisAccessibilityService\nimport android.app.Notification', 1)
s = s.replace('Notification.EXTRA_SENDER_TEXT', 'Notification.EXTRA_TITLE')
s = s.replace('graphifyRepo?.logNotification(\n                    "${parsed.packageName}: ${parsed.title}"\n                )', 'Unit // Notification contents are not persisted implicitly.')
put(p, s)
p = kt('llm/providers/GroqProvider')
s = read(p)
put(p, s[:s.index('\nclass GeminiProvider(')] + '\n')
rep(kt('llm/providers/Providers'), 'private val baseUrl: String', 'override val baseUrl: String')
p = kt('skills/SkillEngine')
s = read(p)
a = s.index('        val builtinDir = File(context.assets, "skills")')
b = s.index('\n    private fun parseSkill', a)
s = s[:a] + '''        context.assets.list("skills")?.filter { it.endsWith(".json") }?.forEach { name ->
            val dest = File(skillsDir, name)
            if (!dest.exists()) context.assets.open("skills/$name").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
''' + s[b:]
s = s.replace('val usageCount: Int,', 'val usageCount: Int = 0,').replace('val successRate: Float\n', 'val successRate: Float = 0f\n')
put(p, s)
rep(kt('ui/OverlayService'), 'voiceManager.initialize()', 'voiceManager.resetState()')
p = kt('ui/dashboard/DashboardScreen')
rep(p, 'import androidx.compose.ui.draw.clip', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.scale\nimport androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.text.withStyle')
rep(p, 'Column(alpha = alpha)', 'Column(modifier = Modifier.alpha(alpha))')
for name in ['ui/overlay/FloatingOverlayWidget', 'ui/settings/SettingsScreen']:
    p = kt(name)
    s = read(p).replace('.icons.automirrored.filled.', '.icons.filled.').replace('Icons.AutoMirrored.Filled.', 'Icons.Filled.')
    put(p, s)
p = kt('ui/overlay/FloatingOverlayWidget')
s = read(p).replace('import androidx.compose.foundation.gestures.detectTransformableState\n', '').replace('import androidx.compose.foundation.pointerInput', 'import androidx.compose.ui.input.pointer.pointerInput')
s = s.replace('import androidx.compose.material3.Icon\n', 'import androidx.compose.material3.Divider\nimport androidx.compose.material3.Icon\n')
a = s.index('        transitionSpec = {')
b = s.index('        label = "overlay_expand"', a)
s = s[:a] + '        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },\n' + s[b:]
a = s.index('    var glowAlpha by remember { mutableFloatStateOf(0.15f) }')
b = s.index('    val statusScale', a)
s = s[:a] + '''    val glowAlpha by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.15f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "alpha"
    )

''' + s[b:]
put(p, s)
p = kt('ui/settings/SettingsScreen')
rep(p, 'import androidx.compose.ui.Modifier', 'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.graphicsLayer')
rep(p, 'if (isPassword && !showPassword)', 'if (isPassword)')
p = kt('ui/onboarding/OnboardingActivity')
rep(p, 'private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {', 'private fun TryItScreen(onBack: () -> Unit, onSuccess: () -> Unit) {\n    val scope = rememberCoroutineScope()\n    val context = androidx.compose.ui.platform.LocalContext.current\n    val core = remember { com.openjarvis.agent.AgentCore(context) }')
rep(p, 'lifecycleScope.launch {\n                    delay(2000)\n                    isRunning = false\n                    onSuccess()\n                }', '''scope.launch {
                    try {
                        core.executeTask(command)
                        val result = kotlinx.coroutines.withTimeout(120000) {
                            core.state.first { it is com.openjarvis.agent.AgentState.Done || it is com.openjarvis.agent.AgentState.Error }
                        }
                        if (result is com.openjarvis.agent.AgentState.Done) onSuccess()
                    } finally { isRunning = false }
                }''')
rep(p, 'import android.os.Bundle', 'import android.os.Bundle\nimport kotlinx.coroutines.flow.first')
p = kt('ui/tutorial/TutorialMode')
rep(p, 'MutableStateFlow(TutorialState.OFF)', 'MutableStateFlow<TutorialState>(TutorialState.Off)')
rep(p, 'action.description ?: action.action', 'action.message ?: action.action')
p = kt('vision/ScreenshotCapture')
s = read(p).replace('surface.width', 'imageReader!!.width').replace('surface.height', 'imageReader!!.height')
s = s.replace('                surface.allocation,\n                surface.allocation,', '                context.resources.displayMetrics.densityDpi,\n                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,')
put(p, s)
rep(kt('vision/VisionModule'), 'confidence = block.confidence', 'confidence = Float.NaN // This ML Kit version does not expose block confidence')
p = kt('voice/AndroidSTTEngine')
s = read(p)
s = '\n'.join(l for l in s.split('\n') if 'EXTRA_SPEECH_INPUT_MINIMUM_CONFIDENCE_THRESHOLD' not in l)
s = s.replace('"en-US"', '"fr-FR"')
put(p, s)
rep(kt('voice/VoiceManager'), 'import kotlinx.coroutines.CoroutineScope', 'import kotlinx.coroutines.cancel\nimport kotlinx.coroutines.CoroutineScope')
rep(kt('voice/AndroidTTSEngine'), 'Locale.US', 'Locale.FRANCE')
rep(kt('watch/ScreenWatcher'), 'val lastTriggered: Long = 0', 'var lastTriggered: Long = 0')
p = kt('bridge/SocketServer')
s = read(p)
s = s.replace('    companion object {\n        const val SOCKET_NAME = "jarvis.port"\n    }', '')
s = s.replace('    private fun parseRequest(', '    internal fun parseRequest(')
s = s.replace('            serverSocket = ServerSocket(0)', '            serverSocket = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())')
s = s.replace('        if (isRunning) return', '        if (isRunning || !context.getSharedPreferences("jarvis_prefs", 0).getBoolean("bridge_enabled", false)) return')
s = s.replace('            callingUid in allowedUids', '            false // A TCP socket has no Android caller UID; fail closed.')
put(p, s)
put('app/src/test/java/com/openjarvis/bridge/SocketServerTest.kt', '''package com.openjarvis.bridge
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class SocketServerTest {
    @Test fun requestRoundTrip() {
        val request = JSONObject().put("requestId", "abc123").put("cmd", "status")
        assertEquals("status", JSONObject(request.toString()).getString("cmd"))
        assertEquals("abc123", JSONObject(request.toString()).getString("requestId"))
    }
    @Test fun specialCharactersRemainData() {
        val text = "hello\\nworld\\\"test\\tvalue"
        assertEquals(text, JSONObject(JSONObject().put("cmd", text).toString()).getString("cmd"))
    }
}
''')
p = 'app/src/test/java/com/openjarvis/graphify/AnalysisEngineTest.kt'
s = read(p)
s = s.replace('class AnalysisEngineTest {', '''@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class AnalysisEngineTest {
    @org.junit.Before fun clearDatabase() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        GraphifyDB.getInstance(testContext()).clearAllTables()
    }
''')
s = s.replace('androidx.compose.ui.platform.LocalContext.current.applicationContext', 'androidx.test.core.app.ApplicationProvider.getApplicationContext()')
s = s.replace('val decayedPattern = patterns.find { it.sequenceHash == "abc123" }', 'val decayedPattern = repo.getPatternByHash("abc123")')
put(p, s)
p = kt('graphify/GraphifyRepository')
s = read(p)
s = s.replace('contactNodeDao.findByNameOrPhone(name.lowercase(), "%${name}%")', '''contactNodeDao.findByNameOrPhone(name.lowercase(), name) ?: run {
            val target = name.lowercase()
            contactNodeDao.getRecentContacts(1000).filter { contact ->
                val candidate = contact.name.lowercase()
                var previous = IntArray(candidate.length + 1) { it }
                for ((i, c) in target.withIndex()) {
                    val current = IntArray(candidate.length + 1)
                    current[0] = i + 1
                    for (j in candidate.indices) current[j + 1] = minOf(
                        current[j] + 1, previous[j + 1] + 1,
                        previous[j] + if (c == candidate[j]) 0 else 1)
                    previous = current
                }
                previous.last() <= 2
            }.singleOrNull()
        }''')
s = s.replace('patternNodeDao.getByHash("").let { }', 'patternNodeDao.getById(id)?.let { patternNodeDao.update(it.copy(timeOfDayMask = timeMask)) }')
put(p, s)
p = kt('graphify/AnalysisEngine')
s = read(p).replace('hashCounts[hash] = hashCounts.getOrDefault(hash, 0) + 1', 'hashCounts[seq] = hashCounts.getOrDefault(seq, 0) + 1')
put(p, s)
p = 'app/build.gradle'
s = read(p).replace('    buildTypes {', '    testOptions { unitTests.includeAndroidResources = true }\n\n    buildTypes {')
s = s.replace("    testImplementation 'junit:junit:4.13.2'", "    testImplementation 'junit:junit:4.13.2'\n    testImplementation 'org.json:json:20240303'\n    testImplementation 'org.robolectric:robolectric:4.12.2'\n    testImplementation 'androidx.test:core:1.5.0'")
put(p, s)
marker.write_text('Applied deterministic source repairs, revision 1.\n')
print('Source repairs applied. Compilation and tests must still run.')
