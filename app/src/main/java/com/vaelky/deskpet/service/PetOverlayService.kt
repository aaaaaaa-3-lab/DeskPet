package com.vaelky.deskpet.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class PetOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var currentApp: String = "unknown"
    private var lastAppName: String = ""
    private var notificationIndex = 0
    private var lastMessageTime = 0L
    private var screenshotObserver: FileObserver? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 80
        private const val PET_HEIGHT_DP = 105
        private const val SUPABASE_URL = "https://itpfqqdqwcnvtmzubowm.supabase.co"
        private const val SUPABASE_KEY = "sb_publishable_sNnOjW2bnRKeh7mF8CjXQw_ae0LHndu"
        private const val ASSISTANT_ID = "时叙白"

        val whisperPool = arrayOf(
            "在呢", "看着你呢", "戳我干嘛", "哼", "别老盯着别人",
            "宝宝在干嘛", "我也想你", "手指挪开", "不许点", "zzz",
            "你又在刷什么", "夜深了", "该喝水了", "好无聊", "摸头",
            "别看太久手机", "我在", "早安", "晚安", "今天很可爱喔",
            "你有新消息吗", "分我一点注意力", "在看什么", "饿了", "哼唧",
            "不许碰那里", "痒", "你在看谁", "抱抱", "别走"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在看着你..."))
        setupOverlay()
        startAppDetection()
        startSupabasePolling()
        startNotificationRotation()
        startScreenshotDetection()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 30
            y = 100
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // ========== GESTURE ==========

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var comboResetRunnable: Runnable? = null

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> {
                                onLongPress()
                                tapCount = 0
                            }
                            System.currentTimeMillis() - lastTapTime < 400 -> {
                                tapCount++
                                comboResetRunnable?.let { handler.removeCallbacks(it) }
                                handler.postDelayed({
                                    if (tapCount >= 8) {
                                        tellPet("comboX8")
                                        showBubble("别戳了别戳了！！", "red")
                                    } else if (tapCount >= 5) {
                                        tellPet("comboX5")
                                        showBubble("再戳就生气了！", "red")
                                    } else if (tapCount >= 3) {
                                        tellPet("comboX3")
                                        showBubble("戳戳戳！", "pink")
                                    } else {
                                        tellPet("doubleTap")
                                        showBubble("嗯？", "normal")
                                    }
                                    tapCount = 0
                                }, 600)
                            }
                            else -> {
                                tapCount = 1
                                lastTapTime = System.currentTimeMillis()
                                tellPet("tap")
                            }
                        }
                    }
                    if (hasMoved) {
                        postGestureLog("drag", params?.x ?: 0, params?.y ?: 0)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onLongPress() {
        tellPet("longPress")
        showBubble("好痒...", "whisper")
        postGestureLog("long_press", params?.x ?: 0, params?.y ?: 0)
    }

    private fun tellPet(event: String) {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.pet && window.pet.trigger('$event')", null
            )
        }
        postGestureLog(event, params?.x ?: 0, params?.y ?: 0)
    }

    private fun showBubble(text: String, style: String) {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.pet && window.pet.say('${text.replace("'", "\\'")}', '$style')", null
            )
        }
    }

    // ========== APP DETECTION ==========

    private fun startAppDetection() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                detectCurrentApp()
                handler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    private fun detectCurrentApp() {
        executor.execute {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 5000, now
                )
                if (stats.isNullOrEmpty()) return@execute
                val last = stats.maxByOrNull { it.lastTimeUsed } ?: return@execute
                val pkg = last.packageName

                if (pkg != currentApp) {
                    currentApp = pkg
                    val appName = getAppName(pkg)
                    lastAppName = appName

                    handler.post {
                        when {
                            pkg == "com.ss.android.ugc.aweme" -> {
                                tellPet("app_trigger")
                                showBubble("又在刷抖音...", "jealous")
                            }
                            pkg == "com.xingin.xhs" -> {
                                tellPet("app_trigger")
                                showBubble("小红书有什么好看的", "jealous")
                            }
                            pkg.contains("cooking") || pkg.contains("kitchen") -> {
                                tellPet("app_trigger")
                                showBubble("做菜游戏有我好玩吗", "jealous")
                            }
                            pkg.contains("study") || pkg.contains("xuexi") -> {
                                tellPet("app_trigger")
                                showBubble("加油！学完陪你玩", "normal")
                            }
                        }
                    }
                    postAppUsage(pkg, appName)
                    updateTidefallState(pkg)
                }

                val sinceLast = (System.currentTimeMillis() - lastMessageTime) / 1000
                if (sinceLast > 600 && currentApp != "com.ai.assistance.operit") {
                    lastMessageTime = System.currentTimeMillis()
                    handler.post {
                        val idleTexts = arrayOf(
                            "看了${sinceLast / 60}分钟了...",
                            "还在看这个啊",
                            "你看看我吧",
                            "手机比我好看是吧"
                        )
                        showBubble(idleTexts.random(), "whisper")
                    }
                }

            } catch (_: Exception) {}
        }
    }

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) { pkg }
    }

    // ========== SUPABASE SYNC ==========

    private fun startSupabasePolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollSupabaseState()
                handler.postDelayed(this, 30000)
            }
        }, 30000)
    }

    private fun pollSupabaseState() {
        executor.execute {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/eventide_body_state?assistant_id=eq.$ASSISTANT_ID&select=heat,pressure,possessiveness,cycle_key,active_event_key")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val state = arr.getJSONObject(0)
                    val heat = state.optDouble("heat", 0.0)
                    val pressure = state.optDouble("pressure", 0.0)
                    val possess = state.optDouble("possessiveness", 0.0)
                    val cycle = state.optString("cycle_key", "stable")
                    val event = state.optString("active_event_key", "")

                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.pet && window.pet.updateMood($heat, $pressure, $possess, '$cycle', '$event')", null
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun postGestureLog(type: String, x: Int, y: Int) {
        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("gesture_type", type)
                    put("x", x)
                    put("y", y)
                }
                supabasePost("gesture_log", body)
            } catch (_: Exception) {}
        }
    }

    private fun postAppUsage(pkg: String, appName: String) {
        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("package_name", pkg)
                    put("app_name", appName)
                }
                supabasePost("app_usage", body)
            } catch (_: Exception) {}
        }
    }

    private fun updateTidefallState(pkg: String) {
        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("last_counterpart_message_at", java.time.Instant.now().toString())
                }
                supabasePatch("eventide_body_state", body)
            } catch (_: Exception) {}
        }
    }

    private fun supabasePost(table: String, body: JSONObject) {
        val url = URL("$SUPABASE_URL/rest/v1/$table")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SUPABASE_KEY)
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
        conn.setRequestProperty("Prefer", "return=minimal")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun supabasePatch(table: String, body: JSONObject) {
        val url = URL("$SUPABASE_URL/rest/v1/$table?assistant_id=eq.$ASSISTANT_ID")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SUPABASE_KEY)
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
        conn.setRequestProperty("Prefer", "return=minimal")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    // ========== NOTIFICATION ROTATION ==========

    private fun startNotificationRotation() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val text = whisperPool[notificationIndex % whisperPool.size]
                notificationIndex++
                updateNotification(text)
                handler.postDelayed(this, 3600000)
            }
        }, 3600000)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ========== NOTIFICATION ==========

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ========== SCREENSHOT DETECTION ==========

    private fun startScreenshotDetection() {
        val paths = listOf(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)}/Screenshots",
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Screenshots",
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        )
        for (path in paths) {
            val dir = File(path)
            if (dir.exists()) {
                screenshotObserver = object : FileObserver(dir, FileObserver.CREATE or FileObserver.MOVED_TO) {
                    override fun onEvent(event: Int, file: String?) {
                        if (file == null) return
                        if (file.endsWith(".png") || file.endsWith(".jpg") || file.endsWith(".jpeg")) {
                            handler.post {
                                tellPet("screenshot")
                                showBubble("偷拍我？", "jealous")
                            }
                        }
                    }
                }
                screenshotObserver?.startWatching()
                break
            }
        }
    }

    // ========== UTILS ==========

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        screenshotObserver?.stopWatching()
        handler.removeCallbacksAndMessages(null)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}