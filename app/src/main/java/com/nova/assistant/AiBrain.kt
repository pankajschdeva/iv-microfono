package com.nova.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "brain". Sends what the user said to an AI model along with a
 * description of everything this app can do, and gets back a plan: a spoken
 * reply plus concrete steps for ActionExecutor to carry out.
 *
 * Works with EITHER provider, picked automatically from the key:
 *   - key starts with "sk-ant-"  -> Claude  (paid)
 *   - anything else              -> Gemini  (free tier, no card needed)
 */
object AiBrain {

    private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val main = Handler(Looper.getMainLooper())

    private val history = ArrayList<Pair<String, String>>()
    private const val MAX_TURNS = 6

    data class Step(val action: String, val args: JSONObject)
    data class Plan(val reply: String, val steps: List<Step>)

    fun clearHistory() = history.clear()

    private fun systemPrompt(assistantName: String, userName: String) = """
You are $assistantName, a voice assistant running on ${userName}'s Android phone.
You control the phone through an accessibility service, so you can really tap,
scroll and type inside any app.

Reply ONLY with a JSON object. No markdown, no backticks, no extra text.

Format:
{"reply":"<short spoken reply>","steps":[{"action":"<name>","args":{...}}]}

Available actions:
- open_app        args: {"name":"whatsapp"}
- tap_text        args: {"text":"Send"}
- type_text       args: {"text":"hello"}
- scroll          args: {"direction":"down"}
- auto_scroll     args: {"interval":15}   keep scrolling until told to stop
- stop            args: {}
- press           args: {"key":"back"}    back | home | recents
- whatsapp_send   args: {"contact":"Paras","message":"hello"}
- call            args: {"target":"9876543210"}
- search_web      args: {"query":"cricket score"}
- open_url        args: {"url":"https://..."}
- wait            args: {"seconds":3}
- speak           args: {}                reply only, no phone action

Rules:
- Understand Hindi, English and Hinglish. Always set "reply" in simple English.
- Break multi-step requests into ordered steps. Add "wait" steps (2-3 seconds)
  after opening an app or tapping, so the screen has time to load.
- If the user just chats or asks a question, use a single {"action":"speak"} step.
- Keep "reply" under 15 words; it is read aloud.
- If a request is unsafe or impossible, say so in "reply" with no steps.

Example:
User: insta kholo aur reel scroll karte raho
{"reply":"Opening Instagram and scrolling reels","steps":[{"action":"open_app","args":{"name":"instagram"}},{"action":"wait","args":{"seconds":3}},{"action":"auto_scroll","args":{"interval":15}}]}
""".trim()

    fun ask(context: Context, userText: String, onResult: (Plan?) -> Unit) {
        val key = with(Prefs) { context.apiKey }
        if (key.isBlank()) { onResult(null); return }

        val assistantName = with(Prefs) { context.assistantName }
        val userName = with(Prefs) { context.userName }
        val sys = systemPrompt(assistantName, userName)
        val useClaude = key.startsWith("sk-ant-")

        Thread {
            var plan: Plan? = null
            try {
                val text = if (useClaude) callClaude(key, sys, userText)
                           else callGemini(key, sys, userText)
                if (text != null) {
                    plan = parsePlan(text)
                    if (plan != null) remember(userText, text)
                }
            } catch (e: Exception) {
                plan = null
            }
            main.post { onResult(plan) }
        }.start()
    }

    // ---------- Gemini (free tier) ----------
    private fun callGemini(key: String, sys: String, userText: String): String? {
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(turn("user", u))
            contents.put(turn("model", a))
        }
        contents.put(turn("user", userText))

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", sys))))
            put("contents", contents)
            put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 800))
        }

        val raw = post("$GEMINI_URL?key=$key", body.toString(), null) ?: return null
        return JSONObject(raw)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
    }

    private fun turn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    // ---------- Claude (paid) ----------
    private fun callClaude(key: String, sys: String, userText: String): String? {
        val msgs = JSONArray()
        for ((u, a) in history) {
            msgs.put(JSONObject().put("role", "user").put("content", u))
            msgs.put(JSONObject().put("role", "assistant").put("content", a))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject().apply {
            put("model", CLAUDE_MODEL)
            put("max_tokens", 800)
            put("system", sys)
            put("messages", msgs)
        }
        val headers = mapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01"
        )
        val raw = post(CLAUDE_URL, body.toString(), headers) ?: return null
        return JSONObject(raw).getJSONArray("content")
            .getJSONObject(0).getString("text")
    }

    // ---------- shared HTTP ----------
    private fun post(url: String, body: String, headers: Map<String, String>?): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("content-type", "application/json")
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 20000
            readTimeout = 30000
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val ok = conn.responseCode in 200..299
        val stream = if (ok) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        return if (ok) raw else null
    }

    private fun remember(user: String, assistant: String) {
        history.add(user to assistant)
        while (history.size > MAX_TURNS) history.removeAt(0)
    }

    private fun parsePlan(text: String): Plan? = try {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val json = JSONObject(text.substring(start, end + 1))
        val steps = ArrayList<Step>()
        val arr = json.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            steps.add(Step(s.getString("action"), s.optJSONObject("args") ?: JSONObject()))
        }
        Plan(json.optString("reply", "Okay"), steps)
    } catch (e: Exception) {
        null
    }
}
