package se.gymrepris.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat

/** One encoded picture. A key frame carries the parameter sets in front, so decoding can start at any of them. */
class Frame(val ts: Long, val key: Boolean, val data: ByteArray)

/** Encoded frames in time order. Indexes keep counting when old frames are dropped. */
abstract class Source {
    abstract val fixed: Boolean
    abstract fun first(): Int
    abstract fun end(): Int
    abstract fun get(i: Int): Frame?

    /** Last index whose time is at or before [ts]; first() - 1 when there is none. */
    @Synchronized
    fun findLE(ts: Long): Int {
        var lo = first()
        var hi = end() - 1
        var r = lo - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (get(m)!!.ts <= ts) { r = m; lo = m + 1 } else hi = m - 1
        }
        return r
    }

    @Synchronized
    fun keyAtOrBefore(i: Int): Int {
        var k = i
        val f = first()
        while (k > f && get(k)?.key != true) k--
        return k
    }
}

/** The newest stretch of video from the camera. */
class LiveBuffer : Source() {
    private val list = ArrayList<Frame>()
    private var base = 0
    override val fixed = false

    @Synchronized override fun first() = base
    @Synchronized override fun end() = base + list.size
    @Synchronized override fun get(i: Int): Frame? = list.getOrNull(i - base)
    @Synchronized fun firstTs(): Long? = list.firstOrNull()?.ts

    /** Old video is dropped up to a key frame, so the buffer always starts with one. */
    @Synchronized
    fun add(f: Frame, keepUs: Long) {
        if (list.isEmpty() && !f.key) return
        list.add(f)
        if (!f.key) return
        var n = 0
        for (i in 1 until list.size) {
            if (!list[i].key) continue
            if (f.ts - list[i].ts >= keepUs) n = i else break
        }
        if (n > 0) { list.subList(0, n).clear(); base += n }
    }

    @Synchronized
    fun clear() { base += list.size; list.clear() }

    /** The frames between two times, starting at the key frame before [from]. */
    @Synchronized
    fun cut(from: Long, to: Long): List<Frame>? {
        if (list.isEmpty()) return null
        val i = keyAtOrBefore(maxOf(findLE(from), base))
        val e = findLE(to)
        if (get(i)?.key != true || e - i < 5) return null
        return ArrayList(list.subList(i - base, e - base + 1))
    }
}

class ClipSource(private val frames: List<Frame>) : Source() {
    override val fixed = true
    override fun first() = 0
    override fun end() = frames.size
    override fun get(i: Int): Frame? = frames.getOrNull(i)
}

class Clip(
    val frames: List<Frame>,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val format: MediaFormat,
    val at: Long,
    val thumb: Bitmap?,
) {
    val t0 = frames.first().ts
    val dur = frames.last().ts - t0
    val source = ClipSource(frames)
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("gymrepris", Context.MODE_PRIVATE)
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun set(k: String, v: Int) = p.edit().putInt(k, v).apply()

    var delay: Int get() = int("delay", 8); set(v) = set("delay", v)
    var res: Int get() = int("res", 1080); set(v) = set("res", v)
    var fps: Int get() = int("fps", 60); set(v) = set("fps", v)
    /** 0 lets the camera choose; otherwise the exposure is 1/shutter of a second. */
    var shutter: Int get() = int("shutter", 0); set(v) = set("shutter", v)
    var auto: Int get() = int("auto", 1); set(v) = set("auto", v)
    var sens: Int get() = int("sens", 5); set(v) = set("sens", v)
    var idle: Int get() = int("idle", 2); set(v) = set("idle", v)
    /** Playback speed in the clip viewer, in eighths. */
    var speed: Int get() = int("speed", 4); set(v) = set("speed", v)

    val preUs = 2_000_000L
    val postUs = 1_500_000L
    val manualUs = 10_000_000L
}
