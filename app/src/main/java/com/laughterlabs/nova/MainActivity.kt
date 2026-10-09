package com.laughterlabs.nova

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Nova's face. Works like Gemini:
 *  - "Hey Nova" from anywhere (WakeWordService), or long-press power (default assistant)
 *  - it listens straight away, answers, and keeps listening until you stop talking or say "bye".
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var input: EditText
    private lateinit var controlBtn: Button
    private lateinit var wakeBtn: Button
    private lateinit var assistantBtn: Button

    private lateinit var engine: CommandEngine
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var utteranceId = 0

    /** True while we're in a back-and-forth conversation (Nova re-listens after each answer). */
    private var inConversation = false
    private var endAfterReply = false
    private var foreground = false
    private var pendingListen = false

    private val bye = Regex("^(stop|bye|goodbye|good bye|that's all|thats all|that is all|nothing|no thanks|cancel|exit|close)\\b.*")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        engine = CommandEngine(
            ctx = this,
            say = { text -> runOnUiThread { speak(text) } },
            hideNova = { runOnUiThread { moveTaskToBack(true) } },
        )

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts.language = Locale("en", "IN")
                tts.setSpeechRate(1.1f)
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { runOnUiThread { afterReply() } }
                    override fun onDone(id: String?) { runOnUiThread { afterReply() } }
                })
                if (pendingListen) { pendingListen = false; startListening() }
            }
        }

        askPermissions()
        NovaNotification.show(this)
        if (Prefs.wakeEnabled(this) && hasMic()) WakeWordService.start(this)
        addLog("Nova", "Online. Say \"Hey Nova\", tap the mic, or type. Say \"help\" for what I can do.")
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refreshButtons()
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!listening) endConversation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val wantsListen = i?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND
        if (!wantsListen) return
        i?.removeExtra(EXTRA_LISTEN)
        inConversation = true
        // Greet like a real assistant, then listen. If the voice isn't ready yet, just listen.
        if (ttsReady) mic.postDelayed({ startListening() }, 200) else pendingListen = true
    }

    // ---------------- Conversation flow ----------------

    /** Called when Nova finishes speaking. Decides whether to keep listening (like Gemini Live). */
    private fun afterReply() {
        when {
            engine.awaitingConfirm -> startListening()
            endAfterReply -> { endAfterReply = false; endConversation() }
            inConversation && foreground -> startListening()
            else -> endConversation()
        }
    }

    private fun endConversation() {
        inConversation = false
        WakeWordService.conversationActive = false
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        status.text = if (WakeWordService.running) "Say \"Hey Nova\" or tap the mic." else "Tap the mic and speak."
    }

    // ---------------- Voice ----------------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Use Google's speech engine explicitly. When Nova is the default assistant, Android points
     * the "default recognizer" at Nova itself, so we must pick the real one by name.
     */
    private fun recognizerComponent(): ComponentName? {
        val all = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
        return all.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: all.firstOrNull { it.packageName.startsWith("com.google") }
            ?: all.firstOrNull()
    }

    private fun startListening() {
        if (listening) return
        if (!hasMic()) { askPermissions(); return }
        val comp = recognizerComponent()
        if (comp == null && !SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition isn't available. Install or update the Google app.")
            return
        }
        if (ttsReady) tts.stop()
        inConversation = true
        // Free the microphone from the wake-word listener first.
        WakeWordService.conversationActive = true
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)

        val rec = recognizer ?: (if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp)
        else SpeechRecognizer.createSpeechRecognizer(this)).also { recognizer = it }
        rec.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        setMicActive(true)
        status.text = "Listening…"
        mic.postDelayed({ if (listening) rec.startListening(intent) }, if (WakeWordService.running) 250L else 0L)
    }

    private fun stopListening() {
        recognizer?.cancel()
        listening = false
        setMicActive(false)
        endConversation()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 40f)
            mic.scaleX = s; mic.scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "Thinking…" }
        override fun onError(error: Int) {
            listening = false
            setMicActive(false)
            when (error) {
                // Silence = conversation over (Gemini does the same).
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> endConversation()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> mic.postDelayed({ startListening() }, 400)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    endConversation(); status.text = "Network issue. Try again."
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    endConversation(); status.text = "Microphone permission needed."
                }
                else -> endConversation()
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            setMicActive(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) { endConversation(); return }
            onUserText(text)
        }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { status.text = "“$it”" }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun onUserText(text: String) {
        addLog("You", text)
        status.text = "Thinking…"
        if (!engine.awaitingConfirm && bye.matches(text.lowercase(Locale.ROOT).trim())) {
            endAfterReply = true
            speak("Okay, I'm here if you need me.")
            return
        }
        engine.handle(text)
    }

    private fun speak(text: String) {
        addLog("Nova", text)
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${utteranceId++}")
        else afterReply()
    }

    // ---------------- UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val cyan = Color.parseColor("#22D3EE")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05080F"))
            setPadding(dp(16), dp(36), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "N O V A"
            setTextColor(cyan)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "Tap the mic and speak."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        mic = Button(this).apply {
            text = "🎙"
            textSize = 34f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0B1726"))
                setStroke(dp(3), cyan)
            }
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(104), dp(104)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10); bottomMargin = dp(10)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "Type a command…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { sendTyped() } })
        root.addView(row)

        val half = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wakeBtn = Button(this).apply { setOnClickListener { toggleWake() } }
        assistantBtn = Button(this).apply { setOnClickListener { openAssistantSettings() } }
        row1.addView(wakeBtn, half())
        row1.addView(assistantBtn, half())
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        row2.addView(controlBtn, half())
        row2.addView(Button(this).apply { text = "AI brain"; setOnClickListener { showAiKeyDialog() } }, half())
        root.addView(row2)

        setContentView(root)
    }

    private fun refreshButtons() {
        controlBtn.text = if (NovaAccessibilityService.instance != null) "Phone control: ON" else "Turn on phone control"
        wakeBtn.text = if (Prefs.wakeEnabled(this)) "\"Hey Nova\": ON" else "\"Hey Nova\": OFF"
        assistantBtn.text = if (isDefaultAssistant()) "Default assistant ✓" else "Make default assistant"
    }

    private fun toggleWake() {
        val on = !Prefs.wakeEnabled(this)
        if (on && !hasMic()) { askPermissions(); return }
        Prefs.setWakeEnabled(this, on)
        if (on) {
            WakeWordService.start(this)
            addLog("Nova", "Hands-free on. First time, I download a small offline voice model (about 36 MB), then just say \"Hey Nova\" from anywhere.")
        } else {
            WakeWordService.stop(this)
            addLog("Nova", "Hands-free off.")
        }
        refreshButtons()
    }

    private fun isDefaultAssistant(): Boolean {
        val cur = Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: return false
        return cur.startsWith("$packageName/")
    }

    private fun openAssistantSettings() {
        addLog("Nova", "In the screen that opens choose: Default digital assistant app → Nova. Then long-press the power button (or swipe up from a bottom corner) to call me from any app.")
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); onUserText(t) }
    }

    private fun setMicActive(on: Boolean) {
        (mic.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) "#0E7490" else "#0B1726"))
        if (!on) { mic.scaleX = 1f; mic.scaleY = 1f }
    }

    private fun addLog(who: String, text: String) {
        val prefix = if (logView.text.isEmpty()) "" else "\n\n"
        logView.append("$prefix$who: $text")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showAiKeyDialog() {
        val box = EditText(this).apply {
            hint = "Paste free Gemini API key"
            setText(Prefs.aiKey(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("AI brain (optional)")
            .setMessage("Commands work offline with no key. For open questions and coding help, paste a free key from aistudio.google.com.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Prefs.setAiKey(this, box.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        NovaNotification.show(this)
        if (Prefs.wakeEnabled(this) && hasMic()) WakeWordService.start(this)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
    }
}
