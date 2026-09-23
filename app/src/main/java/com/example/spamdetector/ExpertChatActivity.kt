package com.example.spamdetector

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.spamdetector.databinding.ActivityExpertChatBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
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
            sendMessage(message)
        }
    }

    private fun sendMessage(message: String) {
        val messages = loadMessages()
        val sentAt = timestamp()
        messages.add(ChatMessage(message, sentAt, "user"))
        saveMessages(messages)
        renderSavedMessages()
        binding.etMessage.text?.clear()
        setLoading(true)

        val apiKey = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString("openai_api_key", "").orEmpty()
        lifecycleScope.launch {
            try {
                val reply = OpenAIService.chat(messages, apiKey)
                messages.add(ChatMessage(reply, timestamp(), "assistant"))
                saveMessages(messages)
                renderSavedMessages()
            } catch (error: Exception) {
                Toast.makeText(this@ExpertChatActivity, error.message ?: "AI 응답을 받지 못했습니다.", Toast.LENGTH_LONG).show()
                binding.tvAgentStatus.text = "연결 오류"
            } finally {
                setLoading(false)
            }
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
        binding.chatMessages.removeAllViews()
        addBubble(
            text = "안녕하세요. 저는 약관과 의심스러운 메시지를 함께 확인하는 AI 에이전트입니다. 궁금한 내용을 보내주세요.",
            sentAt = "지금",
            isUser = false
        )
        loadMessages().forEach { message ->
            addBubble(message.text, message.sentAt, message.role == "user")
        }
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(text: String, sentAt: String, isUser: Boolean) {
        val bubble = TextView(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.text = "$text\n$sentAt"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            if (isUser) {
                gravity = Gravity.END
                setBackgroundResource(R.drawable.bg_chat_bubble_sent)
            } else {
                setBackgroundResource(R.drawable.bg_chat_bubble_received)
            }
            setTypeface(typeface, Typeface.NORMAL)
        }
        val row = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            orientation = LinearLayout.HORIZONTAL
            addView(bubble, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) }
        binding.chatMessages.addView(row, rowParams)
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSendMessage.isEnabled = !loading
        binding.etMessage.isEnabled = !loading
        binding.tvAgentStatus.text = if (loading) "답변 작성 중..." else "온라인 · AI 에이전트"
    }

    private fun saveMessages(messages: List<ChatMessage>) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(KEY_CHAT_MESSAGES, gson.toJson(messages))
            .apply()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        binding.btnSendMessage.setOnClickListener(null)
        super.onDestroy()
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
