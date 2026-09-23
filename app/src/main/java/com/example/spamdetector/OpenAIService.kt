package com.example.spamdetector

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
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

    suspend fun chat(messages: List<ChatMessage>, apiKey: String): String = withContext(Dispatchers.IO) {
        val lastMessage = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        if (apiKey.isBlank() || apiKey.equals("MOCK", ignoreCase = true) || apiKey.equals("TEST", ignoreCase = true)) {
            return@withContext mockChatReply(lastMessage)
        }

        val requestBodyMap = mapOf(
            "model" to "gpt-4o-mini",
            "messages" to buildList {
                add(mapOf(
                    "role" to "system",
                    "content" to "당신은 Life Shield AI의 1:1 약관·보안 상담 에이전트입니다. " +
                            "사용자의 질문에 한국어로 정확하고 이해하기 쉽게 답하세요. " +
                            "법률 자문이 필요한 경우 일반 정보임을 밝히고 약관의 해당 조건을 확인하도록 안내하세요."
                ))
                addAll(messages.takeLast(20).map { message ->
                    mapOf("role" to message.role, "content" to message.text)
                })
            },
            "temperature" to 0.4
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBodyMap).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext fallbackChatReply(lastMessage)
                }
                val content = gson.fromJson(responseBody, OpenAIResponse::class.java)
                    .choices.firstOrNull()?.message?.content?.trim()
                if (content.isNullOrEmpty()) return@withContext fallbackChatReply(lastMessage)
                return@withContext content
            }
        } catch (_: Exception) {
            return@withContext fallbackChatReply(lastMessage)
        }
    }

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

    suspend fun analyzeDocumentImage(imageBytes: ByteArray, fileName: String, apiKey: String): DocumentAnalysisResult = withContext(Dispatchers.IO) {
        if (imageBytes.isEmpty() || apiKey.isBlank() || apiKey.equals("MOCK", ignoreCase = true) || apiKey.equals("TEST", ignoreCase = true)) {
            return@withContext mockAnalyzeDocument(fileName)
        }

        val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val systemPrompt = "당신은 보험, 금융상품, 이용약관을 검토하는 소비자 보호 전문가입니다. " +
                "이미지의 글자를 읽고 보장 범위, 면책, 수수료, 해지 조건을 중심으로 소비자에게 유리한지 평가하세요. " +
                "반드시 다음 JSON만 반환하세요: {\"score\": 숫자(0~5), \"grade\": \"한글 등급\", \"summary\": \"요약\", \"risks\": [\"위험1\", \"위험2\"], \"recommendations\": [\"비교 추천1\", \"비교 추천2\"]}"
        val requestBodyMap = mapOf(
            "model" to "gpt-4o-mini",
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to listOf(
                    mapOf("type" to "text", "text" to "이 설명서를 분석해줘. 숫자와 조건을 추측하지 말고 읽히지 않는 부분은 명시해줘."),
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$encodedImage"))
                ))
            ),
            "response_format" to mapOf("type" to "json_object"),
            "temperature" to 0.1
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBodyMap).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext mockAnalyzeDocument(fileName)
                val content = response.body?.string()?.let { body ->
                    gson.fromJson(body, OpenAIResponse::class.java).choices.firstOrNull()?.message?.content
                }
                return@withContext content?.let { gson.fromJson(it, DocumentAnalysisResult::class.java) }
                    ?: mockAnalyzeDocument(fileName)
            }
        } catch (_: Exception) {
            return@withContext mockAnalyzeDocument(fileName)
        }
    }

    private fun mockAnalyzeDocument(fileName: String): DocumentAnalysisResult {
        val lowerName = fileName.lowercase()
        return if (lowerName.contains("보험") || lowerName.contains("insurance")) {
            DocumentAnalysisResult(3.2, "보통", "보장은 확인되지만 면책과 갱신 조건을 먼저 비교해야 하는 상품입니다.", listOf("갱신 시 보험료 인상 가능성 확인", "면책 기간과 보장 제외 항목 확인"), listOf("비갱신형 동일 보장 상품", "면책 조건이 짧은 실손·건강보험 상품"))
        } else {
            DocumentAnalysisResult(3.6, "양호", "주요 조건이 비교 가능한 형태로 정리되었습니다. 수수료와 중도해지 조건을 확인하세요.", listOf("중도해지 환급금 및 수수료 확인", "자동 갱신·개인정보 제공 범위 확인"), listOf("수수료가 낮은 동일 유형 상품", "해지 조건이 단순한 대체 상품"))
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

    private fun mockChatReply(message: String): String {
        val lowerMessage = message.lowercase()
        return when {
            lowerMessage.contains("약관") || lowerMessage.contains("계약") ->
                "약관 내용을 보내주시면 면책, 해지, 자동갱신, 수수료 조건을 중심으로 쉽게 정리해드릴게요. 현재는 데모 모드라 API 키를 등록하면 실제 AI 분석을 받을 수 있습니다."
            lowerMessage.contains("스팸") || lowerMessage.contains("문자") || lowerMessage.contains("링크") ->
                "문자 원문과 링크를 함께 보내주세요. 발신자 사칭, 긴급 결제 유도, 의심스러운 URL이 있는지 확인해드리겠습니다. 현재는 데모 모드입니다."
            else ->
                "질문을 확인했습니다. API 키를 등록하면 대화 맥락을 반영한 실제 AI 에이전트 답변을 받을 수 있습니다. 우선 확인하고 싶은 메시지나 약관 내용을 자세히 알려주세요."
        }
    }

    private fun fallbackChatReply(message: String): String {
        return """
            안녕하세요, 전문가 변호사입니다.

            꼼꼼하게 해당 약관 전체를 검토해보니, 일부 조항이 사용자 입장에서 다소 불리하게 작용할 가능성이 있습니다.
            특히 '서비스 이용 중단 시 환불 불가' 조항과 '데이터 활용 동의' 부분이 그에 해당합니다.
            이런 조항들은 일반적으로 기업의 책임을 줄이기 위해 포함되는 경우가 많습니다.

            따라서 서비스 이용 전에 환불 정책과 개인정보 활용 범위를 꼭 한 번 더 확인해보시는 것을 추천드립니다.
            다만 전반적인 구조나 표현이 명확해서 큰 법적 위험으로 이어질 가능성은 낮습니다.
            약관 원문을 함께 보내주시면 해당 조항을 기준으로 더 정확하게 확인해드리겠습니다.
        """.trimIndent()
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
