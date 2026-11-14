package com.guard.photosync

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val GITHUB_REPO_URL = "https://github.com/dx9901mk5/PhotoSyncGuard"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/dx9901mk5/PhotoSyncGuard/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val hasUpdate: Boolean,
        val releasePageUrl: String,
        val apkDownloadUrl: String?,
        val releaseNotes: String
    )

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdate(
        context: Context,
        onResult: (Result<UpdateInfo>) -> Unit
    ) {
        val currentVersion = getCurrentVersionName(context)
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "PhotoSyncGuard/$currentVersion")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    mainHandler.post {
                        onResult(Result.failure(RuntimeException("GitHub API 回應狀態碼：$code")))
                    }
                    return@Thread
                }

                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val latestVersion = tagName.removePrefix("v").removePrefix("V")
                val htmlUrl = json.optString("html_url", "$GITHUB_REPO_URL/releases")
                val notes = json.optString("body", "")

                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", null)
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(latestVersion, currentVersion)
                val info = UpdateInfo(
                    latestVersion = latestVersion,
                    currentVersion = currentVersion,
                    hasUpdate = hasUpdate,
                    releasePageUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    releaseNotes = notes
                )
                mainHandler.post { onResult(Result.success(info)) }
            } catch (e: Exception) {
                mainHandler.post { onResult(Result.failure(e)) }
            }
        }.start()
    }

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
