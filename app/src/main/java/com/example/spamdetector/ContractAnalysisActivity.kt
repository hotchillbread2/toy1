package com.example.spamdetector

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.spamdetector.databinding.ActivityContractAnalysisBinding
import kotlinx.coroutines.launch

class ContractAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContractAnalysisBinding
    private var selectedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContractAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectDocument.setOnClickListener { selectDocument() }
        binding.btnAnalyzeDocument.setOnClickListener { analyzeDocument() }
        binding.btnMockTerms.setOnClickListener { showMockTermsResult() }
        binding.btnConsultant.setOnClickListener {
            startActivity(Intent(this, ExpertChatActivity::class.java))
        }
        setupBottomNavigation()
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
            showResult(result)
            binding.btnAnalyzeDocument.isEnabled = true
            binding.btnAnalyzeDocument.text = "AI로 내용 분석하기"
        }
    }

    private fun showResult(result: DocumentAnalysisResult) {
        binding.cardAnalysisResult.visibility = View.VISIBLE
        binding.cardConsultant.visibility = View.VISIBLE
        binding.tvDocumentScore.text = "계약 점수 ${result.score}/5.0  ·  ${result.grade}"
        binding.tvDocumentScore.setTextColor(if (result.score >= 3.5) Color.parseColor("#34D399") else Color.parseColor("#FBBF24"))
        binding.tvDocumentSummary.text = result.summary
        binding.tvDocumentRisks.text = "확인할 조건\n${result.risks.joinToString("\n") { "• $it" }}"
        binding.tvDocumentRecommendations.text = "추천 비교 상품\n${result.recommendations.joinToString("\n") { "• $it" }}"
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "계약 설명서 사진"
    }

    companion object {
        private const val REQUEST_DOCUMENT = 401
    }
}