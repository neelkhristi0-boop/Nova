package com.laughterlabs.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Hands-free "Hey Nova". Runs a tiny offline speech model (Vosk) in the background, so nothing
 * is sent anywhere until you say the wake word. Then it hands the mic to Nova's main screen.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_PAUSE = "com.laughterlabs.nova.PAUSE"
        const val ACTION_RESUME = "com.laughterlabs.nova.RESUME"
        private const val CHANNEL = "nova_wake"
        private const val NOTIF_ID = 2
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

        /** Wake words, including the ways the small model tends to hear "Nova". */
        private val WAKE = Regex("\\b(nova|no va|novah|noa|jarvis|jarvi|jervis)\\b")

        @Volatile var running = false
            private set
        /** Set by MainActivity while a conversation is going on, so we don't fight over the mic. */
        @Volatile var conversationActive = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        fun send(ctx: Context, action: String) {
            if (running) ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Nova listener", NotificationManager.IMPORTANCE_MIN))
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification("Starting…"))
            }
        } catch (e: Exception) {
            // Android refuses to start a microphone service from the background; open Nova once to restart it.
            stopSelf()
            return
        }
        running = true
        Thread { loadModel() }.start()
        main.postDelayed(watchdog, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> main.postDelayed({ if (!conversationActive) resumeListening() }, 400)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        pauseListening()
        model?.close()
        model = null
        super.onDestroy()
    }

    /** If Nova was triggered but never came back (e.g. screen was locked), start listening again. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (paused && !conversationActive && System.currentTimeMillis() - lastTrigger > 20_000) resumeListening()
            main.postDelayed(this, 20_000)
        }
    }

    // ---------------------------------------------------------------- model

    private fun loadModel() {
        try {
            val dir = File(filesDir, "vosk-model")
            if (!File(dir, "am").exists()) download(dir)
            model = Model(dir.absolutePath)
            main.post { if (!conversationActive) resumeListening() else paused = true }
        } catch (e: Exception) {
            update("Couldn't load the voice model: ${e.message}. Check internet and toggle Hey Nova again.")
        }
    }

    private fun download(target: File) {
        update("Downloading offline voice model (one time)…")
        val tmp = File(filesDir, "vosk-tmp").apply { deleteRecursively(); mkdirs() }
        var url = URL(MODEL_URL)
        var conn: HttpURLConnection
        var hops = 0
        // Follow redirects manually (HttpURLConnection won't hop between http and https).
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code !in 200..299) throw Exception("download failed ($code)")
            break
        }
        val total = conn.contentLengthLong
        var read = 0L
        var lastPct = -1
        ZipInputStream(CountingStream(conn.inputStream) { n ->
            read += n
            if (total > 0) {
                val pct = (read * 100 / total).toInt()
                if (pct != lastPct && pct % 5 == 0) { lastPct = pct; update("Downloading voice model… $pct%") }
            }
        }).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // Strip the top folder ("vosk-model-small-en-in-0.4/...").
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val out = File(tmp, rel)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        conn.disconnect()
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw Exception("could not save model")
    }

    private class CountingStream(private val inner: java.io.InputStream, private val onRead: (Int) -> Unit) :
        java.io.InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) onRead(it) }
        override fun close() = inner.close()
    }

    // ---------------------------------------------------------------- listening

    private fun resumeListening() {
        val m = model ?: return
        paused = false
        if (speech != null) return
        try {
            val rec = Recognizer(m, 16000f)
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            update("Say \"Hey Nova\"")
        } catch (e: Exception) {
            speech = null
            update("Mic busy, retrying…")
            main.postDelayed({ if (!conversationActive) resumeListening() }, 3_000)
        }
    }

    private fun pauseListening() {
        paused = true
        speech?.let { it.stop(); it.shutdown() }
        speech = null
    }

    private fun check(json: String?) {
        if (json.isNullOrBlank() || paused) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val text = o.optString("partial").ifEmpty { o.optString("text") }
        if (WAKE.containsMatchIn(text)) trigger()
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 2_000) return
        lastTrigger = now
        pauseListening()
        conversationActive = true

        // Best: open as the system assistant (allowed from the background).
        NovaVoiceInteractionService.instance?.let {
            it.showSession(Bundle(), 0); return
        }
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The phone-control service is allowed to open screens from the background too.
        val launcher: Context = NovaAccessibilityService.instance ?: this
        try {
            launcher.startActivity(i)
        } catch (e: Exception) {
            conversationActive = false
            update("Turn on phone control or make Nova the default assistant so I can pop up from anywhere.")
        }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis)
    override fun onResult(hypothesis: String?) = check(hypothesis)
    override fun onFinalResult(hypothesis: String?) = check(hypothesis)
    override fun onError(exception: Exception?) {
        pauseListening()
        main.postDelayed({ if (!conversationActive) resumeListening() }, 3_000)
    }
    override fun onTimeout() {
        pauseListening()
        if (!conversationActive) resumeListening()
    }

    // ---------------------------------------------------------------- notification

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 8,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nova")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
