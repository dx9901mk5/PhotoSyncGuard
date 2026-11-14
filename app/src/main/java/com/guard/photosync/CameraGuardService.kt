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

class CameraGuardService : AccessibilityService() {
    companion object {
        private const val TAG = "CameraGuardService"
        const val ACTION_FORCE_RESTORE = "com.guard.photosync.ACTION_FORCE_RESTORE"

        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.oplus.camera",
            "com.oneplus.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.vivo.camera",
            "com.huawei.camera"
        )
        // 1MB 門檻 (1024 * 1024 bytes)
        private const val MIN_FINISHED_FILE_SIZE_BYTES = 1048576L
        // 安全兜底時間：若檔案一直 < 1MB，離開相機且無寫入達 35 秒後強制恢復網路（避免極端小圖導致永久斷網）
        private const val SAFETY_TIMEOUT_MS = 35000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isCameraInForeground = false
    private var hasUnfinishedSmallPhoto = false
    private var fileObserver: FileObserver? = null

    private val forceRestoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_RESTORE) {
                handler.removeCallbacks(restoreNetworkRunnable)
                hasUnfinishedSmallPhoto = false
                isCameraInForeground = false
                Log.i(TAG, "收到手動防呆恢復指令，已清除計時器與小圖鎖定")
            }
        }
    }

    private val restoreNetworkRunnable = Runnable {
        if (!isCameraInForeground && ShizukuNetHelper.isBlocked) {
            val delaySec = GuardStateManager.getRestoreDelaySec(this)
            val reason = if (hasUnfinishedSmallPhoto) {
                "已達安全閒置時限（35秒無寫入），強制恢復 Google 相簿網路"
            } else {
                "相機算圖完成並已離開相機（延遲 ${delaySec} 秒緩衝），恢復 Google 相簿網路"
            }
            Log.i(TAG, reason)
            hasUnfinishedSmallPhoto = false
            ShizukuNetHelper.setPhotosNetworkBlocked(this, false, reason)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "無障礙服務已啟動，開始監控相機與檔案大小門檻")
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
            "開始監控前景相機與 /DCIM/Camera/ 檔案大小（門檻 1MB）"
        )
        startDcimObserver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return
            if (pkgName == "com.android.systemui" || pkgName == packageName) return
            if (CAMERA_PACKAGES.contains(pkgName)) {
                isCameraInForeground = true
                handler.removeCallbacks(restoreNetworkRunnable)
                if (!ShizukuNetHelper.isBlocked) {
                    Log.i(TAG, "偵測到相機開啟 ($pkgName)，立即切斷 Google 相簿網路")
                    ShizukuNetHelper.setPhotosNetworkBlocked(this, true, "偵測到相機開啟 ($pkgName)")
                }
            } else {
                if (isCameraInForeground) {
                    isCameraInForeground = false
                    Log.i(TAG, "已離開相機，目前是否有未完成小圖鎖定: $hasUnfinishedSmallPhoto")
                    scheduleRestoreCheck()
                }
            }
        }
    }

    private fun startDcimObserver() {
        // 僅監控相機專屬目錄，天然排除 Screenshots 截圖目錄
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!cameraDir.exists()) return
        @Suppress("DEPRECATION")
        fileObserver = object : FileObserver(
            cameraDir.absolutePath,
            CREATE or CLOSE_WRITE or MOVED_TO or MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                // 忽略系統正在寫入的隱藏暫存檔（通常以 . 開頭或包含 pending）
                if (path.startsWith(".") || path.contains("pending", ignoreCase = true)) {
                    ensureNetworkBlocked("偵測到相機暫存檔寫入: $path")
                    return
                }
                // 僅針對照片格式進行大小檢查（排除 mp4 錄影檔以免長影片誤判）
                val lowerPath = path.lowercase()
                if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".heic")) {
                    val file = File(cameraDir, path)
                    if (file.exists()) {
                        val fileSize = file.length()
                        val sizeKb = fileSize / 1024
                        if (fileSize in 1 until MIN_FINISHED_FILE_SIZE_BYTES) {
                            // 檔案小於 1MB：認定為尚未算完的低畫質預覽圖
                            if (!hasUnfinishedSmallPhoto) {
                                GuardStateManager.addLog(
                                    this@CameraGuardService,
                                    GuardStateManager.LogCategory.PHOTO_WAIT,
                                    "偵測到未算完小圖 (< 1MB)",
                                    "$path (${sizeKb} KB)，強制保持斷網等待算圖"
                                )
                            }
                            hasUnfinishedSmallPhoto = true
                            ensureNetworkBlocked("偵測到小於 1MB 的未算完照片 ($path, ${sizeKb} KB)")
                        } else if (fileSize >= MIN_FINISHED_FILE_SIZE_BYTES) {
                            // 檔案 >= 1MB：認定為算圖完成並已覆寫為完整成片
                            if (hasUnfinishedSmallPhoto) {
                                Log.i(TAG, "照片已算圖完成並覆寫 ($path, ${sizeKb} KB)，解除小圖鎖定")
                                GuardStateManager.addLog(
                                    this@CameraGuardService,
                                    GuardStateManager.LogCategory.PHOTO_READY,
                                    "判讀照片算圖完成 (>= 1MB)",
                                    "$path (${sizeKb} KB)，準備恢復 Google 相簿網路"
                                )
                            }
                            hasUnfinishedSmallPhoto = false
                            if (ShizukuNetHelper.isBlocked || isCameraInForeground) {
                                scheduleRestoreCheck()
                            }
                        }
                    }
                }
            }
        }
        fileObserver?.startWatching()
    }

    private fun ensureNetworkBlocked(reason: String) {
        Log.d(TAG, reason)
        if (!ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(this, true, reason)
        } else {
            GuardStateManager.notifyDelaying(this, reason)
        }
        scheduleRestoreCheck()
    }

    private fun scheduleRestoreCheck() {
        handler.removeCallbacks(restoreNetworkRunnable)
        if (isCameraInForeground) return
        val delay = if (hasUnfinishedSmallPhoto) {
            SAFETY_TIMEOUT_MS
        } else {
            GuardStateManager.getRestoreDelaySec(this) * 1000L
        }
        handler.postDelayed(restoreNetworkRunnable, delay)
    }

    override fun onInterrupt() {
        fileObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(forceRestoreReceiver) }
        fileObserver?.stopWatching()
        handler.removeCallbacks(restoreNetworkRunnable)
        if (ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(this, false, "無障礙服務停止，自動恢復網路")
        }
    }
}
