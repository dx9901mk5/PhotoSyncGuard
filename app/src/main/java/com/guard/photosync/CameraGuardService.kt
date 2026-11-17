package com.guard.photosync

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CameraGuardService : AccessibilityService() {
    companion object {
        private const val TAG = "CameraGuardService"
        const val ACTION_FORCE_RESTORE = "com.guard.photosync.ACTION_FORCE_RESTORE"

        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.oplus.camera",
            "com.oppo.camera",
            "com.oneplus.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.vivo.camera",
            "com.miui.camera",
            "com.xiaomi.camera",
            "com.huawei.camera"
        )

        // 1MB 門檻 (1024 * 1024 bytes)
        private const val MIN_FINISHED_FILE_SIZE_BYTES = 1048576L
        // 強制最低等待驗證門檻：不論如何至少滿 30 秒才開始驗證算圖結果
        private const val MIN_WAIT_BEFORE_VERIFY_MS = 30_000L
        // 驗證未通過時的再次檢查間隔（30秒）
        private const val REVERIFY_INTERVAL_MS = 30_000L
        // 極端情況安全兜底上限（10分鐘無任何寫入才強制釋放避免永久死鎖）
        private const val SAFETY_MAX_IDLE_MS = 600_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isCameraInForeground = false
    @Volatile
    private var sessionStartTimeMs = 0L
    @Volatile
    private var lastCameraExitTimeMs = 0L
    @Volatile
    private var lastFileEventTimeMs = 0L

    private val sessionPhotoNames = ConcurrentHashMap.newKeySet<String>()
    private val loggedShotFiles = ConcurrentHashMap<String, Long>()
    private var fileObserver: FileObserver? = null

    private val forceRestoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_RESTORE) {
                handler.removeCallbacks(verifyAndRestoreRunnable)
                isCameraInForeground = false
                resetSession()
                Log.i(TAG, "收到手動防呆恢復指令，已清除計時器與拍攝工作階段")
            }
        }
    }

    private val verifyAndRestoreRunnable: Runnable = object : Runnable {
        override fun run() {
            if (handleIfExpired()) return
            if (isCameraInForeground || !ShizukuNetHelper.isBlocked) return

            val now = System.currentTimeMillis()
            val delaySec = GuardStateManager.getRestoreDelaySec(this@CameraGuardService)
            val requiredWaitMs = maxOf(delaySec * 1000L, MIN_WAIT_BEFORE_VERIFY_MS)
            val lastActivityMs = maxOf(lastCameraExitTimeMs, lastFileEventTimeMs)
            val quietElapsedMs = now - lastActivityMs

            // 第一關：只要拍照或剛離開相機，必須先完整靜置滿設定秒數（至少 30 秒起跳）才開始驗證
            if (quietElapsedMs < requiredWaitMs) {
                val remainingMs = (requiredWaitMs - quietElapsedMs).coerceAtLeast(1000L)
                Log.i(TAG, "距離最後拍照/離開相機尚未滿 ${delaySec} 秒，繼續等待 ${remainingMs / 1000} 秒後再驗證")
                GuardStateManager.notifyDelaying(
                    this@CameraGuardService,
                    "已鎖定斷網，等待滿 ${delaySec} 秒後開始驗證照片（剩餘 ${remainingMs / 1000} 秒）"
                )
                handler.postDelayed(this, remainingMs)
                return
            }

            // 第二關：已靜置滿 30 秒以上，開始全面驗證本次拍攝工作階段的所有檔案
            val verification = verifyAllSessionPhotosFinished(now)
            val idleSinceLastFileMs = now - lastFileEventTimeMs

            if (!verification.allFinished && idleSinceLastFileMs < SAFETY_MAX_IDLE_MS) {
                val detail = "等待滿 ${delaySec} 秒後驗證未通過：${verification.unfinishedReason}，繼續保持斷網並於 30 秒後重新驗證"
                Log.i(TAG, detail)
                GuardStateManager.addLog(
                    this@CameraGuardService,
                    GuardStateManager.LogCategory.PHOTO_WAIT,
                    "驗證尚未全數完成，繼續延長阻斷",
                    detail
                )
                GuardStateManager.notifyDelaying(
                    this@CameraGuardService,
                    "驗證發現未算完檔案 (${verification.unfinishedReason})，繼續阻斷 Google 相簿網路…"
                )
                handler.postDelayed(this, REVERIFY_INTERVAL_MS)
                return
            }

            // 第三關：全部照片驗證完畢（或已達 10 分鐘極端兜底上限），正式開放 Google 相簿網路
            val reason = if (!verification.allFinished) {
                "已達極端安全上限（10分鐘無寫入），強制恢復 Google 相簿網路"
            } else {
                "已等待滿 ${delaySec} 秒並驗證全部照片算圖完成（共 ${verification.verifiedPhotoCount} 張成片、無暫存檔），開放 Google 相簿網路上傳"
            }
            Log.i(TAG, reason)
            GuardStateManager.addLog(
                this@CameraGuardService,
                GuardStateManager.LogCategory.PHOTO_READY,
                "全部照片驗證通過",
                "共驗證 ${verification.verifiedPhotoCount} 張照片 (>= 1MB 且無暫存檔)"
            )
            resetSession()
            ShizukuNetHelper.setPhotosNetworkBlocked(this@CameraGuardService, false, reason)
        }
    }

    private data class VerificationResult(
        val allFinished: Boolean,
        val verifiedPhotoCount: Int,
        val unfinishedReason: String = ""
    )

    private fun resetSession() {
        sessionStartTimeMs = 0L
        sessionPhotoNames.clear()
        loggedShotFiles.clear()
    }

    private fun handleIfExpired(): Boolean {
        if (!GuardStateManager.isAppExpired()) return false
        handler.removeCallbacks(verifyAndRestoreRunnable)
        fileObserver?.stopWatching()
        isCameraInForeground = false
        resetSession()
        if (ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(
                this,
                false,
                "App expired after ${GuardStateManager.EXPIRY_DATE_DISPLAY}"
            )
        }
        return true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (handleIfExpired()) {
            Log.w(TAG, "版本已超過使用期限 (${GuardStateManager.EXPIRY_DATE_DISPLAY})，停用守護服務")
            disableSelf()
            return
        }
        Log.i(TAG, "無障礙服務已啟動，開始監控相機與拍照工作階段（至少 30 秒後才驗證）")
        val filter = IntentFilter(ACTION_FORCE_RESTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceRestoreReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(forceRestoreReceiver, filter)
        }
        GuardStateManager.addLog(
            this,
            GuardStateManager.LogCategory.INFO,
            "守護服務已啟動",
            "只要拍照即鎖定斷網，至少等待 30 秒以上才開始驗證全部照片是否算圖完畢"
        )
        startDcimObserver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (handleIfExpired()) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return
            if (pkgName == "com.android.systemui" || pkgName == packageName) return
            if (CAMERA_PACKAGES.contains(pkgName)) {
                val now = System.currentTimeMillis()
                isCameraInForeground = true
                if (sessionStartTimeMs == 0L) {
                    sessionStartTimeMs = now
                }
                handler.removeCallbacks(verifyAndRestoreRunnable)
                if (!ShizukuNetHelper.isBlocked) {
                    Log.i(TAG, "偵測到相機開啟 ($pkgName)，立即切斷 Google 相簿網路")
                    ShizukuNetHelper.setPhotosNetworkBlocked(this, true, "偵測到相機開啟 ($pkgName)")
                } else {
                    GuardStateManager.notifyDelaying(this, "偵測到使用相機中，持續阻斷 Google 相簿網路並重置驗證計時")
                }
            } else {
                if (isCameraInForeground) {
                    isCameraInForeground = false
                    lastCameraExitTimeMs = System.currentTimeMillis()
                    val delaySec = GuardStateManager.getRestoreDelaySec(this)
                    Log.i(TAG, "已離開相機，將先等待至少 ${delaySec} 秒後再開始驗證全部照片")
                    scheduleVerifyAfterWait()
                }
            }
        }
    }

    private fun startDcimObserver() {
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!cameraDir.exists()) return
        @Suppress("DEPRECATION")
        fileObserver = object : FileObserver(
            cameraDir.absolutePath,
            CREATE or CLOSE_WRITE or MOVED_TO or MODIFY or DELETE or MOVED_FROM
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (handleIfExpired()) return
                if (path == null) return
                val now = System.currentTimeMillis()
                lastFileEventTimeMs = now
                if (sessionStartTimeMs == 0L) {
                    sessionStartTimeMs = now
                }

                val lowerPath = path.lowercase()
                val isTempFile = path.startsWith(".") || lowerPath.contains("pending") || lowerPath.endsWith(".tmp")
                val isPhotoFile = lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") ||
                    lowerPath.endsWith(".heic") || lowerPath.endsWith(".dng")

                if (!isTempFile && !isPhotoFile) return

                // 不論檔案大小為何、不論是否為暫存檔，只要偵測到拍照或照片異動，一律立即切斷網路並重新起算 30 秒以上等待期！
                if ((event and (CREATE or MODIFY or CLOSE_WRITE or MOVED_TO)) != 0) {
                    if (isPhotoFile && !isTempFile) {
                        sessionPhotoNames.add(path)
                    }
                    val delaySec = GuardStateManager.getRestoreDelaySec(this@CameraGuardService)
                    val lastLogged = loggedShotFiles[path] ?: 0L
                    if (now - lastLogged > 8000L) {
                        loggedShotFiles[path] = now
                        val file = File(cameraDir, path)
                        val sizeInfo = if (file.exists()) "${file.length() / 1024} KB" else "寫入中"
                        GuardStateManager.addLog(
                            this@CameraGuardService,
                            GuardStateManager.LogCategory.PHOTO_WAIT,
                            "偵測到拍照寫入，重新起算 ${delaySec} 秒等待期",
                            "$path ($sizeInfo) — 期間嚴格禁止 Google 相簿聯網，滿 ${delaySec} 秒後才進行驗證"
                        )
                    }
                    ensureNetworkBlocked("偵測到拍照寫入 ($path)，鎖定斷網並等待至少 ${delaySec} 秒後驗證")
                } else {
                    // DELETE 或 MOVED_FROM（例如暫存檔被清除），同樣重新起算等待期確保穩定
                    scheduleVerifyAfterWait()
                }
            }
        }
        fileObserver?.startWatching()
    }

    private fun verifyAllSessionPhotosFinished(now: Long): VerificationResult {
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        val files = cameraDir.listFiles() ?: return VerificationResult(true, 0)
        val sessionCutoff = if (sessionStartTimeMs > 0L) sessionStartTimeMs - 2000L else now - 300_000L
        var verifiedCount = 0

        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            val lower = name.lowercase()
            val mod = file.lastModified()

            // 1. 檢查是否存在任何本次拍攝期間（或近 10 分鐘內）的暫存檔
            if ((name.startsWith(".pending") || lower.contains("pending") || lower.endsWith(".tmp")) &&
                (mod >= sessionCutoff || now - mod < SAFETY_MAX_IDLE_MS)
            ) {
                return VerificationResult(false, verifiedCount, "暫存檔尚未清除: $name")
            }

            // 2. 檢查所有本次拍攝期間新增/修改的照片，以及被追蹤清單內的照片
            val isPhoto = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".heic")
            if (isPhoto && (mod >= sessionCutoff || sessionPhotoNames.contains(name))) {
                val len = file.length()
                if (len < MIN_FINISHED_FILE_SIZE_BYTES) {
                    return VerificationResult(false, verifiedCount, "照片尚未算完 (< 1MB): $name (${len / 1024} KB)")
                }
                if (now - mod < MIN_WAIT_BEFORE_VERIFY_MS) {
                    return VerificationResult(
                        false,
                        verifiedCount,
                        "照片剛被算圖覆寫未滿 30 秒: $name (${(now - mod) / 1000}s)"
                    )
                }
                verifiedCount++
            }
        }
        return VerificationResult(true, verifiedCount)
    }

    private fun ensureNetworkBlocked(reason: String) {
        Log.d(TAG, reason)
        if (!ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(this, true, reason)
        } else {
            GuardStateManager.notifyDelaying(this, reason)
        }
        scheduleVerifyAfterWait()
    }

    private fun scheduleVerifyAfterWait() {
        handler.removeCallbacks(verifyAndRestoreRunnable)
        if (isCameraInForeground) return
        val delaySec = GuardStateManager.getRestoreDelaySec(this)
        val waitMs = maxOf(delaySec * 1000L, MIN_WAIT_BEFORE_VERIFY_MS)
        handler.postDelayed(verifyAndRestoreRunnable, waitMs)
    }

    override fun onInterrupt() {
        fileObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(forceRestoreReceiver) }
        fileObserver?.stopWatching()
        handler.removeCallbacks(verifyAndRestoreRunnable)
        if (ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(this, false, "無障礙服務停止，自動恢復網路")
        }
    }
}
