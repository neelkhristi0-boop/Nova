package com.laughterlabs.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Saved settings. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    fun aiKey(ctx: Context) = p(ctx).getString("ai_key", "") ?: ""
    fun setAiKey(ctx: Context, key: String) = p(ctx).edit().putString("ai_key", key).apply()
    fun wakeEnabled(ctx: Context) = p(ctx).getBoolean("wake", false)
    fun setWakeEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("wake", on).apply()
    fun aiModel(ctx: Context) = p(ctx).getString("ai_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
}

/**
 * Optional AI for open questions, explanations and code help.
 * No key = Boba still works fully for commands, and falls back to a Google search.
 */
class Brain(private val ctx: Context) {

    private val history = ArrayDeque<JSONObject>()

    private val persona = """You are Boba, a fast personal voice assistant on the user's Android phone.
Answer in 1-3 short spoken sentences unless asked for detail. No markdown, no emojis.
If asked for code, give it plainly and keep it short."""

    fun ask(question: String, reply: (String) -> Unit) {
        val key = Prefs.aiKey(ctx)
        if (key.isBlank()) {
            reply(Actions.webSearch(ctx, question) + " Add a free AI key with the AI brain button and I can answer things like this myself.")
            return
        }
        Thread {
            val answer = try { gemini(key, question) } catch (e: Exception) { "My AI brain didn't respond: ${e.message}" }
            reply(answer)
        }.start()
    }

    @Synchronized
    private fun gemini(key: String, question: String): String {
        history.addLast(turn("user", question))
        while (history.size > 12) history.removeFirst()

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona))))
            .put("contents", JSONArray(history.toList()))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Prefs.aiModel(ctx)}:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            history.removeLast()
            throw Exception("error $code")
        }
        val answer = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
        history.addLast(turn("model", answer))
        return answer
    }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
}
