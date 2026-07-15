package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Shows a small draggable microphone bubble that floats over every screen.
 * Tapping it opens the assistant and starts listening. This is the reliable,
 * always-available launcher (since apps cannot bind to the power button).
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        addBubble()
    }

    private fun startAsForeground() {
        val channelId = "assistant_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "Assistant", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Assistant is ready")
            .setContentText("Tap the floating button to speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun addBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        // Drag + tap handling
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        icon.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    windowManager.updateViewLayout(icon, params)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.rawX - touchX) + abs(e.rawY - touchY)
                    if (moved < 20) launchAssistant()
                    true
                }
                else -> false
            }
        }

        bubble = icon
        windowManager.addView(icon, params)
    }

    private fun launchAssistant() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
        }
        startActivity(i)
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }
}
