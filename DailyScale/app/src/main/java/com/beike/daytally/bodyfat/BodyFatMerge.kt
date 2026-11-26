package com.beike.daytally.bodyfat

/**
 * 体脂记录的纯合并规则：服务端快照是权威来源之一，但不能把本机刚测、
 * 还没上传的记录直接吞掉；同一条 recordId 只有更新的 updatedAt 才覆盖。
 *
 * 这层不依赖 Android，专门用来审计“断网后恢复同步 / 多设备去重”的边界。
 */
object BodyFatMerge {
    @JvmStatic
    fun dedupe(records: List<BodyFatRecord>): List<BodyFatRecord> {
        val byId = LinkedHashMap<String, BodyFatRecord>()
        records.forEach { r ->
            val old = byId[r.recordId]
            if (old == null || r.updatedAt >= old.updatedAt) byId[r.recordId] = r
        }
        return byId.values.sortedWith(compareBy({ it.measuredAt }, { it.updatedAt }, { it.recordId }))
    }

    @JvmStatic
    fun merge(local: List<BodyFatRecord>, server: List<BodyFatRecord>): List<BodyFatRecord> {
        val byId = LinkedHashMap<String, BodyFatRecord>()
        local.forEach { byId[it.recordId] = it }
        server.forEach { remote ->
            val old = byId[remote.recordId]
            if (old == null || remote.updatedAt >= old.updatedAt) byId[remote.recordId] = remote
        }
        return dedupe(byId.values.toList())
    }

    /** 已经由服务端确认（同 id 且服务端版本不旧）的 outbox 才移除。 */
    @JvmStatic
    fun remainingOutbox(outbox: List<BodyFatRecord>, server: List<BodyFatRecord>): List<BodyFatRecord> {
        val serverById = server.associateBy { it.recordId }
        return outbox.filter { pending ->
            val confirmed = serverById[pending.recordId]
            confirmed == null || confirmed.updatedAt < pending.updatedAt
        }
    }
}
