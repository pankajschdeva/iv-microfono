package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns recognized speech (Hindi OR English, Latin OR Devanagari script)
 * into an action. Google may return the phrase in either script, so we
 * normalise Devanagari to Latin keywords before matching.
 */
class CommandProcessor(private val context: Context) {

    data class Result(
        val spoken: String,
        val action: Intent? = null,
        val understood: Boolean = true
    )

    /** Try every alternative Google gave us; return the first understood one. */
    fun processAll(candidates: List<String>): Result {
        for (c in candidates) {
            if (c.isBlank()) continue
            val r = process(c)
            if (r.understood) return r
        }
        return process(candidates.firstOrNull() ?: "")
    }

    fun process(raw: String): Result {
        val text = normalise(raw)

        // ---- STOP ----
        if (text.containsAny("stop", "ruk", "band karo", "bas karo")) {
            ActionService.instance?.stopAutoScroll()
            return Result("Okay, stopped")
        }

        // ---- WhatsApp send ----
        if (text.contains("whatsapp") && text.containsAny("bhej", "send", "message", "msg")) {
            val (name, msg) = extractContactAndMessage(text)
            if (name.isNotBlank()) {
                WhatsAppSender.send(context, name, msg)
                return Result("Sending WhatsApp message to $name")
            }
            return Result("Please tell me the contact name")
        }

        // ---- Auto-scroll ----
        if (text.containsAny("scroll", "reel")) {
            val svc = ActionService.instance
                ?: return Result("Please enable full control first")
            svc.startAutoScroll(15000)
            return Result("Scrolling. Say stop when you want me to stop.")
        }

        // ---- Open app ----
        if (text.containsAny("open", "kholo", "khol do", "launch", "chalu karo")) {
            val name = text.removePrefixes("open", "kholo", "khol do", "launch", "chalu karo")
            val launch = findAppLaunchIntent(name)
            return if (launch != null) Result("Opening $name", launch)
            else Result("I could not find an app named $name")
        }

        // ---- Call ----
        if (text.containsAny("call", "phone karo", "dial")) {
            val number = text.filter { it.isDigit() }
            if (number.length >= 5) {
                return Result("Calling $number",
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val name = text.removePrefixes("call", "phone karo", "dial")
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
        if (text.containsAny("home")) {
            ActionService.instance?.pressHome(); return Result("Home")
        }

        // ---- Time / Date ----
        if (text.containsAny("time", "samay", "kitne baje", "baje")) {
            return Result("The time is " +
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
        }
        if (text.containsAny("date", "tareekh")) {
            return Result("Today is " +
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()))
        }

        // ---- YouTube ----
        if (text.contains("youtube")) {
            val q = text.removePrefixes("youtube", "play")
            return Result("Opening YouTube",
                webIntent("https://www.youtube.com/results?search_query=" + enc(q)))
        }

        // ---- Maps ----
        if (text.containsAny("map", "direction", "raasta", "location")) {
            val place = text.removePrefixes("map", "maps", "direction", "raasta", "location")
            return Result("Opening maps for $place",
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(place)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // ---- Search ----
        if (text.containsAny("search", "google", "khojo", "dhundo")) {
            val q = text.removePrefixes("search for", "search", "google", "khojo", "dhundo")
            return Result("Searching for $q",
                webIntent("https://www.google.com/search?q=" + enc(q)))
        }

        // ---- Greeting ----
        if (text.containsAny("hello", "namaste", "hey")) {
            return Result("Hello. How can I help you?")
        }

        return Result("I did not understand that. Please try again.", null, false)
    }

    /**
     * Google returns Hindi speech in Devanagari. Map the words we care about
     * to their Latin equivalents so a single matcher handles both scripts.
     */
    private fun normalise(raw: String): String {
        var t = raw.trim().lowercase(Locale.getDefault())
        for ((dev, latin) in DEVANAGARI) t = t.replace(dev, latin)
        return t.replace("\\s+".toRegex(), " ").trim()
    }

    private fun extractContactAndMessage(text: String): Pair<String, String> {
        var t = text.replace("whatsapp", " ")
            .replace(" pe ", " ").replace(" par ", " ").replace(" pr ", " ")
        for (m in listOf("bhej do", "bhejo", "bhej", "send", "message", "msg")) {
            val i = t.indexOf(m)
            if (i >= 0) { t = t.substring(0, i); break }
        }
        val koIdx = t.indexOf(" ko ")
        val name = (if (koIdx >= 0) t.substring(0, koIdx) else t).trim()
            .split(" ").lastOrNull()?.trim() ?: ""
        var msg = if (koIdx >= 0) t.substring(koIdx + 4) else ""
        msg = msg.replace(" ka", "").replace(" ke", "").trim().ifBlank { "Hi" }
        return Pair(name, msg)
    }

    private fun findAppLaunchIntent(query: String): Intent? {
        if (query.isBlank()) return null
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = query.replace(" ", "")
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.getDefault())
                .replace(" ", "").contains(q)
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
        for (p in prefixes) {
            val i = s.indexOf(p)
            if (i >= 0) { s = s.substring(i + p.length); break }
        }
        return s.trim()
    }

    companion object {
        // Devanagari -> Latin for the words this assistant reacts to.
        private val DEVANAGARI = listOf(
            "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
            "इंस्टाग्राम" to "instagram", "इंस्टा" to "instagram",
            "यूट्यूब" to "youtube", "स्नैपचैट" to "snapchat", "फेसबुक" to "facebook",
            "ओपन" to "open", "खोलो" to "open", "खोल" to "open", "चालू" to "open",
            "कॉल" to "call", "फोन" to "call", "डायल" to "call",
            "सर्च" to "search", "खोजो" to "search", "ढूंढो" to "search", "गूगल" to "google",
            "मैसेज" to "message", "संदेश" to "message", "भेजो" to "bhej", "भेज" to "bhej",
            "स्क्रॉल" to "scroll", "रील" to "reel",
            "रुको" to "stop", "रुक" to "stop", "बंद" to "stop", "स्टॉप" to "stop",
            "समय" to "time", "टाइम" to "time", "बजे" to "baje", "तारीख" to "date",
            "होम" to "home", "पीछे" to "back", "बैक" to "back",
            "नक्शा" to "map", "मैप" to "map", "रास्ता" to "map",
            "नमस्ते" to "hello", "हेलो" to "hello",
            "को" to " ko "
        )
    }
}
