package com.example.spamdetector

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.spamdetector.databinding.ActivityContractAnalysisBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContractAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContractAnalysisBinding
    private var selectedUri: Uri? = null
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContractAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectDocument.setOnClickListener { selectDocument() }
        binding.btnAnalyzeDocument.setOnClickListener { analyzeDocument() }
        binding.btnMockTerms.setOnClickListener { showMockTermsResult() }
        binding.btnConsultant.setOnClickListener {
            if (!isPremiumUnlocked()) return@setOnClickListener
            startActivity(Intent(this, ExpertChatActivity::class.java))
        }
        binding.btnUnlockPremium.setOnClickListener {
            getSharedPreferences("spam_detector_prefs", MODE_PRIVATE).edit()
                .putBoolean("premium_unlocked", true).apply()
            binding.btnUnlockPremium.visibility = View.GONE
            binding.tvPremiumLock.visibility = View.GONE
            binding.tvDocumentRecommendations.visibility = View.VISIBLE
            binding.btnConsultant.isEnabled = true
            binding.btnConsultant.text = "유료 1:1 상담사 매칭 신청"
        }
        setupBottomNavigation()
        loadSavedHistory()

        intent.getStringExtra(EXTRA_CAPTURE_PATH)?.let { path ->
            analyzeCapturedImage(File(path))
        }
    }

    private fun setupBottomNavigation() {
        binding.root.findViewById<View>(R.id.navSpamLogs).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        binding.root.findViewById<View>(R.id.navTerms).setOnClickListener { }
        binding.root.findViewById<View>(R.id.navExpertChat).setOnClickListener {
            startActivity(Intent(this, ExpertChatActivity::class.java))
            finish()
        }
    }

    private fun showMockTermsResult() {
        binding.tvSelectedDocument.text = "Mock 이용약관 샘플"
        showResult(
            DocumentAnalysisResult(
                score = 2.8,
                grade = "주의",
                summary = "무료 서비스 이용약관 Mock 데이터입니다. 자동 갱신과 개인정보 제공 범위를 확인하세요.",
                risks = listOf("별도 알림 없이 유료 서비스로 전환될 수 있는 조항", "제3자 광고·마케팅 목적의 개인정보 제공"),
                recommendations = listOf("자동 결제 및 해지 조건 확인", "개인정보 수집·제공 동의 범위 축소")
            )
        )
    }

    private fun selectDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_DOCUMENT
        )
    }

    @Deprecated("Deprecated in Android API, retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        selectedUri = data?.data
        selectedUri?.let { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            binding.tvSelectedDocument.text = "선택됨: ${getFileName(uri)}"
            binding.btnAnalyzeDocument.isEnabled = true
        }
    }

    private fun analyzeDocument() {
        val uri = selectedUri ?: return
        binding.btnAnalyzeDocument.isEnabled = false
        binding.btnAnalyzeDocument.text = "분석 중..."

        lifecycleScope.launch {
            val apiKey = getSharedPreferences("spam_detector_prefs", MODE_PRIVATE)
                .getString("openai_api_key", "") ?: ""
            val result = OpenAIService.analyzeDocumentImage(
                contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0),
                getFileName(uri),
                apiKey
            )
            showResult(result, getFileName(uri))
            binding.btnAnalyzeDocument.isEnabled = true
            binding.btnAnalyzeDocument.text = "AI로 내용 분석하기"
        }
    }

    private fun analyzeCapturedImage(file: File) {
        if (!file.exists()) return
        binding.tvSelectedDocument.text = "화면 캡처됨: 이용약관 분석 중..."
        lifecycleScope.launch {
            val apiKey = getSharedPreferences("spam_detector_prefs", MODE_PRIVATE)
                .getString("openai_api_key", "") ?: ""
            val result = OpenAIService.analyzeDocumentImage(file.readBytes(), file.name, apiKey)
            showResult(result, "화면 캡처 약관")
            showJudgementDialog(result)
            file.delete()
        }
    }

    private fun showResult(result: DocumentAnalysisResult, source: String = "선택한 설명서") {
        binding.cardAnalysisResult.visibility = View.VISIBLE
        binding.cardConsultant.visibility = View.VISIBLE
        binding.tvDocumentScore.text = "계약 점수 ${result.score}/5.0  ·  ${result.grade}"
        binding.tvDocumentScore.setTextColor(if (result.score >= 3.5) Color.parseColor("#34D399") else Color.parseColor("#FBBF24"))
        binding.tvDocumentSummary.text = result.summary
        binding.tvDocumentRisks.text = "확인할 조건\n${result.risks.joinToString("\n") { "• $it" }}"
        saveHistory(source, result)
        updatePremiumContent(result)
    }

    private fun showJudgementDialog(result: DocumentAnalysisResult) {
        AlertDialog.Builder(this)
            .setTitle("이용약관 판단 결과")
            .setMessage("${result.grade} (${result.score}/5.0)\n\n${result.summary}\n\n주의할 조건\n${result.risks.joinToString("\n") { "• $it" }}")
            .setPositiveButton("확인", null)
            .show()
    }

    private fun saveHistory(source: String, result: DocumentAnalysisResult) {
        val preferences = getSharedPreferences("spam_detector_prefs", MODE_PRIVATE)
        val type = object : TypeToken<ArrayList<ContractAnalysisHistory>>() {}.type
        val history: ArrayList<ContractAnalysisHistory> = try {
            gson.fromJson(preferences.getString("contract_analysis_history", "[]"), type) ?: ArrayList()
        } catch (_: Exception) {
            ArrayList()
        }
        history.add(0, ContractAnalysisHistory(
            source,
            result,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        ))
        if (history.size > 20) history.removeAt(history.lastIndex)
        preferences.edit().putString("contract_analysis_history", gson.toJson(history)).apply()
        renderHistory(history)
    }

    private fun loadSavedHistory() {
        val preferences = getSharedPreferences("spam_detector_prefs", MODE_PRIVATE)
        val type = object : TypeToken<ArrayList<ContractAnalysisHistory>>() {}.type
        val history: ArrayList<ContractAnalysisHistory> = try {
            gson.fromJson(preferences.getString("contract_analysis_history", "[]"), type) ?: ArrayList()
        } catch (_: Exception) {
            ArrayList()
        }
        renderHistory(history)
    }

    private fun renderHistory(history: List<ContractAnalysisHistory>) {
        binding.tvSavedHistory.text = if (history.isEmpty()) {
            "저장된 판단 내용이 없습니다."
        } else {
            history.joinToString("\n\n") { item ->
                "${item.savedAt} · ${item.source}\n${item.result.grade} (${item.result.score}/5.0) · ${item.result.summary}"
            }
        }
    }

    private fun updatePremiumContent(result: DocumentAnalysisResult) {
        val unlocked = isPremiumUnlocked()
        binding.tvPremiumLock.visibility = if (unlocked) View.GONE else View.VISIBLE
        binding.btnUnlockPremium.visibility = if (unlocked) View.GONE else View.VISIBLE
        binding.tvDocumentRecommendations.visibility = if (unlocked) View.VISIBLE else View.GONE
        binding.tvDocumentRecommendations.text = "더 나은 대안 서비스\n${result.recommendations.joinToString("\n") { "• $it" }}"
        binding.btnConsultant.isEnabled = unlocked
        binding.btnConsultant.text = if (unlocked) "유료 1:1 상담사 매칭 신청" else "🔒 유료 기능 잠금 해제 후 이용"
    }

    private fun isPremiumUnlocked(): Boolean = getSharedPreferences(
        "spam_detector_prefs", MODE_PRIVATE
    ).getBoolean("premium_unlocked", false)

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "계약 설명서 사진"
    }

    companion object {
        const val EXTRA_CAPTURE_PATH = "extra_capture_path"
        private const val REQUEST_DOCUMENT = 401
    }
}