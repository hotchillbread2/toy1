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
