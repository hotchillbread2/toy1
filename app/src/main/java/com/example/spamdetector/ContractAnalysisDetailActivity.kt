package com.example.spamdetector

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.spamdetector.databinding.ActivityContractAnalysisDetailBinding
import com.google.gson.Gson

class ContractAnalysisDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContractAnalysisDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContractAnalysisDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val result = intent.getStringExtra(EXTRA_RESULT)?.let {
            runCatching { Gson().fromJson(it, DocumentAnalysisResult::class.java) }.getOrNull()
        } ?: run {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.tvDetailScore.text = "${result.score}/5.0"
        binding.tvDetailGrade.text = result.grade
        binding.tvDetailScore.setTextColor(
            if (result.score >= 3.5) Color.parseColor("#34D399") else Color.parseColor("#FBBF24")
        )
        binding.tvScoreGuide.text = scoreGuide(result.score)
        binding.tvDetailSummary.text = result.summary
        binding.tvRiskCount.text = "주의가 필요한 조건 ${result.risks.size}개"
        binding.riskContainer.removeAllViews()
        result.risks.forEachIndexed { index, risk ->
            binding.riskContainer.addView(
                detailItem("위험 조건 ${index + 1}", risk, "계약서에서 해당 조건의 적용 범위와 예외를 다시 확인하세요.")
            )
        }
        binding.recommendationContainer.removeAllViews()
        result.recommendations.forEachIndexed { index, recommendation ->
            binding.recommendationContainer.addView(
                detailItem("대안 ${index + 1}", recommendation, "가격뿐 아니라 해지·환불·개인정보 조건도 함께 확인하세요.")
            )
        }
        binding.tvActionPlan.text = actionPlan(result)
    }

    private fun scoreGuide(score: Double): String = when {
        score >= 4.0 -> "전반적으로 유리한 조건입니다. 다만 개인 상황에 맞지 않는 예외 조항은 없는지 확인하세요."
        score >= 3.0 -> "일부 조건은 수용할 수 있지만, 비용·해지·개인정보 조항을 비교한 뒤 결정하는 것이 좋습니다."
        else -> "주의가 필요한 계약입니다. 불리한 조건을 수정하거나 경쟁 상품을 비교한 뒤 가입을 결정하세요."
    }

    private fun actionPlan(result: DocumentAnalysisResult): String = buildString {
        append("1. 계약서 원문에서 표시된 위험 조건을 찾아 적용 대상과 예외를 확인하세요.\n")
        append("2. 월 비용, 총 납부액, 자동 갱신 및 해지 수수료를 경쟁 상품과 같은 기준으로 비교하세요.\n")
        append("3. 설명과 실제 약관이 다르면 판매자에게 서면 답변을 받고, 답변 전에는 동의하지 마세요.")
        if (result.risks.isEmpty()) append("\n4. 특별히 감지된 위험 조건은 없지만 중요한 결정 전 원문을 최종 확인하세요.")
    }

    private fun detailItem(title: String, content: String, guide: String): TextView = TextView(this).apply {
        text = "$title\n$content\n확인 포인트: $guide"
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 14f
        setPadding(0, 0, 0, 20)
    }

    companion object {
        const val EXTRA_RESULT = "extra_contract_analysis_result"
    }
}