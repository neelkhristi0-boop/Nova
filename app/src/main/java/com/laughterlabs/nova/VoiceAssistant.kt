package com.laughterlabs.nova

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Makes Nova selectable as the phone's "Default digital assistant", like Gemini.
 * Then long-pressing power (or swiping up from a bottom corner) opens Nova over any app.
 */
class NovaVoiceInteractionService : VoiceInteractionService() {
    companion object {
        @Volatile var instance: NovaVoiceInteractionService? = null
            private set
    }

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }
}

class NovaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NovaSession(this)
}

class NovaSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startAssistantActivity(i)
        hide()
    }
}

/**
 * Android requires every assistant to declare a speech recognizer. Nova actually uses Google's
 * recognizer (picked by name in MainActivity), so this one just politely declines.
 */
class NovaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
