package com.guard.photosync

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuNetHelper {
    private const val TAG = "ShizukuNetHelper"
    const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

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

    private fun executeShizukuShell(command: String): Pair<Boolean, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
                Log.e(TAG, "指令執行異常 ($exitCode): $err")
                Pair(false, "exit=$exitCode ${err.ifEmpty { "無錯誤訊息" }}")
            } else {
                Pair(true, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 執行失敗", e)
            Pair(false, e.message ?: "未知例外")
        }
    }
}
