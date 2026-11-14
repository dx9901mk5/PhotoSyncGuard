package com.guard.photosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        updateStatus()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku 授權成功", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }

        val btnShizuku = Button(this).apply {
            text = "1. 授權 Shizuku"
            setOnClickListener {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        Shizuku.requestPermission(100)
                    } else {
                        Toast.makeText(this@MainActivity, "已取得 Shizuku 權限", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "請先啟動 Shizuku 服務", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStorage = Button(this).apply {
            text = "2. 授權相簿目錄監聽 (讀取圖片權限)"
            setOnClickListener {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(perm), 101)
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "3. 開啟無障礙服務 (偵測相機)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnTestBlock = Button(this).apply {
            text = "測試：手動切斷 Google 相簿網路"
            setOnClickListener {
                ShizukuNetHelper.setPhotosNetworkBlocked(this@MainActivity, true)
                updateStatus()
            }
        }

        val btnTestUnblock = Button(this).apply {
            text = "測試：手動恢復 Google 相簿網路"
            setOnClickListener {
                ShizukuNetHelper.setPhotosNetworkBlocked(this@MainActivity, false)
                updateStatus()
            }
        }

        layout.addView(statusText)
        layout.addView(btnShizuku)
        layout.addView(btnStorage)
        layout.addView(btnAccessibility)
        layout.addView(btnTestBlock)
        layout.addView(btnTestUnblock)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val shizukuReady = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        statusText.text = """
            【PhotoSyncGuard 狀態面板】
            • Shizuku 權限：${if (shizukuReady) "✅ 已授權" else "❌ 未授權"}
            • Google 相簿網路：${if (ShizukuNetHelper.isBlocked) "🚫 封鎖中 (算圖保護)" else "🟢 正常聯網"}
            ⚠️ 提醒：請務必在系統設定中開啟本 App 的「自啟動」與「允許背景高耗電」，避免拍到一半被系統殺後台。
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
