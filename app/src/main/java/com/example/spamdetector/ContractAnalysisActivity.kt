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
        binding.btnConsultant.setOnClickListener {
            Toast.makeText(this, "상담 신청이 접수되었습니다. 매칭 가능한 상담사를 확인하는 중입니다.", Toast.LENGTH_LONG).show()
        }
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