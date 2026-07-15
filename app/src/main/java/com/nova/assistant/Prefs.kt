package com.nova.assistant

import android.content.Context

/** Simple storage for the two names the user enters on first launch. */
object Prefs {
    private const val FILE = "iv_micro_prefs"
    private const val KEY_USER = "user_name"
    private const val KEY_ASSISTANT = "assistant_name"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_VOICE_ON = "voice_on"
    private const val KEY_BUBBLE_ON = "bubble_on"
    private const val KEY_API = "api_key"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.userName: String
        get() = sp(this).getString(KEY_USER, "") ?: ""
        set(v) { sp(this).edit().putString(KEY_USER, v).apply() }

    var Context.assistantName: String
        get() = sp(this).getString(KEY_ASSISTANT, "assistant") ?: "assistant"
        set(v) { sp(this).edit().putString(KEY_ASSISTANT, v).apply() }

    var Context.onboarded: Boolean
        get() = sp(this).getBoolean(KEY_ONBOARDED, false)
        set(v) { sp(this).edit().putBoolean(KEY_ONBOARDED, v).apply() }

    var Context.voiceOn: Boolean
        get() = sp(this).getBoolean(KEY_VOICE_ON, true)
        set(v) { sp(this).edit().putBoolean(KEY_VOICE_ON, v).apply() }

    var Context.bubbleOn: Boolean
        get() = sp(this).getBoolean(KEY_BUBBLE_ON, false)
        set(v) { sp(this).edit().putBoolean(KEY_BUBBLE_ON, v).apply() }

    /** Claude API key. Empty = AI brain off, keyword mode only. */
    var Context.apiKey: String
        get() = sp(this).getString(KEY_API, "") ?: ""
        set(v) { sp(this).edit().putString(KEY_API, v).apply() }
}
