package io.github.priencelucifer.michisonae

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal class DriverWarningPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private val tone = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    }.getOrNull()
    private val textToSpeech = TextToSpeech(applicationContext, this)
    private var ready = false
    private var pendingMessage: String? = null

    init {
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }

                @Deprecated("Required by Android's TTS callback")
                override fun onError(utteranceId: String?) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
            },
        )
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS &&
            textToSpeech.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE
        if (ready) {
            pendingMessage?.let(::speak)
            pendingMessage = null
        }
    }

    fun warn(message: String) {
        vibrate()
        if (ready) {
            speak(message)
        } else {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            pendingMessage = message
        }
    }

    fun close() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        textToSpeech.stop()
        textToSpeech.shutdown()
        tone?.release()
    }

    private fun speak(message: String) {
        audioManager.requestAudioFocus(focusRequest)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        val result = textToSpeech.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UTTERANCE_ID,
        )
        if (result == TextToSpeech.ERROR) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            applicationContext.getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 160, 100, 160), -1),
        )
    }

    private companion object {
        const val UTTERANCE_ID = "driver-warning"
    }
}
