package com.nova.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nova.assistant.Prefs.apiKey
import com.nova.assistant.Prefs.assistantName
import com.nova.assistant.Prefs.bubbleOn
import com.nova.assistant.Prefs.onboarded
import com.nova.assistant.Prefs.userName
import com.nova.assistant.Prefs.voiceOn
import com.nova.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recognizer: SpeechRecognizer? = null
    private lateinit var commands: CommandProcessor

    private val permRequest =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch -> go to onboarding (two name fields)
        if (!onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        commands = CommandProcessor(this)
        Speaker.init(this)

        binding.greetingName.text = userName.ifBlank { getString(R.string.greeting_name) }
        refreshToggleLabels()

        binding.micButton.setOnClickListener { startListening() }
        binding.actionAsk.setOnClickListener { startListening() }
        binding.actionOpen.setOnClickListener { startListening() }
        binding.actionCall.setOnClickListener { startListening() }
        binding.actionSearch.setOnClickListener { startListening() }

        binding.enableBubble.setOnClickListener { ensureOverlayThenStartBubble() }
        binding.enableAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.voiceToggle.setOnClickListener {
            voiceOn = !voiceOn; refreshToggleLabels()
        }
        binding.bubbleToggle.setOnClickListener {
            if (!bubbleOn) ensureOverlayThenStartBubble()
            else { bubbleOn = false; stopService(Intent(this, FloatingBubbleService::class.java)) }
            refreshToggleLabels()
        }

        requestCorePermissions()

        if (intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) {
            binding.root.post { startListening() }
        }
    }

    private fun refreshToggleLabels() {
        binding.voiceToggle.text =
            if (voiceOn) getString(R.string.voice_on) else getString(R.string.voice_off)
        binding.bubbleToggle.text =
            if (bubbleOn) getString(R.string.bubble_on) else getString(R.string.bubble_off)
        binding.accStatus.text =
            if (ActionService.isRunning()) getString(R.string.acc_on)
            else getString(R.string.acc_off)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshToggleLabels()
    }

    private fun requestCorePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) permRequest.launch(toAsk.toTypedArray())
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (!voiceOn) { setStatus(getString(R.string.voice_is_off)); return }
        if (!hasMic()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 100
            )
            setStatus(getString(R.string.status_need_mic))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.status_no_speech)); return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // en-IN keeps the output in Latin script and still copes with
            // Hinglish. We also ask for several alternatives and try them all.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        setStatus(getString(R.string.status_listening))
        binding.micButton.isActivated = true
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { binding.micButton.isActivated = false }
        override fun onError(error: Int) {
            binding.micButton.isActivated = false
            setStatus(getString(R.string.status_try_again))
        }
        override fun onPartialResults(partial: Bundle?) {
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) binding.transcript.text = t
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            binding.micButton.isActivated = false
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (all.isNullOrEmpty()) { setStatus(getString(R.string.status_try_again)); return }
            binding.transcript.text = all[0]
            handleCommand(all)
        }
    }

    private fun handleCommand(candidates: List<String>) {
        // Fast path: simple keyword commands run instantly, offline and free.
        val reply = commands.processAll(candidates)
        if (reply.understood) {
            setStatus(reply.spoken)
            Speaker.say(this, reply.spoken)
            reply.action?.let { runCatching { startActivity(it) } }
            return
        }
        // Anything else goes to the AI brain, which can plan multiple steps.
        askBrain(candidates.firstOrNull() ?: return)
    }

    private fun askBrain(text: String) {
        if (apiKey.isBlank()) {
            setStatus(getString(R.string.no_api_key))
            Speaker.say(this, "Please add your API key in settings")
            return
        }
        setStatus(getString(R.string.thinking))
        AiBrain.ask(this, text) { plan ->
            if (plan == null) {
                setStatus(getString(R.string.brain_error))
                Speaker.say(this, "Sorry, I could not reach my brain right now")
                return@ask
            }
            setStatus(plan.reply)
            Speaker.say(this, plan.reply)
            if (plan.steps.isNotEmpty()) ActionExecutor.run(this, plan.steps)
        }
    }

    private fun setStatus(msg: String) { binding.status.text = msg }

    private fun openAccessibilitySettings() {
        setStatus(getString(R.string.acc_hint))
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun ensureOverlayThenStartBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            setStatus(getString(R.string.status_grant_overlay))
            return
        }
        bubbleOn = true
        ContextCompat.startForegroundService(this, Intent(this, FloatingBubbleService::class.java))
        setStatus(getString(R.string.status_bubble_on))
        refreshToggleLabels()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_AUTO_LISTEN = "auto_listen" }
}
