package com.openjarvis.murena

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.openjarvis.ui.MainActivity

/** Android settings uses these activities to validate and configure a TTS engine. */
class VoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val ready = try {
                    val store = ProfileStore.get(this)
                    val voice = store.chosen(Roles.TTS)
                    store.flag("system_tts") && voice != null &&
                        Providers.info(voice.kind).capability == "tts" && voice.model.isNotBlank() && voice.voice.isNotBlank()
                } catch (_: Exception) { false }
                val languages = arrayListOf("fra-FRA", "eng-USA")
                val data = Intent()
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, if (ready) languages else arrayListOf())
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, if (ready) arrayListOf() else languages)
                setResult(if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, data)
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                val language = intent.getStringExtra("language").orEmpty()
                val sample = if (language in setOf("en", "eng")) "Hello. This is a sample of the selected Jarvis voice."
                    else "Bonjour. Voici un exemple de la voix choisie pour Jarvis."
                setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample))
            }
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> {
                // Opening configuration never enables network synthesis or changes a profile itself.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                setResult(RESULT_CANCELED)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
