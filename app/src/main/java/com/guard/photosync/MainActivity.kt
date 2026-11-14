package com.guard.photosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuBadge: TextView
    private lateinit var networkBadge: TextView
    private lateinit var notifySwitch: MaterialSwitch
    private lateinit var delayValueText: TextView
    private lateinit var delaySlider: Slider
    private lateinit var logContainer: LinearLayout
    private lateinit var logCountText: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        updateUI()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku 授權成功", Toast.LENGTH_SHORT).show()
        }
    }

    private val stateListener: () -> Unit = {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        GuardStateManager.addListener(stateListener)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.md_background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "PhotoSyncGuard"
            subtitle = "相機算圖期間延遲 Google 相簿備份"
            setTitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            setSubtitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.md_surface))
            elevation = dp(2).toFloat()
        }
        root.addView(toolbar)

        val scrollView = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // 1. 最上方：緊急防呆按鈕（立即恢復 Google 相簿上傳與連線功能）
        content.addView(buildFailsafeCard())

        // 2. 系統狀態與通知開關卡片
        content.addView(buildStatusAndNotifyCard())

        // 3. 權限設定與手動測試卡片
        content.addView(buildSetupCard())

        // 4. 下方：24 小時處理過程與阻擋/恢復紀錄
        content.addView(buildLogCard())

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun buildFailsafeCard(): MaterialCardView {
        val card = createCard(
            strokeColorRes = R.color.failsafe_bg,
            strokeWidthDp = 2
        )
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "緊急防呆恢復"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val desc = TextView(this).apply {
            text = "若發現 Google 相簿長時間無法上傳或卡住，點擊下方按鈕可立即強制解除封鎖並恢復連線。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, dp(4), 0, dp(12))
        }
        val btnFailsafe = MaterialButton(this).apply {
            text = "立即恢復 Google 相簿上傳與連線"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.failsafe_bg)
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.failsafe_fg))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                sendBroadcast(Intent(CameraGuardService.ACTION_FORCE_RESTORE).setPackage(packageName))
                val ok = ShizukuNetHelper.setPhotosNetworkBlocked(
                    this@MainActivity,
                    false,
                    "使用者點擊緊急防呆按鈕，強制恢復 Google 相簿上傳與連線"
                )
                GuardStateManager.addLog(
                    this@MainActivity,
                    if (ok) GuardStateManager.LogCategory.MANUAL_RESTORE else GuardStateManager.LogCategory.RESTORE_FAIL,
                    if (ok) "防呆恢復已執行" else "防呆恢復失敗",
                    if (ok) "已清除算圖鎖定並恢復 Google 相簿網路連線" else "請確認 Shizuku 已啟動且授權"
                )
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "已恢復 Google 相簿網路連線" else "恢復失敗，請檢查 Shizuku 權限",
                    Toast.LENGTH_SHORT
                ).show()
                updateUI()
            }
        }

        inner.addView(title)
        inner.addView(desc)
        inner.addView(btnFailsafe)
        card.addView(inner)
        return card
    }

    private fun buildStatusAndNotifyCard(): MaterialCardView {
        val card = createCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val header = TextView(this).apply {
            text = "運作狀態與通知設定"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            setPadding(0, 0, 0, dp(12))
        }

        shizukuBadge = createBadgeView()
        networkBadge = createBadgeView()

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        badgeRow.addView(shizukuBadge)
        badgeRow.addView(networkBadge)

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val switchLabelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchTitle = TextView(this).apply {
            text = "延遲上傳與算圖完成通知"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val switchSub = TextView(this).apply {
            text = "顯示是否正在延遲 Google 相簿上傳，以及算圖完畢恢復連線的通知"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
        }
        switchLabelBox.addView(switchTitle)
        switchLabelBox.addView(switchSub)

        notifySwitch = MaterialSwitch(this).apply {
            isChecked = GuardStateManager.isNotificationEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            102
                        )
                    }
                }
                GuardStateManager.setNotificationEnabled(this@MainActivity, isChecked)
            }
        }

        switchRow.addView(switchLabelBox)
        switchRow.addView(notifySwitch)

        // 延遲恢復上傳時間設定
        val delaySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        val delayTitle = TextView(this).apply {
            text = "延遲恢復 Google 相簿上傳時間"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        delayValueText = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_primary))
            setPadding(0, dp(4), 0, dp(4))
        }
        delaySlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 30f
            stepSize = 1f
            value = GuardStateManager.getRestoreDelaySec(this@MainActivity).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    GuardStateManager.setRestoreDelaySec(this@MainActivity, value.toInt())
                }
            }
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        for (sec in listOf(3, 5, 10, 15)) {
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (sec == 5) "${sec}秒(預設)" else "${sec}秒"
                textSize = 12f
                cornerRadius = dp(8)
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    GuardStateManager.setRestoreDelaySec(this@MainActivity, sec)
                }
            }
            presetRow.addView(btn)
        }
        val delayNote = TextView(this).apply {
            text = "極端小圖 (< 1MB) 仍保有 35 秒無寫入安全兜底自動恢復。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, dp(6), 0, 0)
        }
        delaySection.addView(delayTitle)
        delaySection.addView(delayValueText)
        delaySection.addView(delaySlider)
        delaySection.addView(presetRow)
        delaySection.addView(delayNote)

        inner.addView(header)
        inner.addView(badgeRow)
        inner.addView(switchRow)
        inner.addView(delaySection)
        card.addView(inner)
        return card
    }

    private fun buildSetupCard(): MaterialCardView {
        val card = createCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val header = TextView(this).apply {
            text = "權限引導與測試"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            setPadding(0, 0, 0, dp(8))
        }

        val hint = TextView(this).apply {
            text = "⚠️ 請於系統設定開啟本 App 的「自啟動」與「允許背景高耗電」，避免拍到一半被系統回收。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, 0, 0, dp(12))
        }

        val btnShizuku = createActionButton("1. 授權 Shizuku") {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(100)
                } else {
                    Toast.makeText(this, "已取得 Shizuku 權限", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "請先啟動 Shizuku 服務", Toast.LENGTH_SHORT).show()
            }
        }

        val btnStorage = createActionButton("2. 授權相簿目錄監聽 (讀取圖片權限)") {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
        }

        val btnAccessibility = createActionButton("3. 開啟無障礙服務 (偵測相機)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val btnTestBlock = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "測試：手動切斷 Google 相簿網路"
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                ShizukuNetHelper.setPhotosNetworkBlocked(this@MainActivity, true, "使用者手動測試阻斷")
                updateUI()
            }
        }

        inner.addView(header)
        inner.addView(hint)
        inner.addView(btnShizuku)
        inner.addView(btnStorage)
        inner.addView(btnAccessibility)
        inner.addView(btnTestBlock)
        card.addView(inner)
        return card
    }

    private fun buildLogCard(): MaterialCardView {
        val card = createCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(this).apply {
            text = "近 24 小時處理紀錄"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClear = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "清除"
            setOnClickListener {
                GuardStateManager.clearLogs(this@MainActivity)
            }
        }
        headerRow.addView(title)
        headerRow.addView(btnClear)

        logCountText = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, 0, 0, dp(10))
        }

        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        inner.addView(headerRow)
        inner.addView(logCountText)
        inner.addView(logContainer)
        card.addView(inner)
        return card
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val shizukuReady = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        applyBadgeStyle(
            shizukuBadge,
            if (shizukuReady) "✅ Shizuku 權限：已授權" else "❌ Shizuku 權限：未授權",
            if (shizukuReady) R.color.status_safe_bg else R.color.status_error_bg,
            if (shizukuReady) R.color.status_safe_fg else R.color.status_error_fg
        )

        val blocked = ShizukuNetHelper.isBlocked
        applyBadgeStyle(
            networkBadge,
            if (blocked) "⏳ Google 相簿網路：封鎖中（延遲上傳等待算圖）" else "🟢 Google 相簿網路：正常連線可上傳",
            if (blocked) R.color.status_blocked_bg else R.color.status_safe_bg,
            if (blocked) R.color.status_blocked_fg else R.color.status_safe_fg
        )

        notifySwitch.isChecked = GuardStateManager.isNotificationEnabled(this)

        val delaySec = GuardStateManager.getRestoreDelaySec(this)
        delayValueText.text = "目前設定：算圖完成且離開相機後，延遲 $delaySec 秒恢復上傳"
        if (delaySlider.value.toInt() != delaySec) {
            delaySlider.value = delaySec.toFloat()
        }

        val logs = GuardStateManager.getLogs(this)
        logCountText.text = "自動保留最近 24 小時內的阻擋、算圖判讀與恢復紀錄（目前 ${logs.size} 筆，逾時自動移除）"
        logContainer.removeAllViews()

        if (logs.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "尚無 24 小時內的處理紀錄。"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
                setPadding(0, dp(8), 0, dp(8))
            }
            logContainer.addView(emptyView)
        } else {
            for (entry in logs) {
                logContainer.addView(buildLogItemView(entry))
            }
        }
    }

    private fun buildLogItemView(entry: GuardStateManager.LogEntry): LinearLayout {
        val (icon, colorRes) = when (entry.category) {
            GuardStateManager.LogCategory.BLOCK_OK -> "🛡️" to R.color.status_blocked_fg
            GuardStateManager.LogCategory.BLOCK_FAIL -> "❌" to R.color.status_error_fg
            GuardStateManager.LogCategory.PHOTO_WAIT -> "⏳" to R.color.status_blocked_fg
            GuardStateManager.LogCategory.PHOTO_READY -> "📸" to R.color.md_primary
            GuardStateManager.LogCategory.RESTORE_OK -> "✅" to R.color.status_safe_fg
            GuardStateManager.LogCategory.RESTORE_FAIL -> "⚠️" to R.color.status_error_fg
            GuardStateManager.LogCategory.MANUAL_RESTORE -> "🆘" to R.color.status_safe_fg
            GuardStateManager.LogCategory.INFO -> "ℹ️" to R.color.md_on_surface_muted
        }

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.md_surface_variant))
                cornerRadius = dp(10).toFloat()
            }
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "$icon ${entry.title}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val timeTv = TextView(this).apply {
            text = entry.formattedTime()
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
        }
        topRow.addView(titleTv)
        topRow.addView(timeTv)

        val detailTv = TextView(this).apply {
            text = entry.detail
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            setPadding(0, dp(4), 0, 0)
        }

        item.addView(topRow)
        item.addView(detailTv)
        return item
    }

    private fun createCard(
        strokeColorRes: Int = R.color.md_outline,
        strokeWidthDp: Int = 1
    ): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.md_surface))
            strokeColor = ContextCompat.getColor(this@MainActivity, strokeColorRes)
            strokeWidth = dp(strokeWidthDp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun createBadgeView(): TextView {
        return TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun applyBadgeStyle(tv: TextView, label: String, bgRes: Int, fgRes: Int) {
        tv.text = label
        tv.setTextColor(ContextCompat.getColor(this, fgRes))
        tv.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, bgRes))
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun createActionButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        GuardStateManager.removeListener(stateListener)
    }
}
