package se.gymrepris.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
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

    /** Half seconds kept before the movement began and after it ended. */
    var pre: Int get() = int("pre", 4); set(v) = set("pre", v)
    var post: Int get() = int("post", 3); set(v) = set("post", v)
    /** Seconds the button Spara klipp keeps. */
    var manual: Int get() = int("manual", 10); set(v) = set("manual", v)

    /** The camera's id; empty for the one on the back. */
    var cam: String get() = p.getString("cam", "")!!; set(v) = p.edit().putString("cam", v).apply()
    /** Whether the picture as it is right now is shown in the corner. */
    var pip: Int get() = int("pip", 1); set(v) = set("pip", v)

    /** The part of the shown picture that is watched for movement, in fractions of it; null for all of it. */
    var zone: RectF?
        get() {
            val w = p.getFloat("zw", 0f)
            if (w <= 0f) return null
            val x = p.getFloat("zx", 0f); val y = p.getFloat("zy", 0f)
            return RectF(x, y, x + w, y + p.getFloat("zh", 0f))
        }
        set(v) = p.edit().putFloat("zx", v?.left ?: 0f).putFloat("zy", v?.top ?: 0f).putFloat("zw", v?.width() ?: 0f).putFloat("zh", v?.height() ?: 0f).apply()

    val preUs get() = pre * 500_000L
    val postUs get() = post * 500_000L
    val manualUs get() = manual * 1_000_000L
}
