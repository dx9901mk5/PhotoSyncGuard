package com.guard.photosync    private fun buildSetupCard(): MaterialCardView {
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

    private fun buildAboutAndUpdateCard(): MaterialCardView {
        val card = createCard()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val currentVersion = UpdateChecker.getCurrentVersionName(this)
        val header = TextView(this).apply {
            text = "關於與版本更新"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface))
        }
        val versionDesc = TextView(this).apply {
            text = "目前版本：v$currentVersion（開啟 App 時會自動檢查 GitHub 是否有新版 APK）"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_on_surface_muted))
            setPadding(0, dp(4), 0, dp(12))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnAbout = MaterialButton(this).apply {
            text = "關於我 / GitHub"
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
            text = "檢查 APK 更新"
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
            .setTitle("關於 PhotoSyncGuard v$currentVersion")
            .setMessage(
                """
                專為 OPPO、vivo 與小米（全球版 / CN 版 ROM）設計的免 Root 守護工具，防止 Google 相簿在相機算圖完成前搶先上傳低畫質半成品。
                
                PS：vivo 國際版 OriginOS 7（基於 Android 17）已內建修復，可忽略不使用本工具。
                
                專案 GitHub：
                ${UpdateChecker.GITHUB_REPO_URL}
                """.trimIndent()
            )
            .setPositiveButton("前往 GitHub") { _, _ ->
                openUrl(UpdateChecker.GITHUB_REPO_URL)
            }
            .setNeutralButton("檢查更新") { _, _ ->
                checkAppUpdate(manual = true)
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, "正在檢查 GitHub 最新版本…", Toast.LENGTH_SHORT).show()
        }
        UpdateChecker.checkForUpdate(this) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            result.onSuccess { info ->
                if (info.hasUpdate) {
                    val targetUrl = info.apkDownloadUrl ?: info.releasePageUrl
                    MaterialAlertDialogBuilder(this)
                        .setTitle("發現新版本 v${info.latestVersion}")
                        .setMessage(
                            "目前版本：v${info.currentVersion}\n最新版本：v${info.latestVersion}\n\n" +
                                (info.releaseNotes.ifBlank { "已有新版 APK 可直接覆蓋安裝更新。" })
                        )
                        .setPositiveButton("立即下載更新 APK") { _, _ ->
                            openUrl(targetUrl)
                        }
                        .setNeutralButton("查看發佈頁") { _, _ ->
                            openUrl(info.releasePageUrl)
                        }
                        .setNegativeButton("稍後再說", null)
                        .show()
                } else if (manual) {
                    Toast.makeText(this, "目前已是最新版本 (v${info.currentVersion})", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                if (manual) {
                    Toast.makeText(this, "檢查更新失敗：${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "無法開啟連結：$url", Toast.LENGTH_SHORT).show()
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
