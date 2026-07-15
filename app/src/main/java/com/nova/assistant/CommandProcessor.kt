package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a recognized phrase (Hindi OR English) into an action.
 * Some actions are simple Intents; the powerful ones (WhatsApp send,
 * auto-scroll, tap-by-text) go through the AccessibilityService.
 */
class CommandProcessor(private val context: Context) {

    data class Result(val spoken: String, val action: Intent? = null)

    fun process(raw: String): Result {
        val text = raw.trim().lowercase(Locale.getDefault())

        // ---- STOP (auto-scroll / loops) ----
        if (text.containsAny("stop", "ruk", "band karo", "bas karo")) {
            ActionService.instance?.stopAutoScroll()
            return Result("Okay, stopped")
        }

        // ---- WhatsApp: send message to a contact ----
        // e.g. "whatsapp pe paras ko hello bhej do"
        if (text.contains("whatsapp") && text.containsAny("bhej", "send", "message", "msg")) {
            val (name, msg) = extractContactAndMessage(text)
            if (name.isNotBlank()) {
                WhatsAppSender.send(context, name, msg)
                return Result("Sending WhatsApp message to $name")
            }
            return Result("Please tell me the contact name")
        }

        // ---- Auto-scroll reels/feed ----
        if (text.containsAny("scroll", "reel") && text.containsAny("karte raho", "keep", "har", "auto")) {
            val svc = ActionService.instance
            return if (svc != null) {
                svc.startAutoScroll(15000)
                Result("Scrolling. Say stop when you want me to stop.")
            } else Result("Please enable the accessibility service first")
        }

        // ---- Open an app by name (Instagram, Snapchat, anything) ----
        if (text.containsAny("open", "kholo", "khol do", "launch", "chalu karo")) {
            val name = text.removePrefixes(
                "open", "kholo", "khol do", "launch", "chalu karo"
            ).trim()
            val launch = findAppLaunchIntent(name)
            return if (launch != null) Result("Opening $name", launch)
            else Result("I could not find an app named $name")
        }

        // ---- Call ----
        if (text.containsAny("call", "phone karo", "call karo", "dial")) {
            val number = text.filter { it.isDigit() }
            if (number.length >= 5) {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return Result("Calling $number", i)
            }
            // Call a saved contact by name via WhatsApp/dialer search
            val name = text.removePrefixes("call", "phone karo", "call karo", "dial").trim()
            if (name.isNotBlank()) {
                WhatsAppSender.callContact(context, name)
                return Result("Calling $name")
            }
            return Result("Please say a name or number to call")
        }

        // ---- System buttons ----
        if (text.containsAny("go back", "peeche", "back")) {
            ActionService.instance?.pressBack(); return Result("Going back")
        }
        if (text.containsAny("home", "ghar")) {
            ActionService.instance?.pressHome(); return Result("Home")
        }

        // ---- Time / Date ----
        if (text.containsAny("time", "samay", "kitne baje", "kitna baja")) {
            val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return Result("The time is $now")
        }
        if (text.containsAny("date", "tareekh", "aaj kaunsi")) {
            val today = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            return Result("Today is $today")
        }

        // ---- YouTube ----
        if (text.containsAny("youtube", "video chala", "play video")) {
            val q = text.removePrefixes("youtube", "video chala", "play video", "play")
            val i = webIntent("https://www.youtube.com/results?search_query=" + enc(q))
            return Result("Opening YouTube", i)
        }

        // ---- Maps ----
        if (text.containsAny("map", "direction", "raasta", "location")) {
            val place = text.removePrefixes("map", "maps", "direction to", "raasta", "location")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(place)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Result("Opening maps for $place", i)
        }

        // ---- Explicit search ----
        if (text.containsAny("search", "google", "khojo", "dhundo")) {
            val q = text.removePrefixes("search for", "search", "google", "khojo", "dhundo")
            return Result("Searching for $q",
                webIntent("https://www.google.com/search?q=" + enc(q)))
        }

        // ---- Greetings ----
        if (text.containsAny("hello", "hi", "namaste", "hey")) {
            return Result("Hello. How can I help you?")
        }

        // ---- Fallback ----
        return Result("I did not understand that. Please try again.")
    }

    // Pull "<name> ko <message>" out of the phrase.
    private fun extractContactAndMessage(text: String): Pair<String, String> {
        // remove wrapper words
        var t = text.replace("whatsapp", " ")
            .replace("pe", " ").replace("par", " ").replace("pr", " ")
        val msgMarkers = listOf("bhej do", "bhejo", "bhej", "send", "message", "msg")
        var message = ""
        for (m in msgMarkers) {
            val idx = t.indexOf(m)
            if (idx >= 0) { t = t.substring(0, idx); break }
        }
        // "ka" often precedes the actual message word: "hello ka"
        val koIdx = t.indexOf(" ko ")
        var name = if (koIdx >= 0) t.substring(0, koIdx) else t
        var after = if (koIdx >= 0) t.substring(koIdx + 4) else ""
        after = after.replace(" ka", "").replace(" ke", "").trim()
        message = after.ifBlank { "Hi" }
        name = name.trim().split(" ").lastOrNull() ?: name.trim()
        return Pair(name.trim(), message.trim())
    }

    private fun findAppLaunchIntent(query: String): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.getDefault()).contains(query)
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun webIntent(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private fun String.containsAny(vararg keys: String) = keys.any { this.contains(it) }

    private fun String.removePrefixes(vararg prefixes: String): String {
        var s = this
        for (p in prefixes) if (s.startsWith(p)) { s = s.removePrefix(p); break }
        return s.trim()
    }
}
