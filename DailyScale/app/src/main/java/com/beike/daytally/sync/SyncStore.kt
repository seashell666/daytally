package com.beike.daytally.sync

import android.content.Context
import com.beike.daytally.model.Metric
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 同步事件队列。
 *
 * 计数类项目不能只同步“今天的总数”——两台手机各自加了几组，如果只拿最新总数覆盖，
 * 另一台的增量就丢了。这里把每次加/减/改都记成一条带时间的动作，服务端按动作求和；
 * 事件 id 保证同一条动作重复上传也只算一次。
 */
object SyncStore {

    private const val PREFS = "ds_sync"
    private const val K_CLIENT = "client_id"
    private const val K_SEQ = "seq"
    private const val K_INIT = "initialized"
    private const val K_LAST = "last_sync"
    private const val K_OK = "last_ok"
    private const val K_ERR = "last_error"
    private const val FILE = "sync_events.json"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class LastResult(val ok: Boolean?, val error: String?)

    fun lastResult(c: Context): LastResult {
        val p = prefs(c)
        val ok = if (p.contains(K_OK)) p.getBoolean(K_OK, false) else {
            /* 兼容 1.2.10 只有“上次成功时间”的旧记录 */
            if (p.getLong(K_LAST, 0L) > 0L) true else null
        }
        return LastResult(ok, p.getString(K_ERR, null))
    }

    fun setLastResult(c: Context, ok: Boolean, error: String?) {
        prefs(c).edit()
            .putBoolean(K_OK, ok)
            .putString(K_ERR, error?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun clearLastResult(c: Context) {
        prefs(c).edit().remove(K_OK).remove(K_ERR).remove(K_LAST).apply()
    }

    /** 换了一个服务端：上次同步时间和结果属于旧地址，不能拿来显示“已连接”。 */
    fun resetForServerChange(c: Context) {
        prefs(c).edit()
            .putBoolean(K_INIT, false)
            .remove(K_LAST)
            .remove(K_OK)
            .remove(K_ERR)
            .apply()
    }

    fun clientId(c: Context): String {
        val p = prefs(c)
        val old = p.getString(K_CLIENT, null)
        if (!old.isNullOrBlank()) return old
        val id = UUID.randomUUID().toString()
        p.edit().putString(K_CLIENT, id).apply()
        return id
    }

    fun isInitialized(c: Context): Boolean = prefs(c).getBoolean(K_INIT, false)

    fun setInitialized(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_INIT, v).apply()
    }

    fun lastSyncAt(c: Context): Long = prefs(c).getLong(K_LAST, 0L)

    fun setLastSyncAt(c: Context, at: Long) {
        prefs(c).edit().putLong(K_LAST, at).apply()
    }

    fun recordCounter(c: Context, metric: String, day: String, delta: Int) {
        if (!SyncConfig.enabled(c)) return
        val e = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("seq", nextSeq(c))
            .put("metric", metric)
            .put("day", day)
            .put("kind", if (delta >= 0) "add" else "remove")
            .put("delta", if (delta >= 0) 1 else -1)
            .put("occurredAt", System.currentTimeMillis())
        append(c, e)
        SyncManager.request(c, "counter")
    }

    fun recordSet(c: Context, metric: String, day: String, value: Double) {
        if (!SyncConfig.enabled(c)) return
        val e = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("seq", nextSeq(c))
            .put("metric", metric)
            .put("day", day)
            .put("kind", "set")
            .put("value", value)
            .put("occurredAt", System.currentTimeMillis())
        append(c, e)
        SyncManager.request(c, "set")
    }

    fun recordConfig(c: Context, m: Metric) {
        if (!SyncConfig.enabled(c)) return
        val cfg = JSONObject()
            .put("name", m.name)
            .put("unit", m.unit)
            .put("per", m.per)
            .put("goal", m.goal)
            .put("maxGroups", m.maxGroups)
        val e = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("seq", nextSeq(c))
            .put("metric", m.key)
            .put("kind", "config")
            .put("config", cfg)
            .put("occurredAt", System.currentTimeMillis())
        append(c, e)
        SyncManager.request(c, "config")
    }

    fun recordReset(c: Context, metric: String) {
        if (!SyncConfig.enabled(c)) return
        val e = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("seq", nextSeq(c))
            .put("metric", metric)
            .put("kind", "reset")
            .put("occurredAt", System.currentTimeMillis())
        append(c, e)
        SyncManager.request(c, "reset")
    }

    fun pending(c: Context): JSONArray = readQueue(c)

    /** 生成整库快照：只用于“服务端还是空库”的第一次播种，平时不参与覆盖 */
    fun snapshot(repo: com.beike.daytally.Repo): JSONObject {
        val metrics = JSONObject()
        repo.order.forEach { key ->
            val m = repo.metric(key)
            val data = JSONObject()
            m.data.forEach { (day, value) -> data.put(day, value) }
            metrics.put(
                key,
                JSONObject()
                    .put("name", m.name)
                    .put("unit", m.unit)
                    .put("per", m.per)
                    .put("goal", m.goal)
                    .put("maxGroups", m.maxGroups)
                    .put("data", data)
            )
        }
        return JSONObject().put("metrics", metrics)
    }

    /** 把服务端已经收下的事件从队列删掉；只删这次实际发出去的 id，不误删请求期间新产生的 */
    fun ack(c: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val accept = ids.toHashSet()
        val old = readQueue(c)
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val e = old.optJSONObject(i) ?: continue
            if (e.optString("id") !in accept) out.put(e)
        }
        writeQueue(c, out)
    }

    private fun nextSeq(c: Context): Int {
        val p = prefs(c)
        val n = p.getInt(K_SEQ, 0) + 1
        p.edit().putInt(K_SEQ, n).apply()
        return n
    }

    @Synchronized
    private fun append(c: Context, e: JSONObject) {
        val q = readQueue(c)
        q.put(e)
        writeQueue(c, q)
    }

    @Synchronized
    private fun readQueue(c: Context): JSONArray {
        val f = c.filesDir.resolve(FILE)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
    }

    @Synchronized
    private fun writeQueue(c: Context, q: JSONArray) {
        val f = c.filesDir.resolve(FILE)
        val tmp = File(f.parentFile, "$FILE.tmp")
        runCatching {
            tmp.writeText(q.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(q.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { android.util.Log.w("DayTallySync", "write event queue failed", it) }
    }
}
