package com.beike.daytally.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.beike.daytally.BuildConfig
import com.beike.daytally.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 局域网同步总入口。
 *
 * 触发：App 打开、Wi-Fi 重新连上、每次本地改动后（1.8 秒合并成一次请求）。
 * 语义：先把本地动作推给服务端，服务端按动作时间/事件 id 合并，再把权威快照回写本机。
 * 两台手机同时加组也不会互相覆盖：A 加 3 组、B 加 1 组，服务端算 4 组。
 */
object SyncManager {

    const val ACTION_SYNCED = "com.beike.daytally.SYNCED"
    private const val TAG = "DayTallySync"
    private const val MIN_GAP_MS = 1800L

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "daytally-sync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private var running = false
    private var scheduled = false
    private var pendingAgain = false
    private var lastStart = 0L

    fun request(c: Context, reason: String) {
        if (!SyncConfig.enabled) return
        val app = c.applicationContext
        if (!SyncNetwork.isOnWifi(app)) return

        synchronized(lock) {
            if (running || scheduled) {
                pendingAgain = true
                return
            }
            val now = SystemClock.elapsedRealtime()
            val wait = (MIN_GAP_MS - (now - lastStart)).coerceAtLeast(0L)
            if (wait == 0L) {
                start(app, reason)
            } else {
                scheduled = true
                main.postDelayed({
                    synchronized(lock) {
                        scheduled = false
                        if (running) pendingAgain = true else start(app, reason)
                    }
                }, wait)
            }
        }
    }

    private fun start(app: Context, reason: String) {
        running = true
        lastStart = SystemClock.elapsedRealtime()
        io.execute { doSync(app, reason) }
    }

    private fun finish(app: Context, ok: Boolean, reason: String) {
        Log.i(TAG, "sync $reason ${if (ok) "ok" else "failed"}")
        synchronized(lock) {
            running = false
            if (pendingAgain) {
                pendingAgain = false
                request(app, "followup")
            }
        }
    }

    private fun doSync(ctx: Context, reason: String) {
        try {
            val repo = Repo.get(ctx).also { it.ensureToday() }
            val clientId = SyncStore.clientId(ctx)
            val sentIds = ArrayList<String>()
            val body: JSONObject = synchronized(repo) {
                val pending = SyncStore.pending(ctx)
                for (i in 0 until pending.length()) {
                    pending.optJSONObject(i)?.optString("id")
                        ?.takeIf { it.isNotBlank() }?.let { sentIds.add(it) }
                }
                JSONObject()
                    .put("clientId", clientId)
                    .put("deviceName", Build.MODEL ?: "android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("reason", reason)
                    .put("seed", true)
                    .put("snapshot", SyncStore.snapshot(ctx, repo))
                    .put("events", pending)
            }

            val resp = post(body)
            val seeded = resp.optBoolean("seeded", false)
            val acked = if (seeded) {
                sentIds
            } else {
                val list = jsonList(resp.optJSONArray("acked"))
                if (list.isEmpty()) sentIds else list
            }
            val metrics = resp.optJSONObject("metrics")

            main.post {
                runCatching {
                    if (metrics != null) repo.applyServerSnapshot(metrics)
                    SyncStore.ack(ctx, acked)
                    SyncStore.setInitialized(ctx, true)
                    SyncStore.setLastSyncAt(ctx, System.currentTimeMillis())
                    ctx.sendBroadcast(Intent(ACTION_SYNCED).setPackage(ctx.packageName))
                }.onFailure { Log.w(TAG, "apply snapshot failed", it) }
                finish(ctx, true, reason)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}", e)
            finish(ctx, false, reason)
        }
    }

    private fun post(body: JSONObject): JSONObject {
        val url = URL(SyncConfig.baseUrl + "/api/v1/sync")
        var conn: HttpURLConnection? = null
        try {
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 20000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + SyncConfig.token)
        }
        conn = c
        c.outputStream.use { out ->
            out.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code $text")
        return JSONObject(text)
        } finally {
            conn?.disconnect()
        }
    }

    private fun jsonList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }
}
