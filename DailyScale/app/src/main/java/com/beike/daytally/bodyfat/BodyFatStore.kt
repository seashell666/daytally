package com.beike.daytally.bodyfat

import android.content.Context
import android.util.Log
import com.beike.daytally.sync.SyncConfig
import com.beike.daytally.sync.SyncManager
import com.beike.daytally.sync.SyncStore
import com.beike.daytally.widget.WidgetRefresh
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 体脂率独立仓库。
 *
 * 关键边界：这里不碰 Repo.metrics，也不写身高字段。体脂率允许多次测量，
 * 每条记录有独立 id；桌面组件、同步 outbox、服务端快照都只读写这套记录。
 */
object BodyFatStore {
    private const val TAG = "DayTallyBodyFat"
    private const val FILE = "body_fat_records.json"
    private const val OUTBOX = "body_fat_outbox.json"
    private const val PENDING = "body_fat_pending.json"
    private const val PREFS = "ds_body_fat"
    private const val K_CLEAR_AT = "clear_at"

    data class StripSlot(val date: LocalDate, val record: BodyFatRecord?)

    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(c: Context, name: String): File = c.filesDir.resolve(name)

    private fun readArray(c: Context, name: String): JSONArray {
        val f = file(c, name)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
    }

    private fun writeArray(c: Context, name: String, array: JSONArray) {
        val f = file(c, name)
        val tmp = File(f.parentFile, "$name.tmp")
        runCatching {
            tmp.writeText(array.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(array.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "write $name failed", it) }
    }

    private fun parseArray(array: JSONArray): List<BodyFatRecord> {
        val out = ArrayList<BodyFatRecord>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { raw ->
                BodyFatRecord.fromJson(raw)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun sort(records: List<BodyFatRecord>): List<BodyFatRecord> = BodyFatMerge.dedupe(records)

    private fun readRecords(c: Context): List<BodyFatRecord> = sort(parseArray(readArray(c, FILE)))

    private fun writeRecords(c: Context, records: List<BodyFatRecord>) {
        val array = JSONArray()
        sort(records).forEach { array.put(it.toJson()) }
        writeArray(c, FILE, array)
    }

    fun all(c: Context): List<BodyFatRecord> = synchronized(lock) { readRecords(c) }

    fun latest(c: Context): BodyFatRecord? = all(c).lastOrNull()

    fun latestForDay(c: Context, date: LocalDate): BodyFatRecord? =
        all(c).lastOrNull { BodyFatPolicy.localDate(it.measuredAt) == date }

    /** 桌面条带：固定周二 → 周一，和身高卡同一套日历口径。 */
    fun strip(c: Context): List<StripSlot> {
        val today = LocalDate.now()
        val off = (today.dayOfWeek.value + 5) % 7
        return (0 until 7).map { k ->
            val date = today.plusDays((k - off).toLong())
            StripSlot(date, latestForDay(c, date))
        }
    }

    fun add(
        c: Context,
        value: Double,
        source: String,
        measuredAt: Long = System.currentTimeMillis(),
        recordId: String = UUID.randomUUID().toString(),
    ): BodyFatRecord {
        require(BodyFatPolicy.isValidValue(value)) { "体脂率必须在 1.0 ~ 75.0 之间" }
        val record = BodyFatRecord.new(
            value = value,
            source = source,
            deviceId = SyncStore.clientId(c),
            measuredAt = measuredAt,
            recordId = recordId,
        )
        synchronized(lock) {
            writeRecords(c, readRecords(c) + record)
            val out = parseArray(readArray(c, OUTBOX))
            if (out.none { it.recordId == record.recordId }) {
                val array = JSONArray()
                out.forEach { array.put(it.toJson()) }
                array.put(record.toJson())
                writeArray(c, OUTBOX, array)
            }
        }
        WidgetRefresh.refreshBodyFat(c)
        if (SyncConfig.enabled(c)) SyncManager.request(c, "body_fat_add", urgent = true)
        return record
    }

    fun outbox(c: Context): List<BodyFatRecord> = synchronized(lock) { parseArray(readArray(c, OUTBOX)) }

    fun outboxJson(c: Context): JSONArray {
        val array = JSONArray()
        outbox(c).forEach { array.put(it.toJson()) }
        return array
    }

    fun snapshotJson(c: Context): JSONArray {
        val array = JSONArray()
        all(c).forEach { array.put(it.toJson()) }
        return array
    }

    /**
     * 服务端快照回写：只合并没有更新的同 id 记录，不删除本地尚未上传的记录。
     * clearAt 是服务端“体脂记录被物理清空”的标记；比本机记录更新时先清本地。
     */
    fun applyServer(c: Context, remote: List<BodyFatRecord>, clearAt: Long = 0L) {
        synchronized(lock) {
            val previousClear = prefs(c).getLong(K_CLEAR_AT, 0L)
            if (clearAt > previousClear) {
                writeRecords(c, emptyList())
                writeArray(c, OUTBOX, JSONArray())
                file(c, PENDING).delete()
                prefs(c).edit().putLong(K_CLEAR_AT, clearAt).apply()
            }
            val local = readRecords(c)
            val merged = BodyFatMerge.merge(local, remote)
            if (merged != local) writeRecords(c, merged)
            ackFromServerLocked(c, remote)
        }
        WidgetRefresh.refreshBodyFat(c)
    }

    fun ackFromServer(c: Context, remote: List<BodyFatRecord>) {
        synchronized(lock) { ackFromServerLocked(c, remote) }
    }

    private fun ackFromServerLocked(c: Context, remote: List<BodyFatRecord>) {
        val out = BodyFatMerge.remainingOutbox(parseArray(readArray(c, OUTBOX)), remote)
        val array = JSONArray()
        out.forEach { array.put(it.toJson()) }
        writeArray(c, OUTBOX, array)
    }

    /** 适配层拿到 OKOK 结果后先放这里，不直接落正式记录；等用户在日刻确认。 */
    fun stageAdapterResult(c: Context, record: BodyFatRecord) {
        synchronized(lock) {
            file(c, PENDING).writeText(record.toJson().toString(), Charsets.UTF_8)
        }
    }

    fun takePending(c: Context): BodyFatRecord? = synchronized(lock) {
        val f = file(c, PENDING)
        if (!f.exists()) return null
        val record = runCatching {
            BodyFatRecord.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        }.getOrNull()
        f.delete()
        record
    }

    fun hasPending(c: Context): Boolean = synchronized(lock) { file(c, PENDING).exists() }

    /** 仅供测试/诊断用，正式界面不展示。 */
    fun count(c: Context): Int = all(c).size

    fun zone(): ZoneId = ZoneId.systemDefault()
}
