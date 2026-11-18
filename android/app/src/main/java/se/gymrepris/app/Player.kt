package se.gymrepris.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Shows, on a surface, the frame that belongs to the time [target] returns. The time may stand still, move at any
 * speed or jump; the player follows.
 */
class Player(
    private val surface: Surface,
    private val src: Source,
    private val width: Int,
    private val height: Int,
    private val rotation: Int,
    private val target: () -> Long,
) : Thread("player") {
    @Volatile private var running = true
    @Volatile var shownTs = -1L
        private set

    fun finish() {
        running = false
        try { join(1000) } catch (_: InterruptedException) {}
    }

    override fun run() {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_ROTATION, rotation)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            play(codec)
        } catch (e: Exception) {
            Log.w("Gymrepris", "player", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun play(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var feed = -1
        var fromTs = Long.MAX_VALUE
        var passed = -1L
        var catchTs = 0L
        var ended = false
        var started = false
        var held = -1
        var heldTs = 0L

        while (running) {
            val t = target()
            val want = src.findLE(t)
            val wantTs = if (want >= src.first()) src.get(want)?.ts else null
            if (wantTs == null) { sleep(5); continue }
            var busy = false

            // A wanted frame that the decoder has gone past, or that lies before the key frame it started at, needs a new start.
            if (feed < src.first() || feed > src.end() || passed > wantTs || fromTs > wantTs) {
                if (started) codec.flush()
                held = -1
                feed = src.keyAtOrBefore(want)
                val key = src.get(feed)
                if (key == null || !key.key) { feed = -1; sleep(5); continue }
                fromTs = key.ts
                passed = -1
                catchTs = wantTs
                ended = false
            } else {
                // A decoder that has fallen far behind continues from the newest key frame.
                val next = src.get(feed)
                if (next != null && feed <= want && wantTs - next.ts > 1_500_000) feed = maxOf(feed, src.keyAtOrBefore(want))
            }

            while (!ended && feed < src.end()) {
                val f = src.get(feed) ?: break
                if (f.ts > t + 100_000) break
                val i = codec.dequeueInputBuffer(0)
                if (i < 0) break
                codec.getInputBuffer(i)!!.apply { clear(); put(f.data) }
                codec.queueInputBuffer(i, 0, f.data.size, f.ts, 0)
                started = true
                feed++
                busy = true
            }
            // The last frames of a clip only come out once the decoder is told there are no more.
            if (src.fixed && !ended && feed == src.end()) {
                val i = codec.dequeueInputBuffer(0)
                if (i >= 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended = true }
            }

            if (held < 0) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o >= 0) {
                    if (info.size == 0) codec.releaseOutputBuffer(o, false) else { held = o; heldTs = info.presentationTimeUs }
                    busy = true
                }
            }
            if (held >= 0 && heldTs <= t) {
                // After a jump the frames between the key frame and the wanted frame are decoded but not shown.
                val show = heldTs >= catchTs || heldTs >= wantTs
                codec.releaseOutputBuffer(held, show)
                if (show) { shownTs = heldTs; catchTs = 0 }
                passed = heldTs
                held = -1
                busy = true
            }
            if (!busy) sleep(2)
        }
    }
}
