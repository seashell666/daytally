package com.beike.daytally.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.beike.daytally.MainActivity
import com.beike.daytally.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 实时同步长连接。
 *
 * 只做一件事：保持一条 SSE（server-sent events）连接。服务器每次写入成功后
 * 推一个 change 事件，这里马上请求一次普通同步；同步成功后会刷新桌面组件，
 * 所以另一台手机不打开 App、进程没在后台，也能看到组件的数字变化。
 *
 * 低功耗取舍：不轮询、不每秒请求；空闲时只有 25 秒一次心跳。代价是同步配置
 * 存在时要保留一个前台服务通知。MIUI 还需要允许自启动/省电无限制，否则系统
 * 清掉后台后只能等下次打开 App、开机或重新连 Wi-Fi 时恢复。
 */
class SyncPushService : Service() {

    companion object {
        private const val TAG = "DayTallyPush"
        private const val CHANNEL = "daytally_realtime"
        private const val NOTIF_ID = 43
        private const val HEARTBEAT_TIMEOUT_MS = 0
        private const val RECONNECT_MIN_MS = 1200L
        private const val RECONNECT_MAX_MS = 30000L

        fun start(ctx: Context) {
            if (!SyncConfig.enabled(ctx)) return
            val intent = Intent(ctx, SyncPushService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            }.onFailure { Log.i(TAG, "start rejected: ${it.message}") }
        }

        /** 换服务器/换 Token 后必须换连接；不能继续听旧服务端的事件。 */
        fun restart(ctx: Context) {
            val app = ctx.applicationContext
            app.stopService(Intent(app, SyncPushService::class.java))
            if (!SyncConfig.enabled(app)) return
            Handler(Looper.getMainLooper()).postDelayed({ start(app) }, 250L)
        }
    }

    private var worker: Thread? = null
    @Volatile private var stopped = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SyncConfig.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification("正在连接…"))
        if (worker?.isAlive != true) {
            stopped = false
            worker = Thread({ loop() }, "daytally-push").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        connection?.disconnect()
        connection = null
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        var backoff = RECONNECT_MIN_MS
        while (!stopped) {
            val cfg = SyncConfig.endpoint(this)
            if (cfg == null || !cfg.enabled) break
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cfg.baseUrl + "/api/v1/stream").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = HEARTBEAT_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Authorization", "Bearer " + cfg.token)
                }
                connection = conn
                val code = conn.responseCode
                if (code != 200) throw IOException("HTTP $code")
                updateNotification("实时同步已连接")
                backoff = RECONNECT_MIN_MS

                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!stopped) {
                        val line = reader.readLine() ?: break
                        /* 只认 change；data 行只带版本号，不需要重复触发 */
                        if (line.startsWith("event: change")) {
                            SyncManager.request(this@SyncPushService, "push")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopped) Log.i(TAG, "stream closed: ${e.message}")
            } finally {
                connection = null
                conn?.disconnect()
            }

            if (!stopped) {
                updateNotification("等待连接…")
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            "日刻实时同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持一条低功耗长连接，让桌面组件在另一台手机操作后马上更新"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync_notify)
            .setContentTitle("日刻 · 实时同步")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
