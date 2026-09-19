package com.example.spamdetector

import com.google.gson.annotations.SerializedName

data class SpamAnalysisResult(
    @SerializedName("isSpam")
    val isSpam: Boolean,

    @SerializedName("riskLevel")
    val riskLevel: String, // NONE, LOW, MEDIUM, HIGH

    @SerializedName("reason")
    val reason: String
)

data class SpamLogItem(
    @SerializedName("title")
    val title: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("packageName")
    val packageName: String,

    @SerializedName("isSpam")
    val isSpam: Boolean,

    @SerializedName("riskLevel")
    val riskLevel: String,

    @SerializedName("reason")
    val reason: String,

    @SerializedName("time")
    val time: String
)

data class DocumentAnalysisResult(
    val score: Double,
    val grade: String,
    val summary: String,
    val risks: List<String>,
    val recommendations: List<String>
)

data class ContractAnalysisHistory(
    val source: String,
    val result: DocumentAnalysisResult,
    val savedAt: String
)

data class ChatMessage(
    val text: String,
    val sentAt: String
)
