package app.convoyage.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.convoyage.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Something that can say a sentence. The navigation controller only needs this, which keeps it testable. */
fun interface Speaker {
    fun speak(text: String)
}

/** A text-to-speech voice the person can pick in Settings. */
data class VoiceOption(val name: String, val label: String)

/**
 * Turn-by-turn speech with Android's TextToSpeech. English (Australia) when the engine has it, else the engine's
 * default. Music and podcasts are ducked (not stopped) while a prompt plays. Voice on/off, rate and the chosen voice
 * come from [SessionStore], so they apply to the very next prompt. The engine starts the first time it is needed.
 */
class NavVoice(context: Context, private val session: SessionStore) : Speaker {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayList<String>()
    private var focus: AudioFocusRequest? = null
    private var speaking = 0

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())

    /** English voices installed on this phone (empty until the engine has started). */
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    /** Starts the engine now so the first prompt is not late. Safe to call repeatedly. */
    fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) return@TextToSpeech
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            val au = Locale("en", "AU")
            val result = engine.isLanguageAvailable(au)
            if (result >= TextToSpeech.LANG_AVAILABLE) engine.language = au
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = spoken()
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) = spoken()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = spoken()
            })
            _voices.value = runCatching {
                engine.voices.orEmpty()
                    .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                    .sortedWith(compareBy({ it.locale.toString() != "en_AU" }, { it.locale.toString() }, { it.name }))
                    .mapIndexed { i, v -> VoiceOption(v.name, "${v.locale.getDisplayName(Locale.ENGLISH)} ${i + 1}") }
            }.getOrDefault(emptyList())
            ready = true
            queued.forEach { say(it) }
            queued.clear()
        }
    }

    override fun speak(text: String) {
        if (!session.navVoice.value) return
        prepare()
        if (!ready) {
            queued.clear()
            queued.add(text)
            return
        }
        say(text)
    }

    /** Plays [text] now even if guidance is muted (the Settings "Test voice" button). */
    fun test(text: String = "In 200 metres, turn right onto Mount Panorama Circuit") {
        prepare()
        if (!ready) {
            queued.clear()
            queued.add(text)
        } else {
            say(text)
        }
    }

    private fun say(text: String) {
        val engine = tts ?: return
        engine.setSpeechRate(session.navVoiceRate.value.coerceIn(0.5f, 2f))
        session.navVoiceName.value?.let { wanted -> engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it } }
        requestFocus()
        speaking++
        // QUEUE_FLUSH: a newer prompt replaces an older one that has not finished (we are past that turn).
        val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${System.nanoTime()}")
        if (rc != TextToSpeech.SUCCESS) spoken()
    }

    private fun spoken() {
        speaking = (speaking - 1).coerceAtLeast(0)
        if (speaking == 0) abandonFocus()
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        speaking = 0
        abandonFocus()
    }
}
