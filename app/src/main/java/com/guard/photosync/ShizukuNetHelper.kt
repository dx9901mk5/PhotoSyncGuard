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

    fun setPhotosNetworkBlocked(context: Context, block: Boolean) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku 未啟動或無權限")
            return
        }
        val uid = getPhotosUid(context) ?: return
        val cmd = if (block) {
            "cmd connectivity set-chain3-enabled true && cmd connectivity set-package-networking-enabled false $PHOTOS_PACKAGE"
        } else {
            "cmd connectivity set-package-networking-enabled true $PHOTOS_PACKAGE"
        }
        val success = executeShizukuShell(cmd)
        if (success) {
            isBlocked = block
            Log.i(TAG, if (block) "已阻斷 Google 相簿網路 (UID: $uid)" else "已恢復 Google 相簿網路 (UID: $uid)")
        }
    }

    private fun executeShizukuShell(command: String): Boolean {
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
                val err = BufferedReader(InputStreamReader(process.errorStream)).readText()
                Log.e(TAG, "指令執行異常 ($exitCode): $err")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 執行失敗", e)
            false
        }
    }
}
