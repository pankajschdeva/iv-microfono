package com.nova.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nova.assistant.Prefs.assistantName
import com.nova.assistant.Prefs.onboarded
import com.nova.assistant.Prefs.userName
import com.nova.assistant.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.saveButton.setOnClickListener {
            val user = binding.userNameInput.text.toString().trim()
            val assistant = binding.assistantNameInput.text.toString().trim()
            if (user.isEmpty() || assistant.isEmpty()) {
                Toast.makeText(this, getString(R.string.fill_both), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            userName = user
            assistantName = assistant
            onboarded = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
