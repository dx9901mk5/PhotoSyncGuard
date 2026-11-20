package com.guard.photosync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object GuardStateManager {
    private const val PREFS_NAME = "photosync_guard_prefs"
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"
    private const val KEY_RESTORE_DELAY_SEC = "restore_delay_sec"
    private const val DEFAULT_RESTORE_DELAY_SEC = 30
    private const val MIN_RESTORE_DELAY_SEC = 30
    private const val MAX_RESTORE_DELAY_SEC = 180
    private const val KEY_LOGS = "process_logs_24h"
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L

    private const val CHANNEL_ID = "guard_status_channel"
    private const val NOTIFICATION_ID = 1001
    const val EXPIRY_DATE_DISPLAY = "2026/12/01"

    /**
     * Returns true once local time reaches 2026-12-02 00:00:00 (valid through 2026-12-01).
     */
    fun isAppExpired(): Boolean {
        val expiryCutoff = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 2, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return System.currentTimeMillis() >= expiryCutoff
    }

    enum class LogCategory {
        BLOCK_OK,
        BLOCK_FAIL,
        PHOTO_WAIT,
        PHOTO_READY,
        RESTORE_OK,
        RESTORE_FAIL,
        MANUAL_RESTORE,
        INFO
    }

    data class LogEntry(
        val timestamp: Long,
        val category: LogCategory,
        val title: String,
        val detail: String
    ) {
        fun formattedTime(): String {
            val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyListeners() {
        mainHandler.post {
            for (listener in listeners) {
                listener()
            }
        }
    }

    fun isNotificationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIFY_ENABLED, true)
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIFY_ENABLED, enabled).apply()
        if (!enabled) {
            cancelNotifications(context)
        }
        notifyListeners()
    }

    fun getRestoreDelaySec(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_RESTORE_DELAY_SEC, DEFAULT_RESTORE_DELAY_SEC)
            .coerceIn(MIN_RESTORE_DELAY_SEC, MAX_RESTORE_DELAY_SEC)
    }

    fun setRestoreDelaySec(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(MIN_RESTORE_DELAY_SEC, MAX_RESTORE_DELAY_SEC)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_RESTORE_DELAY_SEC, clamped).apply()
        notifyListeners()
    }

    @Synchronized
    fun addLog(context: Context, category: LogCategory, title: String, detail: String) {
        val now = System.currentTimeMillis()
        val entries = loadAndPruneLocked(context, now).toMutableList()
        entries.add(0, LogEntry(now, category, title, detail))
        saveLogsLocked(context, entries)
        notifyListeners()
    }

    @Synchronized
    fun getLogs(context: Context): List<LogEntry> {
        val now = System.currentTimeMillis()
        val entries = loadAndPruneLocked(context, now)
        saveLogsLocked(context, entries)
        return entries
    }

    @Synchronized
    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LOGS).apply()
        notifyListeners()
    }

    private fun loadAndPruneLocked(context: Context, now: Long): List<LogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        val result = mutableListOf<LogEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ts = obj.getLong("ts")
                if (now - ts <= RETENTION_MS) {
                    val catName = obj.optString("cat", LogCategory.INFO.name)
                    val cat = runCatching { LogCategory.valueOf(catName) }.getOrDefault(LogCategory.INFO)
                    result.add(
                        LogEntry(
                            timestamp = ts,
                            category = cat,
                            title = obj.optString("title", ""),
                            detail = obj.optString("detail", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result
    }

    private fun saveLogsLocked(context: Context, entries: List<LogEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("ts", entry.timestamp)
            obj.put("cat", entry.category.name)
            obj.put("title", entry.title)
            obj.put("detail", entry.detail)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGS, arr.toString()).apply()
    }

    fun notifyDelaying(context: Context, reason: String) {
        if (!canPostNotification(context)) return
        ensureChannel(context)

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.notif_delay_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_delay_big_fmt, reason)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun notifyRestored(context: Context, reason: String) {
        if (!canPostNotification(context)) return
        ensureChannel(context)

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.notif_restored_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotifications(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!isNotificationEnabled(context)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
