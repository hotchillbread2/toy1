package com.example.spamdetector

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.spamdetector.databinding.ActivityExpertChatBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpertChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpertChatBinding
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpertChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNavigation()
        renderSavedMessages()

        binding.btnSendMessage.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            val messages = loadMessages()
            val sentAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            messages.add(ChatMessage(message, sentAt))
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_CHAT_MESSAGES, gson.toJson(messages))
                .apply()
            renderSavedMessages()
            binding.etMessage.text?.clear()
            Toast.makeText(this, "전문가에게 메시지를 전달했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMessages(): ArrayList<ChatMessage> {
        val type = object : TypeToken<ArrayList<ChatMessage>>() {}.type
        return try {
            gson.fromJson(
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString(KEY_CHAT_MESSAGES, "[]"), type
            ) ?: ArrayList()
        } catch (_: Exception) {
            ArrayList()
        }
    }

    private fun renderSavedMessages() {
        val savedText = loadMessages().joinToString("\n\n") { message ->
            "나 (${message.sentAt})\n${message.text}"
        }
        binding.tvChatHistory.text = buildString {
            append("안녕하세요. 확인하고 싶은 약관이나 문장을 남겨주시면 함께 살펴보겠습니다.")
            if (savedText.isNotEmpty()) append("\n\n$savedText")
        }
    }

    private fun setupBottomNavigation() {
        binding.root.findViewById<android.view.View>(R.id.navSpamLogs).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        binding.root.findViewById<android.view.View>(R.id.navTerms).setOnClickListener {
            startActivity(Intent(this, ContractAnalysisActivity::class.java))
            finish()
        }
        binding.root.findViewById<android.view.View>(R.id.navExpertChat).setOnClickListener { }
    }

    companion object {
        private const val PREFERENCES = "spam_detector_prefs"
        private const val KEY_CHAT_MESSAGES = "expert_chat_messages"
    }
}
