package com.example.spamdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyNotificationListenerService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val gson = Gson()

    companion object {
        const val ACTION_NEW_LOG = "com.example.spamdetector.ACTION_NEW_LOG"
        const val EXTRA_LOG_JSON = "extra_log_json"
        
        private const val CHANNEL_ID = "spam_detector_alerts"
        private const val CHANNEL_NAME = "보안 스팸 경고"
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""
        val extras = sbn.notification.extras
        val title = extras.getString(NotificationCompat.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: ""

        // 비어있는 내용 스킵
        if (text.isEmpty()) return

        // 1. 분석 대상 앱 지정 (대표 메시지 앱 및 카카오톡, 에뮬레이터 기본 메시지 앱)
        val targetPackages = listOf(
            "com.android.mms",                         // 삼성/기본 메시지
            "com.android.messaging",                   // AOSP/에뮬레이터 기본 메시지 앱
            "com.google.android.apps.messaging",       // 구글 픽셀/순정 메시지
            "com.samsung.android.messaging",           // 최신 삼성 OneUI 메시지
            "com.kakao.talk",                          // 카카오톡
            "com.example.spamdetector"                 // 자체 테스트 가상 알림
        )

        // 패키지명이 비어있지 않고 대상 패키지에 포함되거나, SMS 관련 앱인 경우 분석
        val isTarget = targetPackages.any { packageName.contains(it, ignoreCase = true) } ||
                packageName.contains("sms", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("message", ignoreCase = true)

        if (!isTarget) {
            return
        }

        // 2. 저장소에서 API Key 로드 (없으면 Mock 모드로 자동 동작)
        val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
        val apiKey = sharedPref.getString("openai_api_key", "") ?: ""

        // 3. 코루틴을 사용하여 비동기 분석 수행
        scope.launch {
            val messageToAnalyze = if (title.isNotEmpty()) "[$title] $text" else text
            val result = OpenAIService.analyzeMessage(messageToAnalyze, apiKey)

            // 로그 데이터 객체화
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logItem = SpamLogItem(
                title = if (title.isNotEmpty()) title else "알림 수신",
                content = text,
                packageName = packageName,
                isSpam = result.isSpam,
                riskLevel = result.riskLevel,
                reason = result.reason,
                time = currentTime
            )
            val logJson = gson.toJson(logItem)

            // 4. 로컬 저장소에 로그 데이터 누적 저장
            saveLogToPref(logItem)

            // 5. 실시간 화면 갱신을 위해 메인 액티비티로 브로드캐스트 발송
            val intent = Intent(ACTION_NEW_LOG).apply {
                putExtra(EXTRA_LOG_JSON, logJson)
                setPackage(this@MyNotificationListenerService.packageName)
            }
            sendBroadcast(intent)

            // 6. 스팸 또는 심각한 보안 위험일 때 경고 알림 발생
            if (result.isSpam || result.riskLevel == "HIGH" || result.riskLevel == "MEDIUM") {
                showSecurityAlertNotification(
                    "⚠️ 보안 경고! 스팸/피싱 감지 (${result.riskLevel})",
                    "위험 내용: ${result.reason}\n원문: $text"
                )
            }
        }
    }

    private fun saveLogToPref(newItem: SpamLogItem) {
        val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
        val rawLogs = sharedPref.getString("spam_logs", "[]") ?: "[]"
        
        val type = object : TypeToken<ArrayList<SpamLogItem>>() {}.type
        val logList: ArrayList<SpamLogItem> = try {
            gson.fromJson(rawLogs, type) ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }

        // 최신 로그를 처음에 추가
        logList.add(0, newItem)

        // 최대 100개까지만 유지
        if (logList.size > 100) {
            logList.removeAt(logList.size - 1)
        }

        sharedPref.edit().putString("spam_logs", gson.toJson(logList)).apply()
    }

    private fun showSecurityAlertNotification(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 안드로이드 8.0 Oreo 이상 채널 필수 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "보안 스팸 및 스미싱 의심 문자 알림 채널"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // 경고 아이콘
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        // 고유 ID를 부여하여 알림 노출
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
