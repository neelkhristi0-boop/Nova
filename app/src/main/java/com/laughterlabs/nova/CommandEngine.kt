package com.laughterlabs.nova

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovaException(message: String) : Exception(message)

/**
 * Turns a spoken sentence into an action. Everything here runs offline and instantly;
 * only unknown questions go to the optional AI brain.
 */
class CommandEngine(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val hideNova: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain(ctx)

    /** An action waiting for "yes" (used for messages so a mis-heard word never sends the wrong text). */
    private var pending: (() -> String)? = null
    val awaitingConfirm get() = pending != null

    private val wake = Regex("^(?:(?:hey|hi|ok|okay)\\s+)?(?:boba|bobba|booba|bubba|baba|bobo|bob a|jarvis)[\\s,!.]*")
    private val yes = Regex("^(yes|yeah|yep|yup|haan|ha|han|send|send it|confirm|do it|ok|okay|sure|go ahead)\\b.*")

    fun handle(raw: String) {
        val t = raw.lowercase(Locale.ROOT).trim().replace(wake, "").trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) { say("Yes? I'm listening."); return }

        pending?.let { action ->
            pending = null
            say(if (yes.matches(t)) safe(action) else "Okay, cancelled.")
            return
        }

        val reply = safe { route(t) }
        if (reply != null) say(reply) else brain.ask(raw.trim(), say)
    }

    private fun <T> safe(block: () -> T): T = try {
        block()
    } catch (e: NovaException) {
        @Suppress("UNCHECKED_CAST")
        (e.message ?: "Something went wrong.") as T
    } catch (e: Exception) {
        @Suppress("UNCHECKED_CAST")
        ("Sorry, that failed: ${e.message}") as T
    }

    private fun m(t: String, re: String): List<String>? = Regex(re).find(t)?.groupValues

    // ---------------------------------------------------------------- routing

    private fun route(t: String): String? {
        // ---- small talk & info
        if (t.matches(Regex("help|what can you do.*|commands|show commands"))) return HELP
        if (t.matches(Regex("(hi|hello|hey)( there)?"))) return "Hello! What do you need?"
        if (t.matches(Regex("(thanks|thank you|thank you so much|great|nice|good job).*"))) return "Anytime."
        if (t.matches(Regex("who are you|what('s| is) your name|introduce yourself"))) return "I'm Boba, your personal assistant. I can open apps, call, message, search, and control your phone."
        if (t.matches(Regex("(stop|shut up|be quiet|cancel)"))) return "Okay."
        if (Regex("\\b(what|tell)\\b.*\\btime\\b|^time( now)?$").containsMatchIn(t))
            return "It's " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()) + "."
        if (Regex("\\b(what|tell)\\b.*\\b(date|day)\\b|today'?s date").containsMatchIn(t))
            return "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date()) + "."
        if ("battery" in t) return Actions.battery(ctx)

        // ---- messages: "message rahul saying I'm on the way", "whatsapp mom hi", "sms papa call me", "tell priya that I'll be late"
        m(t, "^(?:send |write )?(?:a |an )?(whatsapp|message|msg|text|sms)(?: message)?(?: to)? (.+)$")?.let { g ->
            val channel = if (g[1] == "sms" || g[1] == "text") Channel.SMS else Channel.WHATSAPP
            val (name, msg) = splitNameAndMessage(g[2])
            return prepareMessage(name, msg, channel)
        }
        m(t, "^tell (.+?) (?:that|to) (.+)$")?.let { g -> return prepareMessage(g[1], g[2], Channel.WHATSAPP) }

        // ---- calls: "call mom", "call 98765 43210"
        m(t, "^(?:make a )?(?:call|phone|dial|ring)(?: to)? (.+?)(?: now| please| on (?:mobile|phone))?$")?.let { g ->
            return Actions.call(ctx, Actions.resolveContact(ctx, g[1]))
        }

        // ---- media & web
        m(t, "^play (.+?) on (youtube|spotify|youtube music)$")?.let { g -> return Actions.play(ctx, g[1], g[2]) }
        m(t, "^(?:play|youtube|search youtube for) (.+)$")?.let { g -> return Actions.play(ctx, g[1], "youtube") }
        m(t, "^(?:navigate|directions|take me|drive|route) (?:me )?(?:to )?(.+)$")?.let { g -> return Actions.navigate(ctx, g[1]) }
        m(t, "^(?:search|google|look up|find)(?: on google)?(?: for)? (.+)$")?.let { g -> return Actions.webSearch(ctx, g[1]) }

        // ---- phone controls
        if ("alarm" in t || t.startsWith("wake me")) return Actions.alarm(ctx, t)
        if ("timer" in t) return Actions.timer(ctx, t)
        if (Regex("flash ?light|torch").containsMatchIn(t)) return Actions.torch(ctx, !Regex("\\boff\\b").containsMatchIn(t))
        if ("volume" in t || t.matches(Regex("(un)?mute( (the )?(phone|sound))?|silent mode"))) return Actions.volume(ctx, t)
        if (Regex("\\b(wi-?fi|wifi)\\b").containsMatchIn(t)) return Actions.panel(ctx, "wifi")
        if ("bluetooth" in t) return Actions.panel(ctx, "bluetooth")
        if (Regex("mobile data|internet|hotspot").containsMatchIn(t)) return Actions.panel(ctx, "internet")

        // ---- full-phone control (needs the accessibility switch)
        if (t.matches(Regex("(go )?home|home screen|minimi[sz]e"))) return screen("Going home.", hide = false) {
            it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); null
        }
        if (t.matches(Regex("(go )?back|previous screen"))) return screen("Going back.") {
            it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); null
        }
        if (Regex("^(show |open )?recent( apps)?s?$").matches(t)) return screen("Recent apps.") {
            it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); null
        }
        if (Regex("^(show |open |pull down )?(my )?notifications?$").matches(t)) return screen("Here are your notifications.") {
            it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); null
        }
        if ("quick settings" in t) return screen("Quick settings.") {
            it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); null
        }
        if (Regex("lock (the |my )?(screen|phone)").containsMatchIn(t)) {
            if (Build.VERSION.SDK_INT < 28) throw NovaException("Locking needs Android 9 or newer.")
            return screen("Locking.") { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); null }
        }
        if (Regex("screen ?shot").containsMatchIn(t)) {
            if (Build.VERSION.SDK_INT < 28) throw NovaException("Screenshots need Android 9 or newer.")
            return screen("Taking a screenshot.") { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); null }
        }
        m(t, "^scroll (up|down)$")?.let { g -> return screen("Scrolling ${g[1]}.") { it.scroll(g[1] == "down"); null } }
        m(t, "^(?:click|tap|press|select)(?: on)? (.+)$")?.let { g ->
            return screen("Tapping ${g[1]}.") { if (it.clickText(g[1])) null else "I couldn't find \"${g[1]}\" on the screen." }
        }
        m(t, "^type (.+)$")?.let { g ->
            return screen("Typing.") { if (it.typeText(g[1])) null else "There's no text box selected. Tap one first." }
        }
        if (Regex("read (the |my )?screen|what('s| is) on (the |my )?screen").containsMatchIn(t)) {
            return screen("Reading.") { it.screenText().ifBlank { "The screen looks empty." } }
        }

        // ---- open any app: "open whatsapp", "launch camera", "start instagram"
        m(t, "^(?:open|launch|start|run|show) (?:the |my )?(.+?)(?: app| application)?$")?.let { g -> return Actions.openApp(ctx, g[1]) }

        return null // unknown -> AI brain
    }

    // ---------------------------------------------------------------- helpers

    enum class Channel { WHATSAPP, SMS }

    /** "rahul sharma saying hello" -> ("rahul sharma", "hello"); "mom hi there" -> ("mom", "hi there") */
    private fun splitNameAndMessage(rest: String): Pair<String, String> {
        Regex("^(.+?) (?:saying|that|says|say|message|:)\\s*(.+)$").find(rest)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        val words = rest.split(" ").filter { it.isNotBlank() }
        if (words.size < 2) throw NovaException("What should the message say? Try: message ${rest} saying hello.")
        for (k in minOf(3, words.size - 1) downTo 1) {
            val name = words.take(k).joinToString(" ")
            if (Actions.findContact(ctx, name) != null) return name to words.drop(k).joinToString(" ")
        }
        return words[0] to words.drop(1).joinToString(" ")
    }

    private fun prepareMessage(name: String, msg: String, channel: Channel): String {
        val contact = Actions.resolveContact(ctx, name)
        val app = if (channel == Channel.SMS) "a text" else "a WhatsApp"
        pending = {
            if (channel == Channel.SMS) Actions.sendSms(ctx, contact, msg) else Actions.sendWhatsApp(ctx, contact, msg)
        }
        return "Send $app to ${contact.name}: \"$msg\"? Say yes to confirm."
    }

    /**
     * Run a screen action through the accessibility service. Boba first steps aside
     * so the action lands on the app you were using, not on Boba itself.
     * The block returns null on success, or a sentence to speak.
     */
    private fun screen(reply: String, hide: Boolean = true, block: (NovaAccessibilityService) -> String?): String {
        val svc = NovaAccessibilityService.instance
            ?: run {
                Actions.openAccessibilitySettings(ctx)
                throw NovaException("I need phone control for that. In the list that just opened, tap Boba and switch it on.")
            }
        if (hide) hideNova()
        main.postDelayed({
            val result = try { block(svc) } catch (e: Exception) { "That didn't work: ${e.message}" }
            if (result != null) say(result)
        }, if (hide) 700L else 0L)
        return reply
    }

    companion object {
        const val HELP = """Here's what I can do:
• open WhatsApp / camera / any app
• call Mom · call 98765 43210
• message Rahul saying I'm on the way (WhatsApp)
• text Papa saying call me (SMS)
• play Arijit songs · play lo-fi on Spotify
• search CNC machining companies in Vadodara
• navigate to Makarpura GIDC
• set alarm for 6:30 am · set timer for 10 minutes
• flashlight on/off · volume up/down/50 · mute
• wifi · bluetooth · mobile data
• go home · go back · recent apps · notifications
• scroll down · tap Send · type hello · read the screen
• take a screenshot · lock the phone
• battery · time · date
Anything else goes to the AI brain (add a free key with the AI brain button)."""
    }
}
