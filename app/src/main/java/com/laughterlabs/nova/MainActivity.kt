package com.laughterlabs.nova

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
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
 * Nova's face: a mic button, a chat log, and a text box.
 * Speech -> CommandEngine -> spoken reply.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var input: EditText
    private lateinit var controlBtn: Button

    private lateinit var engine: CommandEngine
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var utteranceId = 0

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
                    override fun onError(id: String?) {}
                    override fun onDone(id: String?) {
                        // If Nova just asked "say yes to confirm", listen for the answer automatically.
                        if (engine.awaitingConfirm) runOnUiThread { startListening() }
                    }
                })
            }
        }

        askPermissions()
        NovaNotification.show(this)
        addLog("Nova", "Online. Tap the mic or type. Say \"help\" for what I can do.")
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        controlBtn.text = if (NovaAccessibilityService.instance != null) "Phone control: ON" else "Turn on phone control"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val wantsListen = i?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND
        if (wantsListen) mic.postDelayed({ startListening() }, 250)
    }

    // ---------------- Voice ----------------

    private fun startListening() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition isn't available. Install or update the Google app.")
            return
        }
        if (ttsReady) tts.stop()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
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
        rec.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        setMicActive(false)
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
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network issue. Try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                else -> "Tap the mic and speak."
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            setMicActive(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) { status.text = "Didn't catch that."; return }
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
        status.text = "Tap the mic and speak."
        engine.handle(text)
    }

    private fun speak(text: String) {
        addLog("Nova", text)
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${utteranceId++}")
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

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        tools.addView(controlBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(Button(this).apply {
            text = "AI brain"
            setOnClickListener { showAiKeyDialog() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tools)

        setContentView(root)
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
