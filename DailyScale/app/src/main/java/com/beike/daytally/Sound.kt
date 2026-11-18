package com.beike.daytally

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sign

/** 音效与震动：加组 / 退组 / 保存 / 错误 四种反馈，对应原型手感 */
class Sound private constructor(ctx: Context) {

    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    companion object {
        @Volatile private var inst: Sound? = null
        fun get(ctx: Context): Sound = inst ?: synchronized(this) {
            inst ?: Sound(ctx.applicationContext).also { inst = it }
        }
    }

    private fun vibrate(ms: Int) {
        vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun beep(freq: Double, durMs: Int, vol: Double, type: String = "triangle") {
        thread {
            val sr = 44100
            val n = (sr * durMs / 1000.0).toInt().coerceAtLeast(1)
            val buf = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val s = when (type) {
                    "sine" -> Math.sin(2 * PI * freq * t)
                    "square" -> sign(Math.sin(2 * PI * freq * t))
                    else -> (Math.sin(2 * PI * freq * t) + 0.5 * Math.sin(4 * PI * freq * t)) / 1.5
                }
                buf[i] = (s * vol * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
            val at = AudioTrack(
                AudioTrack.MODE_STREAM, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STREAM
            )
            at.write(buf, 0, n)
            at.play()
            Thread.sleep(durMs.toLong() + 30)
            at.stop()
            at.release()
        }
    }

    fun tap() = vibrate(6)
    /** 「去记录」弹窗打开：轻震 + 一记短促提示音，今日页与桌面大卡共用同一手感 */
    fun sheetOpen() { vibrate(10); beep(784.0, 55, 0.09, "sine") }
    /** 底栏 tab 单击：复用「加一组」那下 12ms 手感，不出声 */
    fun tabTap() = vibrate(12)
    fun add() {
        vibrate(12); beep(988.0, 85, 0.15)
        thread { Thread.sleep(34); beep(1319.0, 70, 0.09) }
    }
    fun remove() { vibrate(20); beep(440.0, 90, 0.12, "sine") }
    fun save() {
        vibrate(25); beep(660.0, 100, 0.13)
        thread { Thread.sleep(90); beep(990.0, 120, 0.13) }
    }
    fun error() { vibrate(30); beep(220.0, 160, 0.14, "square") }
}
