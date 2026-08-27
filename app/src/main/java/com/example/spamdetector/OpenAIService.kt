package com.example.spamdetector

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object OpenAIService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    suspend fun analyzeMessage(messageContent: String, apiKey: String): SpamAnalysisResult = withContext(Dispatchers.IO) {
        // API 키가 없거나 MOCK/TEST 모드인 경우 스마트 목 분석기로 즉시 처리
        if (apiKey.isBlank() || apiKey.equals("MOCK", ignoreCase = true) || apiKey.equals("TEST", ignoreCase = true)) {
            return@withContext mockAnalyzeMessage(messageContent)
        }

        val systemPrompt = "당신은 수신된 문자/메시지 알림을 분석하여 스팸, 피싱(Phishing), 스미싱(Smishing), 또는 기타 보안 위험 요소가 있는지 탐지하는 보안 전문가입니다. " +
                "반드시 JSON 형식으로만 응답해야 합니다. " +
                "JSON 포맷 스키마:\n" +
                "{\n" +
                "  \"isSpam\": true 또는 false,\n" +
                "  \"riskLevel\": \"NONE\" 또는 \"LOW\" 또는 \"MEDIUM\" 또는 \"HIGH\",\n" +
                "  \"reason\": \"왜 스팸/보안 위험으로 판별했는지 또는 무해한 메시지인지 한국어로 명확히 요약(예: 택배 사칭 피싱 URL 포함)\"\n" +
                "}"

        val requestBodyMap = mapOf(
            "model" to "gpt-4o-mini",
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to "다음 메시지를 분석해줘:\n\n$messageContent")
            ),
            "response_format" to mapOf("type" to "json_object"),
            "temperature" to 0.1
        )

        val jsonBody = gson.toJson(requestBodyMap)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val apiResponse = gson.fromJson(responseBody, OpenAIResponse::class.java)
                        val content = apiResponse.choices.firstOrNull()?.message?.content
                        if (content != null) {
                            return@withContext gson.fromJson(content, SpamAnalysisResult::class.java)
                        }
                    }
                    return@withContext SpamAnalysisResult(false, "NONE", "응답 내용 파싱 실패")
                } else {
                    val errorMsg = response.body?.string() ?: ""
                    // API 에러 발생 시 테스트를 위해 mock 분석으로 fallback 안내
                    val fallback = mockAnalyzeMessage(messageContent)
                    return@withContext fallback.copy(
                        reason = "[Mock Fallback (API ${response.code})] ${fallback.reason}"
                    )
                }
            }
        } catch (e: Exception) {
            // 네트워크 오류 시 로컬 Mock 분석으로 fallback
            val fallback = mockAnalyzeMessage(messageContent)
            return@withContext fallback.copy(
                reason = "[Mock Fallback (오프라인)] ${fallback.reason}"
            )
        }
    }

    /**
     * 가상 에뮬레이터 테스트 및 API Key 미등록 상태에서도 즉시 시연 가능한 스마트 규칙 기반 목 분석기
     */
    fun mockAnalyzeMessage(message: String): SpamAnalysisResult {
        val lower = message.lowercase()

        // 1. 택배 / 배송 사칭 스미싱
        if ((lower.contains("택배") || lower.contains("배송") || lower.contains("통보서") || lower.contains("건강검진") || lower.contains("부고") || lower.contains("청첩장")) &&
            (lower.contains("http://") || lower.contains("https://") || lower.contains("url") || lower.contains(".net") || lower.contains(".xyz") || lower.contains(".kr/"))) {
            return SpamAnalysisResult(
                isSpam = true,
                riskLevel = "HIGH",
                reason = "기관/택배 사칭 및 악성 의심 단축 URL이 포함된 전형적인 스미싱(Smishing) 공격입니다."
            )
        }

        // 2. 금융/결제 사칭 피싱
        if ((lower.contains("해외인증") || lower.contains("해외결제") || lower.contains("결제완료") || lower.contains("승인") || lower.contains("카드발급")) &&
            (lower.contains("070-") || lower.contains("02-") || lower.contains("상담센터") || lower.contains("소비자보호원") || lower.contains("취소요청") || lower.contains("문의:"))) {
            return SpamAnalysisResult(
                isSpam = true,
                riskLevel = "HIGH",
                reason = "해외/카드 결제 사칭 및 가짜 고객센터 전화번호로 연결을 유도하는 보이스피싱/스미싱 수법입니다."
            )
        }

        // 3. 대출/도박/투자 스팸
        if (lower.contains("정부지원대출") || lower.contains("최저금리") || lower.contains("대출상담") || lower.contains("급전") ||
            lower.contains("카지노") || lower.contains("바카라") || lower.contains("급등주") || lower.contains("수익률") || lower.contains("무료리딩")) {
            return SpamAnalysisResult(
                isSpam = true,
                riskLevel = "MEDIUM",
                reason = "불법 대출/투자 리딩방/도박 유도 스팸 메시지입니다."
            )
        }

        // 4. 악성 URL 포함
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("bit.ly") || lower.contains("tinyurl")) {
            return SpamAnalysisResult(
                isSpam = true,
                riskLevel = "LOW",
                reason = "외부 URL 링크가 포함되어 있어 주의가 필요합니다."
            )
        }

        // 5. 정상 메시지
        return SpamAnalysisResult(
            isSpam = false,
            riskLevel = "NONE",
            reason = "위험 키워드 및 악성 링크가 감지되지 않은 안전한 일상 메시지입니다."
        )
    }

    // Helper classes for parsing OpenAI response
    private data class OpenAIResponse(
        val choices: List<Choice>
    )

    private data class Choice(
        val message: Message
    )

    private data class Message(
        val content: String
    )
}
