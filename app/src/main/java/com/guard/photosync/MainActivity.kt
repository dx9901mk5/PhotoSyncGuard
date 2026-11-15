package com.guard.photosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuBadge: TextView
    private lateinit var networkBadge: TextView
    private lateinit var notifySwitch: MaterialSwitch
    private lateinit var delayValueText: TextView
    private lateinit var delaySlider: Slider
    private lateinit var permShizukuStatus: TextView
    private lateinit var permMediaStatus: TextView
    private lateinit var permA11yStatus: TextView
    private lateinit var permBatteryStatus: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var logCountText: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        updateUI()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.toast_shizuku_granted), Toast.LENGTH_SHORT).show()
            runShizukuGrantAll()
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
            title = getString(R.string.app_name)
            subtitle = getString(R.string.toolbar_subtitle)
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

        content.addView(buildFailsafeCard())
        content.addView(buildStatusAndNotifyCard())
        content.addView(buildSetupCard())
        content.addView(buildAboutAndUpdateCard())
        content.addView(buildLogCard())

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)

        checkAppUpdate(manual = false)
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
            text = getString(R.string.failsafe_title)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val desc = TextView(this).apply {
            text = getString(R.string.failsafe_desc)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, dp(4), 0, dp(12))
        }
        val btnFailsafe = MaterialButton(this).apply {
            text = getString(R.string.failsafe_btn)
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
                    getString(R.string.failsafe_reason)
                )
                GuardStateManager.addLog(
                    this@MainActivity,
                    if (ok) GuardStateManager.LogCategory.MANUAL_RESTORE else GuardStateManager.LogCategory.RESTORE_FAIL,
                    getString(if (ok) R.string.failsafe_log_ok_title else R.string.failsafe_log_fail_title),
                    getString(if (ok) R.string.failsafe_log_ok_detail else R.string.failsafe_log_fail_detail)
                )
                Toast.makeText(
                    this@MainActivity,
                    getString(if (ok) R.string.failsafe_toast_ok else R.string.failsafe_toast_fail),
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
            text = getString(R.string.status_notify_title)
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
            text = getString(R.string.notify_switch_title)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val switchSub = TextView(this).apply {
            text = getString(R.string.notify_switch_sub)
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

        val delaySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        val delayTitle = TextView(this).apply {
            text = getString(R.string.delay_title)
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
            valueFrom = 30f
            valueTo = 180f
            stepSize = 5f
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
        for (sec in listOf(30, 45, 60, 120)) {
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (sec == 30) {
                    getString(R.string.delay_preset_default, sec)
                } else {
                    getString(R.string.delay_preset, sec)
                }
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
            text = getString(R.string.delay_note)
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
            text = getString(R.string.setup_title)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
            setPadding(0, 0, 0, dp(8))
        }

        val hint = TextView(this).apply {
            text = getString(R.string.setup_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, 0, 0, dp(12))
        }

        val btnGrantAllShizuku = MaterialButton(this).apply {
            text = getString(R.string.btn_grant_all_shizuku)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.md_primary_container)
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_primary_container))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_shizuku_not_running), Toast.LENGTH_SHORT).show()
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(100)
                } else {
                    runShizukuGrantAll()
                }
            }
        }

        val (rowShizuku, statusShizuku) = createPermissionRow(
            getString(R.string.perm_row_shizuku_title),
            getString(R.string.perm_action_grant)
        ) {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(100)
                } else {
                    Toast.makeText(this, getString(R.string.toast_shizuku_already), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_shizuku_not_running), Toast.LENGTH_SHORT).show()
            }
        }
        permShizukuStatus = statusShizuku

        val (rowMedia, statusMedia) = createPermissionRow(
            getString(R.string.perm_row_media_title),
            getString(R.string.perm_action_grant)
        ) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
        }
        permMediaStatus = statusMedia

        val (rowA11y, statusA11y) = createPermissionRow(
            getString(R.string.perm_row_a11y_title),
            getString(R.string.perm_action_settings)
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permA11yStatus = statusA11y

        val (rowBattery, statusBattery) = createPermissionRow(
            getString(R.string.perm_row_battery_title),
            getString(R.string.perm_action_settings)
        ) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        permBatteryStatus = statusBattery

        val btnTestBlock = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.btn_test_block)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                ShizukuNetHelper.setPhotosNetworkBlocked(
                    this@MainActivity,
                    true,
                    getString(R.string.test_block_reason)
                )
                updateUI()
            }
        }

        inner.addView(header)
        inner.addView(hint)
        inner.addView(btnGrantAllShizuku)
        inner.addView(rowShizuku)
        inner.addView(createDivider())
        inner.addView(rowMedia)
        inner.addView(createDivider())
        inner.addView(rowA11y)
        inner.addView(createDivider())
        inner.addView(rowBattery)
        inner.addView(btnTestBlock)
        card.addView(inner)
        return card
    }

    private fun runShizukuGrantAll() {
        Thread {
            val report = ShizukuNetHelper.grantAllPermissionsViaShizuku(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (report == null) {
                    Toast.makeText(this, getString(R.string.toast_shizuku_not_running), Toast.LENGTH_SHORT).show()
                } else {
                    val mark = { ok: Boolean -> if (ok) "✅" else "⚠️" }
                    Toast.makeText(
                        this,
                        getString(
                            R.string.toast_grant_all_result,
                            mark(report.mediaGranted),
                            mark(report.notificationGranted),
                            mark(report.accessibilityGranted),
                            mark(report.batteryWhitelistGranted)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateUI()
            }
        }.start()
    }

    private fun buildAboutAndUpdateCard(): MaterialCardView {
        val card = createCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val currentVersion = UpdateChecker.getCurrentVersionName(this)
        val header = TextView(this).apply {
            text = getString(R.string.about_update_title)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val versionDesc = TextView(this).apply {
            text = getString(R.string.about_version_fmt, currentVersion)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, dp(4), 0, dp(12))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnAbout = MaterialButton(this).apply {
            text = getString(R.string.btn_about)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { showAboutDialog() }
        }
        val btnCheckUpdate = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.btn_check_update)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { checkAppUpdate(manual = true) }
        }

        btnRow.addView(btnAbout)
        btnRow.addView(btnCheckUpdate)

        inner.addView(header)
        inner.addView(versionDesc)
        inner.addView(btnRow)
        card.addView(inner)
        return card
    }

    private fun showAboutDialog() {
        val currentVersion = UpdateChecker.getCurrentVersionName(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_dialog_title, currentVersion))
            .setMessage(getString(R.string.about_dialog_msg, UpdateChecker.GITHUB_REPO_URL))
            .setPositiveButton(getString(R.string.btn_open_github)) { _, _ ->
                openUrl(UpdateChecker.GITHUB_REPO_URL)
            }
            .setNeutralButton(getString(R.string.btn_check_update)) { _, _ ->
                checkAppUpdate(manual = true)
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, getString(R.string.toast_checking_update), Toast.LENGTH_SHORT).show()
        }
        UpdateChecker.checkForUpdate(this) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            result.onSuccess { info ->
                if (info.hasUpdate) {
                    val targetUrl = info.apkDownloadUrl ?: info.releasePageUrl
                    val notes = info.releaseNotes.ifBlank { getString(R.string.update_default_notes) }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.update_found_title, info.latestVersion))
                        .setMessage(getString(R.string.update_found_msg, info.currentVersion, info.latestVersion, notes))
                        .setPositiveButton(getString(R.string.btn_download_apk)) { _, _ ->
                            openUrl(targetUrl)
                        }
                        .setNeutralButton(getString(R.string.btn_view_release)) { _, _ ->
                            openUrl(info.releasePageUrl)
                        }
                        .setNegativeButton(getString(R.string.btn_later), null)
                        .show()
                } else if (manual) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_up_to_date, info.currentVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                if (manual) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_update_failed, err.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.toast_cannot_open_url, url), Toast.LENGTH_SHORT).show()
        }
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
            text = getString(R.string.log_card_title)
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
            text = getString(R.string.btn_clear)
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
            getString(if (shizukuReady) R.string.badge_shizuku_ok else R.string.badge_shizuku_no),
            if (shizukuReady) R.color.status_safe_bg else R.color.status_error_bg,
            if (shizukuReady) R.color.status_safe_fg else R.color.status_error_fg
        )

        val blocked = ShizukuNetHelper.isBlocked
        applyBadgeStyle(
            networkBadge,
            getString(if (blocked) R.string.badge_net_blocked else R.string.badge_net_normal),
            if (blocked) R.color.status_blocked_bg else R.color.status_safe_bg,
            if (blocked) R.color.status_blocked_fg else R.color.status_safe_fg
        )

        val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val mediaReady = ContextCompat.checkSelfPermission(this, mediaPerm) == PackageManager.PERMISSION_GRANTED
        val a11yReady = ShizukuNetHelper.isAccessibilityEnabled(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryReady = pm.isIgnoringBatteryOptimizations(packageName)

        updatePermStatusView(permShizukuStatus, shizukuReady)
        updatePermStatusView(permMediaStatus, mediaReady)
        updatePermStatusView(permA11yStatus, a11yReady)
        updatePermStatusView(permBatteryStatus, batteryReady)

        notifySwitch.isChecked = GuardStateManager.isNotificationEnabled(this)

        val delaySec = GuardStateManager.getRestoreDelaySec(this)
        delayValueText.text = getString(R.string.delay_value_fmt, delaySec)
        if (delaySlider.value.toInt() != delaySec) {
            delaySlider.value = delaySec.toFloat()
        }

        val logs = GuardStateManager.getLogs(this)
        logCountText.text = getString(R.string.log_count_fmt, logs.size)
        logContainer.removeAllViews()

        if (logs.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.log_empty)
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

    private fun createPermissionRow(
        titleText: String,
        actionText: String,
        onAction: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(this).apply {
            text = titleText
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val statusTv = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(titleTv)
        textCol.addView(statusTv)

        val actionBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = actionText
            textSize = 13f
            setOnClickListener { onAction() }
        }

        row.addView(textCol)
        row.addView(actionBtn)
        return row to statusTv
    }

    private fun updatePermStatusView(tv: TextView, granted: Boolean) {
        tv.text = getString(if (granted) R.string.perm_status_granted else R.string.perm_status_not_granted)
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                if (granted) R.color.status_safe_fg else R.color.md_on_surface_muted
            )
        )
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.md_outline))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
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
