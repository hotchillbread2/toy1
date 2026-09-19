package com.example.spamdetector

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.spamdetector.databinding.ActivityExpertChatBinding

class ExpertChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpertChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpertChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNavigation()

        binding.btnSendMessage.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            binding.tvChatHistory.append("\n나: $message")
            binding.etMessage.text?.clear()
            Toast.makeText(this, "전문가에게 메시지를 전달했습니다.", Toast.LENGTH_SHORT).show()
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
}
