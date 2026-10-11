package com.laughterlabs.nova

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import java.util.Locale

data class Contact(val name: String, val number: String)

/** Every concrete thing Boba can do on the phone. Each returns the sentence Boba speaks. */
object Actions {

    private fun Context.go(i: Intent) = startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun Context.has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- apps

    fun openApp(ctx: Context, spoken: String): String {
        val q = norm(spoken)
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 0.0
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val label = ri.loadLabel(pm).toString()
            val s = score(q, norm(label))
            if (s > bestScore) { bestScore = s; bestPkg = ri.activityInfo.packageName; bestLabel = label }
        }
        if (bestPkg == null || bestScore < 0.6) throw NovaException("I couldn't find an app called $spoken.")
        val i = pm.getLaunchIntentForPackage(bestPkg) ?: throw NovaException("$bestLabel can't be opened.")
        ctx.go(i)
        return "Opening $bestLabel."
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** 1.0 = exact; tolerant to speech mistakes like "what's app" vs "WhatsApp". */
    private fun score(q: String, label: String): Double {
        if (q.isEmpty() || label.isEmpty()) return 0.0
        if (q == label) return 1.0
        if (label.startsWith(q) || q.startsWith(label)) return 0.9
        if (label.contains(q)) return 0.8
        val d = levenshtein(q, label)
        return 1.0 - d.toDouble() / maxOf(q.length, label.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    // ---------------------------------------------------------------- contacts

    fun findContact(ctx: Context, spoken: String): Contact? {
        val name = spoken.trim()
        val digits = name.filter { it.isDigit() }
        if (digits.length >= 5 && digits.length >= name.count { !it.isWhitespace() && it != '+' } - 1) {
            return Contact(digits, digits)
        }
        if (!ctx.has(Manifest.permission.READ_CONTACTS)) return null
        val found = mutableListOf<Contact>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)
                val num = c.getString(1)
                if (n != null && num != null) found += Contact(n, num)
            }
        }
        if (found.isEmpty()) return null
        val n = name.lowercase(Locale.ROOT)
        return found.firstOrNull { it.name.lowercase(Locale.ROOT) == n }
            ?: found.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(n) }
            ?: found.first()
    }

    fun resolveContact(ctx: Context, spoken: String): Contact {
        findContact(ctx, spoken)?.let { return it }
        if (!ctx.has(Manifest.permission.READ_CONTACTS)) throw NovaException("I need contacts permission. Reopen Boba and allow it.")
        throw NovaException("I couldn't find $spoken in your contacts.")
    }

    // ---------------------------------------------------------------- calls & messages

    fun call(ctx: Context, c: Contact): String {
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        return if (ctx.has(Manifest.permission.CALL_PHONE)) {
            ctx.go(Intent(Intent.ACTION_CALL, uri)); "Calling ${c.name}."
        } else {
            ctx.go(Intent(Intent.ACTION_DIAL, uri)); "Allow phone permission so I can call directly. Dialer is open for ${c.name}."
        }
    }

    fun sendSms(ctx: Context, c: Contact, msg: String): String {
        if (!ctx.has(Manifest.permission.SEND_SMS)) throw NovaException("I need SMS permission. Reopen Boba and allow it.")
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(msg), null, null)
        return "Text sent to ${c.name}."
    }

    /** Indian numbers: 98765 43210 -> 919876543210 */
    private fun waNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }

    fun sendWhatsApp(ctx: Context, c: Contact, msg: String): String {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(ctx, it) }
            ?: throw NovaException("WhatsApp isn't installed.")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${waNumber(c.number)}&text=${Uri.encode(msg)}")
        val autoSend = NovaAccessibilityService.instance != null
        if (autoSend) NovaAccessibilityService.armWhatsAppSend()
        ctx.go(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
        return if (autoSend) "Sending on WhatsApp to ${c.name}."
        else "WhatsApp is open with your message, tap send. Turn on phone control and I'll press send for you."
    }

    // ---------------------------------------------------------------- web & media

    fun webSearch(ctx: Context, q: String): String {
        try {
            ctx.go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        } catch (e: ActivityNotFoundException) {
            ctx.go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        return "Searching for $q."
    }

    fun play(ctx: Context, what: String, where: String): String {
        val pkg = when (where) {
            "spotify" -> "com.spotify.music"
            "youtube music" -> "com.google.android.apps.youtube.music"
            else -> "com.google.android.youtube"
        }
        // "Play from search" makes YouTube/Spotify start playing the top result directly.
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, what)
            .setPackage(pkg)
        try {
            ctx.go(playIntent)
        } catch (e: ActivityNotFoundException) {
            ctx.go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(what))))
        }
        return "Playing $what."
    }

    fun navigate(ctx: Context, place: String): String {
        try {
            ctx.go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))))
        } catch (e: ActivityNotFoundException) {
            ctx.go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        }
        return "Starting directions to $place."
    }

    // ---------------------------------------------------------------- clock

    fun alarm(ctx: Context, t: String): String {
        val m = Regex("(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)?").find(t)
        if (m == null) {
            ctx.go(Intent(AlarmClock.ACTION_SHOW_ALARMS)); return "Here are your alarms. Say a time, like set alarm for 6:30 am."
        }
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].replace(Regex("[^ap]"), "")
        val pm = ampm == "p" || (ampm.isEmpty() && Regex("evening|night|afternoon|tonight").containsMatchIn(t))
        if (pm && h < 12) h += 12
        if (ampm == "a" && h == 12) h = 0
        if (h > 23 || min > 59) throw NovaException("That time doesn't look right.")
        ctx.go(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Boba")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "Alarm set for $h12:${"%02d".format(min)} ${if (h < 12) "AM" else "PM"}."
    }

    fun timer(ctx: Context, t: String): String {
        var secs = 0
        Regex("(\\d+)\\s*(hour|hr|minute|min|second|sec)").findAll(t).forEach {
            val n = it.groupValues[1].toInt()
            secs += when (it.groupValues[2]) { "hour", "hr" -> n * 3600; "minute", "min" -> n * 60; else -> n }
        }
        if (secs == 0) throw NovaException("For how long? Say: set timer for 5 minutes.")
        ctx.go(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Boba")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer started."
    }

    // ---------------------------------------------------------------- hardware

    fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw NovaException("This phone has no flashlight I can use.")
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    fun volume(ctx: Context, t: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val ui = AudioManager.FLAG_SHOW_UI
        Regex("(\\d{1,3})").find(t)?.let {
            val pct = it.value.toInt().coerceIn(0, 100)
            am.setStreamVolume(s, max * pct / 100, ui); return "Volume $pct percent."
        }
        return when {
            "unmute" in t -> { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, ui); "Unmuted." }
            "mute" in t || "silent" in t -> { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, ui); "Muted." }
            Regex("max|full|highest").containsMatchIn(t) -> { am.setStreamVolume(s, max, ui); "Volume at maximum." }
            Regex("down|lower|decrease|reduce|less").containsMatchIn(t) -> {
                repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui) }; "Volume down."
            }
            else -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui) }; "Volume up." }
        }
    }

    fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $pct percent" + if (bm.isCharging) " and charging." else "."
    }

    /** Android 10+ doesn't let apps flip Wi-Fi directly, so Boba pops the system toggle panel. */
    fun panel(ctx: Context, which: String): String {
        val i = when (which) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            else -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        ctx.go(i)
        return "Here's the $which switch."
    }

    fun openAccessibilitySettings(ctx: Context) = ctx.go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
