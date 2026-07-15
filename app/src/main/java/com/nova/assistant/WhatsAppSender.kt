package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Sends a WhatsApp message. Strategy:
 * 1. Open WhatsApp with a search intent for the contact name.
 * 2. Use the AccessibilityService to tap the first matching chat,
 *    type the message into the input box, and tap send.
 *
 * This works because typing/tapping in other apps is done by the
 * accessibility "hands", which the user has enabled.
 */
object WhatsAppSender {

    private val handler = Handler(Looper.getMainLooper())

    fun send(context: Context, contact: String, message: String) {
        // Open WhatsApp on its main screen
        val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (launch == null) {
            Speaker.say(context, "WhatsApp is not installed")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)

        val svc = ActionService.instance ?: run {
            Speaker.say(context, "Please enable the accessibility service")
            return
        }

        // Give WhatsApp time to open, then drive the UI step by step.
        handler.postDelayed({
            // Tap the search icon area is unreliable across versions;
            // instead find the contact by text in the chat list.
            if (!svc.clickByText(contact)) {
                // Try opening search first (magnifier usually top-right)
                val w = context.resources.displayMetrics.widthPixels.toFloat()
                svc.tap(w * 0.9f, context.resources.displayMetrics.heightPixels * 0.07f)
                handler.postDelayed({
                    svc.typeText(contact)
                    handler.postDelayed({
                        svc.clickByText(contact)
                        handler.postDelayed({ typeAndSend(svc, message) }, 1500)
                    }, 1200)
                }, 1200)
            } else {
                handler.postDelayed({ typeAndSend(svc, message) }, 1500)
            }
        }, 2500)
    }

    private fun typeAndSend(svc: ActionService, message: String) {
        svc.typeText(message)
        handler.postDelayed({
            // Send button: look for its description, fallback to bottom-right tap
            if (!svc.clickByText("Send")) {
                val res = svc.resources.displayMetrics
                svc.tap(res.widthPixels * 0.92f, res.heightPixels * 0.94f)
            }
        }, 800)
    }

    fun callContact(context: Context, contact: String) {
        val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launch == null) {
            Speaker.say(context, "WhatsApp is not installed")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        val svc = ActionService.instance ?: return
        handler.postDelayed({
            svc.clickByText(contact)
            handler.postDelayed({
                // Call icon is usually top-right in an open chat
                val res = svc.resources.displayMetrics
                svc.tap(res.widthPixels * 0.80f, res.heightPixels * 0.07f)
            }, 1500)
        }, 2500)
    }
}
