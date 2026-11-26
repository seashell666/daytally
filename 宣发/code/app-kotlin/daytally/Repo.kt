package com.beike.daytally

import android.content.Context
import com.beike.daytally.model.Metric
import com.beike.daytally.sync.SyncConfig
import com.beike.daytally.sync.SyncStore
import com.beike.daytally.util.C
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 全局数据仓库：单例，负责项目数据、本地 JSON 持久化、所有统计与配色计算。
 * 日期按真实日历滚动，0 点之后当天自动换成新日期。
 */
class Repo private constructor(private val ctx: Context) {

    companion object {
        const val N = 30
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) {
            inst ?: Repo(ctx.applicationContext).also { inst = it }
        }
        private const val FILE = "daytally.json"
    }

    val order = listOf("height", "push", "horse", "pullup", "custom")
    val metrics = LinkedHashMap<String, Metric>()
    var days: List<LocalDate> = (N - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
        private set
    private var builtFor: LocalDate = LocalDate.now()
    val todayIdx = N - 1
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    init { load() }

    /**
     * 跨天后重建 30 天窗口。
     * 组件进程会长期驻留，若不重建，过了 0 点 `days` 还是昨天的，桌面日期就卡住不动。
     */
    @Synchronized
    fun ensureToday() {
        val now = LocalDate.now()
        if (now == builtFor) return
        builtFor = now
        days = (N - 1 downTo 0).map { now.minusDays(it.toLong()) }
    }

    fun dateStr(d: LocalDate) = d.format(fmt)
    fun todayStr() = dateStr(days[todayIdx])
    fun metric(key: String) = metrics[key] ?: error("unknown metric $key")

    // ---------- 默认项目 ----------
    private fun defaults(): List<Metric> {
        val empty = mutableMapOf<String, Double>()
        return listOf(
            Metric("height", "身高", "厘米", 1, 0, 0, 0, empty),
            Metric("push", "俯卧撑", "个", 0, 12, 10000, 32, mutableMapOf()),
            Metric("horse", "蹲马步", "分钟", 0, 11, 10000, 32, mutableMapOf()),
            Metric("pullup", "引体向上", "个", 0, 12, 10000, 32, mutableMapOf()),
            Metric("custom", "自定义", "个", 0, 10, 10000, 32, mutableMapOf())
        )
    }

    // 演示种子数据（与原型同种子同区间，验收可见完整曲线；正式发布可清空）
    private fun seed(): Map<String, List<Double?>> {
        val rnd = Random(20261116)
        fun ri(a: Int, b: Int) = rnd.nextInt(a, b + 1).toDouble()
        val push = List(N) { ri(2, 8) }
        val horse = List(N) { ri(1, 4) }
        val pullup = List(N) { ri(1, 5) }
        val custom = List(N) { ri(1, 6) }
        val height = mutableListOf<Double?>()
        var hh = 173.4
        for (i in 0 until N) {
            if (i == N - 1) { height.add(null); break }
            hh = Math.round((hh + (rnd.nextDouble() - 0.5) * 0.5) * 10) / 10.0
            hh = hh.coerceIn(172.0, 174.5)
            height.add(hh)
        }
        return mapOf("push" to push, "horse" to horse, "pullup" to pullup,
            "custom" to custom, "height" to height)
    }

    // ---------- 持久化 ----------
    private fun load() {
        val file = ctx.filesDir.resolve(FILE)
        val defs = defaults()
        if (!file.exists()) {
            /* 配了同步后端时，新装 App 不再灌 30 天演示数据：先空着，首次同步从服务端拉。
               没配后端（纯本地版）保留原来的演示种子，方便看完整曲线。 */
            if (SyncConfig.enabled(ctx)) {
                defs.forEach { metrics[it.key] = it }
                save()
                return
            }
            val seed = seed()
            defs.forEach { m ->
                seed[m.key]?.forEachIndexed { i, v ->
                    if (v != null) m.data[dateStr(days[i])] = v
                }
            }
            defs.forEach { metrics[it.key] = it }
            save()
            return
        }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val mj = root.optJSONObject("metrics") ?: JSONObject()
        defs.forEach { d ->
            val j = mj.optJSONObject(d.key)
            if (j == null) { metrics[d.key] = d; return@forEach }
            val data = mutableMapOf<String, Double>()
            j.optJSONObject("data")?.let { dj ->
                dj.keys().forEach { k -> data[k] = dj.getDouble(k) }
            }
            metrics[d.key] = d.copy(
                name = j.optString("name", d.name),
                unit = j.optString("unit", d.unit),
                per = j.optInt("per", d.per),
                goal = j.optInt("goal", d.goal),
                maxGroups = j.optInt("maxGroups", d.maxGroups),
                data = data
            )
        }
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        val mj = JSONObject()
        metrics.values.forEach { m ->
            val j = JSONObject()
            j.put("name", m.name); j.put("unit", m.unit); j.put("per", m.per)
            j.put("goal", m.goal); j.put("maxGroups", m.maxGroups)
            val dj = JSONObject()
            m.data.forEach { (k, v) -> dj.put(k, v) }
            j.put("data", dj)
            mj.put(m.key, j)
        }
        root.put("metrics", mj)
        ctx.filesDir.resolve(FILE).writeText(root.toString(), Charsets.UTF_8)

        /* 落盘成功就顺手把桌面组件刷一遍：App 里改了数字，桌面不能还停在旧的 */
        runCatching { com.beike.daytally.widget.WidgetRefresh.refreshAll(ctx) }
    }

    // ---------- 取值与统计 ----------
    fun rawVal(m: Metric, i: Int): Double? = m.data[dateStr(days[i])]

    fun valOf(m: Metric, i: Int): Double? {
        val g = rawVal(m, i) ?: return null
        return if (m.per > 0) g * m.per else g
    }

    fun prevIndex(m: Metric, i: Int): Int {
        for (k in i - 1 downTo 0) if (rawVal(m, k) != null) return k
        return -1
    }

    fun cumTotal(m: Metric): Double {
        var s = 0.0
        for (i in 0 until N) valOf(m, i)?.let { s += it }
        return s
    }

    fun isDone(m: Metric) = m.per > 0 && cumTotal(m) >= m.goal

    fun todayRaw(m: Metric) = m.data[todayStr()] ?: 0.0

    /**
     * 固定「周二 → 周一」周期：条带第 k 格（0..6）。
     * 返回该格日期、对应数据索引、以及是否在窗口外（今天之后 / 30 天之前）。
     * k=0 恒为周二，k=6 恒为周一；到下一个周二自动重置。
     */
    fun stripSlot(k: Int): Triple<LocalDate, Int, Boolean> {
        val off = (days[todayIdx].dayOfWeek.value + 5) % 7   // 周二=0 … 周一=6
        val idx = todayIdx + (k - off)
        val date = days[todayIdx].plusDays((k - off).toLong())
        return Triple(date, idx, idx > todayIdx || idx < 0)
    }

    fun seriesColors(m: Metric): List<Int?> {
        val cs = arrayOfNulls<Int>(N)
        for (i in 0 until N) {
            val v = valOf(m, i)
            if (v == null) { cs[i] = null; continue }
            val pi = prevIndex(m, i)
            if (pi < 0) { cs[i] = C.INK_NONE; continue }
            val pv = valOf(m, pi)!!
            cs[i] = when {
                v > pv + 1e-9 -> C.INK_UP
                v < pv - 1e-9 -> C.INK_DN
                else -> cs[pi] ?: C.INK_NONE
            }
        }
        return cs.toList()
    }

    fun fmt(v: Double, dec: Int): String =
        if (dec == 0) v.toInt().toString() else String.format("%.1f", v)

    // ---------- 写操作 ----------
    @Synchronized
    fun addGroup(m: Metric) {
        if (isDone(m)) return
        if (todayRaw(m) >= 99) return
        val day = todayStr()
        /* 先记动作再改本地值：即使随后落盘失败，服务端也还能按动作恢复；
           动作带 id，重复上传不会多算一组。 */
        SyncStore.recordCounter(ctx, m.key, day, 1)
        m.data[day] = todayRaw(m) + 1
        save()
    }

    @Synchronized
    fun removeGroup(m: Metric) {
        if (todayRaw(m) <= 0) return
        val day = todayStr()
        SyncStore.recordCounter(ctx, m.key, day, -1)
        m.data[day] = todayRaw(m) - 1
        save()
    }

    /** 身高是“设置值”不是“加一组”，同步时按动作时间取最新，不和计数事件相加 */
    @Synchronized
    fun setHeight(value: Double) {
        val day = todayStr()
        SyncStore.recordSet(ctx, "height", day, value)
        metric("height").data[day] = value
        save()
    }

    /** 项目名称/单位/每组/目标/纵轴上限改完后记一条配置动作 */
    fun noteConfigChanged(m: Metric) {
        SyncStore.recordConfig(ctx, m)
    }

    @Synchronized
    fun resetProject(m: Metric) {
        SyncStore.recordReset(ctx, m.key)
        m.data.clear()
        save()
    }

    /** 服务端权威快照回写：配置和逐日数据整段替换，不再二次触发事件 */
    @Synchronized
    fun applyServerSnapshot(metricsJson: JSONObject) {
        order.forEach { key ->
            val j = metricsJson.optJSONObject(key) ?: return@forEach
            val m = metrics[key] ?: return@forEach
            m.name = j.optString("name", m.name).ifBlank { m.name }
            m.unit = j.optString("unit", m.unit).ifBlank { m.unit }
            m.per = j.optInt("per", m.per)
            m.goal = j.optInt("goal", m.goal)
            m.maxGroups = j.optInt("maxGroups", m.maxGroups)
            val data = mutableMapOf<String, Double>()
            j.optJSONObject("data")?.let { dj ->
                dj.keys().forEach { d ->
                    runCatching { data[d] = dj.getDouble(d) }
                }
            }
            m.data.clear()
            m.data.putAll(data)
        }
        save()
    }
}
