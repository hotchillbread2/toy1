package com.example.spamdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.spamdetector.databinding.ActivityMainBinding
import com.example.spamdetector.databinding.ItemLogBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val gson = Gson()
    private val logList = ArrayList<SpamLogItem>()
    private lateinit var logAdapter: LogAdapter

    // 서비스에서 날아오는 스캔 이력 브로드캐스트 리시버
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MyNotificationListenerService.ACTION_NEW_LOG) {
                val json = intent.getStringExtra(MyNotificationListenerService.EXTRA_LOG_JSON)
                if (json != null) {
                    try {
                        val logItem = gson.fromJson(json, SpamLogItem::class.java)
                        runOnUiThread {
                            logList.add(0, logItem)
                            logAdapter.notifyItemInserted(0)
                            binding.rvLogs.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
        loadApiKeyAndHistory()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndServiceStatus()
        // 리시버 등록
        val filter = IntentFilter(MyNotificationListenerService.ACTION_NEW_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        // 리시버 해제
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupUI() {
        // RecyclerView 설정
        logAdapter = LogAdapter(logList)
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        // API Key 저장 버튼
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("openai_api_key", key).apply()
            if (key.isNotEmpty()) {
                Toast.makeText(this, "💡 API Key가 저장되었습니다. (OpenAI GPT-4o 연동)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "💡 로컬 스마트 Mock 모드로 전환되었습니다.", Toast.LENGTH_SHORT).show()
            }
            updatePermissionAndServiceStatus() // 갱신
        }

        // 권한 설정 이동 버튼
        binding.btnPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // 모니터링 토글 스위치 안내
        binding.swMonitoring.setOnClickListener {
            val hasPermission = isNotificationServiceEnabled()
            if (!hasPermission) {
                binding.swMonitoring.isChecked = false
                Toast.makeText(this, "⚠️ 모니터링 활성화를 위해 먼저 [알림 접근 권한]을 켜주세요.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
            } else {
                val isChecked = binding.swMonitoring.isChecked
                if (isChecked) {
                    Toast.makeText(this, "🛡️ 실시간 AI 보안 필터가 켜졌습니다. (백그라운드 자동 감지)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "🚫 완전히 끄려면 알림 접근 권한 설정에서 비활성화 하세요.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ===== Mock 데이터 테스트 시나리오 버튼들 =====

        // 1. 택배 배송 스미싱
        binding.btnMockDelivery.setOnClickListener {
            sendVirtualNotification(
                "CJ대한통운 (1588-1255)",
                "[CJ대한통운] 고객님의 운송장(842910) 주소지 불일치로 배송이 보류되었습니다. 주소 수정 및 재배송 신청: http://cj-post-modify.net/go"
            )
        }

        // 2. 해외결제 사칭 스미싱
        binding.btnMockPayment.setOnClickListener {
            sendVirtualNotification(
                "070-8912-3456",
                "[해외승인] 08/27 USD $799.00 해외 직구 결제 완료. 본인 이용 아닐 시 즉시 소비자보호센터 문의: 070-8912-3456"
            )
        }

        // 3. 대출/투자 유도 스팸
        binding.btnMockLoan.setOnClickListener {
            sendVirtualNotification(
                "010-3344-5566",
                "[정부지원] 서민안정 특별대출 지원대상 선정 안내. 연 2.1% 고정금리 최대 8천만원 즉시 승인! 카톡상담: http://bit.ly/gov-loan24"
            )
        }

        // 4. 모바일 부고장 피싱
        binding.btnMockCondolence.setOnClickListener {
            sendVirtualNotification(
                "010-8888-9999",
                "[부고] 모친(故 박영순)께서 별세하셨기에 부고를 전합니다. 장례식장 안내 및 조의금 전달: http://smart-condolence-msg.kr/view"
            )
        }

        // 5. 정상 친구 일상 대화
        binding.btnMockNormalFriend.setOnClickListener {
            sendVirtualNotification(
                "김민수 (동기)",
                "민수야 오늘 저녁 7시에 강남역 11번 출구 고깃집에서 보기로 한 거 잊지 않았지? 이따 보자!"
            )
        }

        // 6. 정상 은행/서비스 인증번호
        binding.btnMockNormalAuth.setOnClickListener {
            sendVirtualNotification(
                "국민은행 (1599-9999)",
                "[KB국민] 본인인증 번호는 [582914]입니다. 3분 이내에 입력해주세요. 타인에게 절대 공유 금지."
            )
        }

        // 로그 비우기
        binding.tvClearLogs.setOnClickListener {
            val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
            sharedPref.edit().remove("spam_logs").apply()
            val size = logList.size
            logList.clear()
            logAdapter.notifyItemRangeRemoved(0, size)
            Toast.makeText(this, "이력이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApiKeyAndHistory() {
        val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
        val apiKey = sharedPref.getString("openai_api_key", "") ?: ""
        binding.etApiKey.setText(apiKey)

        // 이력 데이터 파싱
        val rawLogs = sharedPref.getString("spam_logs", "[]") ?: "[]"
        val type = object : TypeToken<ArrayList<SpamLogItem>>() {}.type
        val savedList: ArrayList<SpamLogItem> = try {
            gson.fromJson(rawLogs, type) ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
        logList.clear()
        logList.addAll(savedList)
        logAdapter.notifyDataSetChanged()
    }

    private fun updatePermissionAndServiceStatus() {
        val hasPermission = isNotificationServiceEnabled()
        if (hasPermission) {
            binding.tvPermissionState.text = "✅ 알림 접근 권한 허용 완료"
            binding.tvPermissionState.setTextColor(Color.parseColor("#10B981")) // GreenAccent
            binding.btnPermission.visibility = View.GONE

            val sharedPref = getSharedPreferences("spam_detector_prefs", Context.MODE_PRIVATE)
            val apiKey = sharedPref.getString("openai_api_key", "") ?: ""
            if (apiKey.isNotEmpty()) {
                binding.tvStatusTitle.text = "실시간 GPT-4o 보안 탐지 동작 중"
                binding.tvStatusSub.text = "모든 메시지를 OpenAI 보안 모델이 실시간 스캔합니다."
            } else {
                binding.tvStatusTitle.text = "실시간 스마트 Mock 탐지 동작 중"
                binding.tvStatusSub.text = "로컬 AI 엔진 가동 중 (API Key 입력 시 GPT-4o 실시간 연동)"
            }
            binding.swMonitoring.isChecked = true
        } else {
            binding.tvPermissionState.text = "⚠️ 알림 접근 권한이 필요합니다."
            binding.tvPermissionState.setTextColor(Color.parseColor("#F59E0B")) // Amber
            binding.btnPermission.visibility = View.VISIBLE

            binding.tvStatusTitle.text = "실시간 보안 탐지 중지됨"
            binding.tvStatusSub.text = "권한 미승인으로 알림을 읽을 수 없습니다."
            binding.swMonitoring.isChecked = false
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            val names = flat.split(":")
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun checkAndRequestPermissions() {
        // Android 13 이상 푸시 알림 수신 동의 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun sendVirtualNotification(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "virtual_incoming_channel"
        val channelName = "가상 수신 문자"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        Toast.makeText(this, "🔔 1초 후 가상 메시지 알림이 도착합니다.", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        }, 1000)
    }

    // RecyclerView ViewHolder & Adapter
    class LogAdapter(private val logs: List<SpamLogItem>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            val title = if (log.title.isNotEmpty()) log.title else "알 수 없음"
            val content = log.content
            val packageName = log.packageName
            val time = log.time
            val isSpam = log.isSpam
            val riskLevel = log.riskLevel
            val reason = log.reason

            holder.binding.tvLogTitle.text = title
            holder.binding.tvLogApp.text = packageName
            holder.binding.tvLogContent.text = content
            holder.binding.tvLogTime.text = time
            holder.binding.tvLogReason.text = reason

            // 결과 배지 스타일링
            if (isSpam) {
                holder.binding.tvLogResultBadge.text = "⚠️ 스팸 ($riskLevel)"
                holder.binding.tvLogResultBadge.setTextColor(Color.parseColor("#EF4444"))
                holder.binding.tvLogResultBadge.setBackgroundColor(Color.parseColor("#3A1C22"))
                holder.binding.ivLogIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                holder.binding.ivLogIcon.setColorFilter(Color.parseColor("#EF4444"))
            } else {
                holder.binding.tvLogResultBadge.text = "✅ 안전"
                holder.binding.tvLogResultBadge.setTextColor(Color.parseColor("#10B981"))
                holder.binding.tvLogResultBadge.setBackgroundColor(Color.parseColor("#142E28"))
                holder.binding.ivLogIcon.setImageResource(android.R.drawable.ic_dialog_info)
                holder.binding.ivLogIcon.setColorFilter(Color.parseColor("#10B981"))
            }
        }

        override fun getItemCount(): Int = logs.size
    }
}
