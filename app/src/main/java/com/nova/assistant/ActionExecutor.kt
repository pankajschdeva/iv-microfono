package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.URLEncoder
import java.util.Locale

/**
 * Runs a plan from AiBrain, one step at a time, pausing between steps so the
 * screen has time to react.
 */
object ActionExecutor {

    private val handler = Handler(Looper.getMainLooper())

    fun run(context: Context, steps: List<AiBrain.Step>, onDone: (() -> Unit)? = null) {
        runFrom(context, steps, 0, onDone)
    }

    private fun runFrom(
        context: Context,
        steps: List<AiBrain.Step>,
        index: Int,
        onDone: (() -> Unit)?
    ) {
        if (index >= steps.size) { onDone?.invoke(); return }
        val step = steps[index]
        val delay = execute(context, step)
        handler.postDelayed({ runFrom(context, steps, index + 1, onDone) }, delay)
    }

    /** Performs one step; returns how long to wait before the next one. */
    private fun execute(context: Context, step: AiBrain.Step): Long {
        val a = step.args
        val svc = ActionService.instance

        return when (step.action) {

            "open_app" -> {
                val name = a.optString("name")
                val i = findApp(context, name)
                if (i != null) { context.startActivity(i); 2500 }
                else { Speaker.say(context, "I could not find $name"); 300 }
            }

            "tap_text" -> { svc?.clickByText(a.optString("text")); 1200 }

            "type_text" -> { svc?.typeText(a.optString("text")); 1000 }

            "scroll" -> {
                if (a.optString("direction", "down") == "up") svc?.scrollUp()
                else svc?.scrollDown()
                1000
            }

            "auto_scroll" -> {
                val every = (a.optInt("interval", 15) * 1000).toLong()
                svc?.startAutoScroll(every)
                300
            }

            "stop" -> { svc?.stopAutoScroll(); 200 }

            "press" -> {
                when (a.optString("key")) {
                    "home" -> svc?.pressHome()
                    "recents" -> svc?.pressRecents()
                    else -> svc?.pressBack()
                }
                1000
            }

            "whatsapp_send" -> {
                WhatsAppSender.send(context, a.optString("contact"), a.optString("message"))
                6000
            }

            "call" -> {
                val t = a.optString("target")
                val digits = t.filter { it.isDigit() }
                if (digits.length >= 5) {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else WhatsAppSender.callContact(context, t)
                2000
            }

            "search_web" -> {
                open(context, "https://www.google.com/search?q=" + enc(a.optString("query")))
                1500
            }

            "open_url" -> { open(context, a.optString("url")); 1500 }

            "wait" -> (a.optInt("seconds", 2) * 1000).toLong()

            else -> 200 // "speak" and anything unknown: nothing to do
        }
    }

    private fun open(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun findApp(context: Context, query: String): Intent? {
        if (query.isBlank()) return null
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = query.lowercase(Locale.getDefault()).replace(" ", "")
        val match = pm.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.getDefault())
                .replace(" ", "").contains(q)
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
