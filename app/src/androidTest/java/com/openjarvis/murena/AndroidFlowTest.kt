package com.openjarvis.murena

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjarvis.ui.MainActivity
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var store: ProfileStore
    private lateinit var runtime: AssistantRuntime
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ProfileStore.get(context); runtime = AssistantRuntime.get(context)
        compose.runOnIdle { runtime.cancel(); store.clearAll() }
        compose.waitForIdle()
        compose.runOnIdle { runtime.newConversation() }
        server = MockWebServer(); server.start()
    }
    @After fun tearDown() { compose.runOnIdle { runtime.cancel() }; server.shutdown() }
    private fun connection(kind: String) = ConnectionProfile(id = kind, name = "Profil de test", kind = kind,
        url = server.url("/v1").toString(), model = "fixture-model", secret = "fixture-only-secret", voice = "fixture-voice", streaming = false, allowLocalHttp = true)

    @Test fun launchesWithoutGooglePlayServicesAndShowsFrenchInput() {
        compose.onNodeWithTag("command_input").assertIsDisplayed()
        compose.onNodeWithTag("send_command").assertIsDisplayed()
        assertTrue(runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0) }.isFailure)
        val intent = Intent(Intent.ACTION_ASSIST).setPackage(context.packageName)
        assertEquals("com.openjarvis.ui.MainActivity", context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name)
    }
    @Test fun userCanSaveAProfileAndItSurvivesActivityRecreationEncrypted() {
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("add_profile").performClick()
        compose.onNodeWithTag("profile_name").performScrollTo().performTextReplacement("Profil persistant")
        compose.onNodeWithTag("profile_url").performScrollTo().performTextReplacement("https://example.invalid/v1")
        compose.onNodeWithTag("profile_model").performScrollTo().performTextReplacement("fixture-model")
        compose.onNodeWithTag("profile_secret").performScrollTo().performTextReplacement("KEY-FIXTURE-MUST-NOT-BE-PLAINTEXT")
        compose.onNodeWithTag("save_profile").performClick()
        compose.waitUntil(10000) { store.profiles().any { it.name == "Profil persistant" } }
        val profile = store.profiles().single()
        assertEquals(profile.id, store.selected(Roles.BRAIN))
        assertEquals("KEY-FIXTURE-MUST-NOT-BE-PLAINTEXT", profile.secret)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("Profil persistant", store.profiles().single().name)
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${ProfileStore.FILE_NAME}.xml")
        assertTrue(file.exists()); assertFalse(file.readText().contains("KEY-FIXTURE-MUST-NOT-BE-PLAINTEXT"))
        assertFalse(store.exportRedacted().contains("KEY-FIXTURE-MUST-NOT-BE-PLAINTEXT"))
    }
    @Test fun aChatRunsFromTheFrenchUiThroughTheHttpAdapter() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", "Réponse réellement reçue du serveur de test.")))).toString()))
        compose.runOnIdle { val profile = connection("mistral"); store.save(profile); store.select(Roles.BRAIN, profile.id) }
        compose.onNodeWithTag("command_input").performTextInput("Bonjour Jarvis")
        compose.onNodeWithTag("send_command").performClick()
        compose.waitUntil(20000) { store.history().any { it.role == "assistant" && it.text.contains("réellement reçue") } }
        compose.onNodeWithText("Réponse réellement reçue du serveur de test.").assertIsDisplayed()
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertTrue(request.body.readUtf8().contains("Bonjour Jarvis"))
    }
    @Test fun localVolumeDoesNothingUntilTheUserApproves() {
        val manager = context.getSystemService(AudioManager::class.java)
        val original = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            compose.onNodeWithTag("command_input").performTextInput("Volume à 30 %")
            compose.onNodeWithTag("send_command").performClick()
            compose.onNodeWithTag("deny_action").assertIsDisplayed()
            assertEquals(original, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            compose.onNodeWithTag("deny_action").performClick()
            compose.waitUntil(10000) { !runtime.busy }
            assertEquals(original, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            compose.onNodeWithTag("command_input").performTextInput("Volume à 30 %")
            compose.onNodeWithTag("send_command").performClick()
            compose.onNodeWithTag("confirm_action").performClick()
            compose.waitUntil(10000) { !runtime.busy }
            assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 30 / 100, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(0, server.requestCount)
        } finally { manager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
    }
    @Test fun privateModeHasNoFallbackToTheCloudProfile() {
        compose.runOnIdle {
            val privateProfile = connection("ollama").copy(id = "private")
            val remote = connection("mistral").copy(id = "cloud")
            store.save(privateProfile); store.save(remote)
            store.select(Roles.PRIVATE, privateProfile.id); store.select(Roles.BRAIN, remote.id); store.setFallback(listOf(remote.id))
        }
        assertEquals(listOf("private"), runtime.chooseProfiles("test", "private").map { it.id })
    }
    @Test fun androidSystemTtsSynthesizesARealWavUsingTheChosenProvider() {
        val pcm = ByteArray(4800)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
            .put("audio_data", Base64.getEncoder().encodeToString(WaveCodec.encode(pcm, 24000))).toString()))
        compose.runOnIdle {
            val voice = connection("voxtral-tts"); store.save(voice); store.select(Roles.TTS, voice.id); store.setFlag("system_tts", true)
        }
        val initialized = CountDownLatch(1); val ready = AtomicBoolean(false); val engineRef = AtomicReference<TextToSpeech>()
        compose.runOnIdle { engineRef.set(TextToSpeech(context, { status -> ready.set(status == TextToSpeech.SUCCESS); initialized.countDown() }, context.packageName)) }
        assertTrue("TTS initialization", initialized.await(20, TimeUnit.SECONDS)); assertTrue(ready.get())
        val engine = engineRef.get(); val done = CountDownLatch(1); val failed = AtomicBoolean(false)
        val file = File(context.cacheDir, "system-tts-fixture.wav")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { }
            override fun onDone(utteranceId: String?) { done.countDown() }
            @Deprecated("Android callback") override fun onError(utteranceId: String?) { failed.set(true); done.countDown() }
        })
        try {
            assertEquals(TextToSpeech.SUCCESS, engine.synthesizeToFile("Bonjour depuis la voix système.", Bundle(), file, "system-test"))
            assertTrue("TTS completion", done.await(20, TimeUnit.SECONDS)); assertFalse("TTS synthesis failure", failed.get())
            val decoded = WaveCodec.decode(file.readBytes())
            assertEquals(24000, decoded.sampleRate); assertArrayEquals(pcm, decoded.samples)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bonjour depuis la voix système.", JSONObject(request.body.readUtf8()).getString("input"))
        } finally { engine.shutdown(); file.delete(); compose.runOnIdle { store.setFlag("system_tts", false) } }
    }
}
