package com.guard.photosync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuNetHelper {
    private const val TAG = "ShizukuNetHelper"
    const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

    data class GrantReport(
        val mediaGranted: Boolean,
        val notificationGranted: Boolean,
        val accessibilityGranted: Boolean,
        val batteryWhitelistGranted: Boolean
    )

    @Volatile
    var isBlocked: Boolean = false
        private set

    private fun getPhotosUid(context: Context): Int? {
        return try {
            context.packageManager.getApplicationInfo(PHOTOS_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "找不到 Google 相簿套件")
            null
        }
    }

    fun setPhotosNetworkBlocked(
        context: Context,
        block: Boolean,
        reason: String = if (block) "自動算圖保護" else "恢復網路連線"
    ): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku 未啟動或無權限")
            GuardStateManager.addLog(
                context,
                if (block) GuardStateManager.LogCategory.BLOCK_FAIL else GuardStateManager.LogCategory.RESTORE_FAIL,
                if (block) "阻斷網路失敗" else "恢復網路失敗",
                "Shizuku 未啟動或未授權（原因：$reason）"
            )
            return false
        }

        val uid = getPhotosUid(context)
        if (uid == null) {
            GuardStateManager.addLog(
                context,
                if (block) GuardStateManager.LogCategory.BLOCK_FAIL else GuardStateManager.LogCategory.RESTORE_FAIL,
                if (block) "阻斷網路失敗" else "恢復網路失敗",
                "找不到 Google 相簿套件（$PHOTOS_PACKAGE）"
            )
            return false
        }

        val cmd = if (block) {
            "cmd connectivity set-chain3-enabled true && cmd connectivity set-package-networking-enabled false $PHOTOS_PACKAGE"
        } else {
            "cmd connectivity set-package-networking-enabled true $PHOTOS_PACKAGE"
        }

        val (success, errMsg) = executeShizukuShell(cmd)
        if (success) {
            isBlocked = block
            if (block) {
                Log.i(TAG, "已阻斷 Google 相簿網路 (UID: $uid)")
                GuardStateManager.addLog(
                    context,
                    GuardStateManager.LogCategory.BLOCK_OK,
                    "成功阻斷 Google 相簿網路",
                    "$reason (UID: $uid)"
                )
                GuardStateManager.notifyDelaying(context, reason)
            } else {
                Log.i(TAG, "已恢復 Google 相簿網路 (UID: $uid)")
                GuardStateManager.addLog(
                    context,
                    GuardStateManager.LogCategory.RESTORE_OK,
                    "成功恢復 Google 相簿網路",
                    "$reason (UID: $uid)"
                )
                GuardStateManager.notifyRestored(context, reason)
            }
        } else {
            GuardStateManager.addLog(
                context,
                if (block) GuardStateManager.LogCategory.BLOCK_FAIL else GuardStateManager.LogCategory.RESTORE_FAIL,
                if (block) "阻斷 Google 相簿網路失敗" else "恢復 Google 相簿網路失敗",
                "Shell 指令異常：$errMsg"
            )
        }
        GuardStateManager.notifyListeners()
        return success
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val pkg = context.packageName
        val fullComponent = "$pkg/${CameraGuardService::class.java.name}"
        val shortComponent = "$pkg/.${CameraGuardService::class.java.simpleName}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(fullComponent, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true)
        }
    }

    fun grantAllPermissionsViaShizuku(context: Context): GrantReport? {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val pkg = context.packageName

        // 1. 媒體讀取權限
        val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "android.permission.READ_MEDIA_IMAGES"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        val (mediaOk, _) = executeShizukuShell("pm grant $pkg $mediaPerm")

        // 2. 通知權限 (Android 13+)
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            executeShizukuShell("pm grant $pkg android.permission.POST_NOTIFICATIONS").first
        } else {
            true
        }

        // 3. 解除側載受限設定並啟用無障礙服務（保留既有其他服務）
        executeShizukuShell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        val component = "$pkg/${CameraGuardService::class.java.name}"
        val (readOk, currentServices, _) = executeShizukuShellWithOutput(
            "settings get secure enabled_accessibility_services"
        )
        val a11yOk = if (readOk) {
            val cleaned = currentServices.trim().let { if (it == "null") "" else it }
            val parts = cleaned.split(':').filter { it.isNotBlank() }.toMutableList()
            if (parts.none { it.equals(component, ignoreCase = true) || it.equals("$pkg/.${CameraGuardService::class.java.simpleName}", ignoreCase = true) }) {
                parts.add(component)
            }
            val joined = parts.joinToString(":")
            val putOk = executeShizukuShell("settings put secure enabled_accessibility_services \"$joined\"").first
            val enableOk = executeShizukuShell("settings put secure accessibility_enabled 1").first
            (putOk && enableOk) || isAccessibilityEnabled(context)
        } else {
            isAccessibilityEnabled(context)
        }

        // 4. 電池優化白名單與背景執行許可
        val batteryOk = executeShizukuShell("dumpsys deviceidle whitelist +$pkg").first
        executeShizukuShell("cmd appops set $pkg RUN_IN_BACKGROUND allow")
        executeShizukuShell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")

        val report = GrantReport(
            mediaGranted = mediaOk,
            notificationGranted = notifOk,
            accessibilityGranted = a11yOk,
            batteryWhitelistGranted = batteryOk
        )
        val mediaSymbol = if (mediaOk) "✅" else "❌"
        val notifSymbol = if (notifOk) "✅" else "❌"
        val a11ySymbol = if (a11yOk) "✅" else "⚠️需手動"
        val batterySymbol = if (batteryOk) "✅" else "⚠️"
        GuardStateManager.addLog(
            context,
            GuardStateManager.LogCategory.INFO,
            "Shizuku 一鍵授權執行完成",
            "媒體讀取=$mediaSymbol, 通知=$notifSymbol, 無障礙=$a11ySymbol, 電池白名單=$batterySymbol"
        )
        return report
    }

    private fun executeShizukuShell(command: String): Pair<Boolean, String> {
        val (ok, _, err) = executeShizukuShellWithOutput(command)
        return Pair(ok, err)
    }

    private fun executeShizukuShellWithOutput(command: String): Triple<Boolean, String, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "指令執行異常 ($exitCode): $err")
                val errMsgText = err.ifEmpty { "無錯誤訊息" }
                Triple(false, out, "exit=$exitCode $errMsgText")
            } else {
                Triple(true, out, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 執行失敗", e)
            Triple(false, "", e.message ?: "未知例外")
        }
    }
}
