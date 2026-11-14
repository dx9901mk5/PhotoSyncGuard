package com.guard.photosync

import android.accessibilityservice.AccessibilityService
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
        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.oplus.camera",
            "com.oneplus.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera"
        )
        // 1MB 門檻 (1024 * 1024 bytes)
        private const val MIN_FINISHED_FILE_SIZE_BYTES = 1048576L
        // 算圖完成（檔案 >= 1MB）且離開相機後的緩衝時間（5秒）
        private const val NORMAL_DEBOUNCE_MS = 5000L
        // 安全兜底時間：若檔案一直 < 1MB，離開相機且無寫入達 35 秒後強制恢復網路（避免極端小圖導致永久斷網）
        private const val SAFETY_TIMEOUT_MS = 35000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isCameraInForeground = false
    private var hasUnfinishedSmallPhoto = false
    private var fileObserver: FileObserver? = null

    private val restoreNetworkRunnable = Runnable {
        if (!isCameraInForeground && ShizukuNetHelper.isBlocked) {
            Log.i(TAG, "滿足恢復條件（成片已達標或已達安全閒置時限），恢復 Google 相簿網路")
            hasUnfinishedSmallPhoto = false
            ShizukuNetHelper.setPhotosNetworkBlocked(this, false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "無障礙服務已啟動，開始監控相機與檔案大小門檻")
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
                    ShizukuNetHelper.setPhotosNetworkBlocked(this, true)
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
                        if (fileSize in 1 until MIN_FINISHED_FILE_SIZE_BYTES) {
                            // 檔案小於 1MB：認定為尚未算完的低畫質預覽圖
                            hasUnfinishedSmallPhoto = true
                            ensureNetworkBlocked("偵測到小於 1MB 的未算完照片 ($path, ${fileSize / 1024} KB)，強制保持斷網")
                        } else if (fileSize >= MIN_FINISHED_FILE_SIZE_BYTES) {
                            // 檔案 >= 1MB：認定為算圖完成並已覆寫為完整成片
                            if (hasUnfinishedSmallPhoto) {
                                Log.i(TAG, "照片已算圖完成並覆寫 ($path, ${fileSize / 1024} KB)，解除小圖鎖定")
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
            ShizukuNetHelper.setPhotosNetworkBlocked(this, true)
        }
        scheduleRestoreCheck()
    }

    private fun scheduleRestoreCheck() {
        handler.removeCallbacks(restoreNetworkRunnable)
        if (isCameraInForeground) return
        val delay = if (hasUnfinishedSmallPhoto) SAFETY_TIMEOUT_MS else NORMAL_DEBOUNCE_MS
        handler.postDelayed(restoreNetworkRunnable, delay)
    }

    override fun onInterrupt() {
        fileObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        handler.removeCallbacks(restoreNetworkRunnable)
        if (ShizukuNetHelper.isBlocked) {
            ShizukuNetHelper.setPhotosNetworkBlocked(this, false)
        }
    }
}
