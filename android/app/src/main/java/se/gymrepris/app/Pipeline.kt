package se.gymrepris.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/** Camera to encoder to memory, with movement detection on a small side stream. */
class Pipeline(private val act: Activity, private val s: Settings, private val ui: Listener) {
    interface Listener {
        fun onReady()
        fun onClip(c: Clip)
        fun onError(msg: String)
    }

    val live = LiveBuffer()
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var rotation = 0; private set
    @Volatile var info = ""; private set
    @Volatile var score = 0f; private set
    @Volatile var recording = false; private set
    @Volatile var lastMotionMs = SystemClock.elapsedRealtime(); private set
    @Volatile private var offsetUs: Long? = null
    @Volatile private var format: MediaFormat? = null

    private val main = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("camera").apply { start() }
    private val encThread = HandlerThread("encoder").apply { start() }
    private val cam = Handler(camThread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null
    private var reader: ImageReader? = null
    private var closed = false

    private var config: ByteArray? = null
    private var lastPts = -1L
    private var ready = false
    private var fpsCount = 0
    private var fpsSince = 0L
    private var label = ""

    private var manual = false
    private var iso = 400
    private var isoRange = Range(100, 3200)
    private var isoSet = 0

    /** The present moment on the clock the frames are stamped with; null until the first frame has come. */
    fun nowTs(): Long? = offsetUs?.let { SystemClock.elapsedRealtimeNanos() / 1000 - it }

    fun start() = cam.post { try { open() } catch (e: Exception) { fail("Kameran kunde inte startas (${e.javaClass.simpleName}).", e) } }

    fun stop() {
        val done = CountDownLatch(1)
        cam.post {
            closed = true
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { encSurface?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        camThread.quitSafely()
        encThread.quitSafely()
    }

    private fun fail(msg: String, e: Exception? = null) {
        Log.w("Gymrepris", msg, e)
        main.post { ui.onError(msg) }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val mgr = act.getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
            ?: mgr.cameraIdList.firstOrNull() ?: return fail("Surfplattan har ingen kamera.")
        val ch = mgr.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("Kameran kan inte användas.")

        val sizes = map.getOutputSizes(MediaCodec::class.java)
        val size = sizes.filter { it.width * 9 == it.height * 16 }.minByOrNull { abs(it.height - s.res) } ?: sizes.first()
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fast = ranges.any { it.lower == 60 && it.upper == 60 } && map.getOutputMinFrameDuration(MediaCodec::class.java, size) <= 16_700_000
        // Samsung keeps 60 pictures a second out of the advertised list, but gives it when its own recording keys ask for it.
        val hidden = s.fps == 60 && !fast
        val fps = if (s.fps == 60) 60 else 30
        val range = ranges.firstOrNull { it.lower == fps && it.upper == fps } ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower } ?: Range(fps, fps)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
        val small = yuv.filter { it.width >= 320 && it.width * size.height == it.height * size.width }.minByOrNull { it.width }
            ?: yuv.filter { it.width >= 320 }.minByOrNull { it.width } ?: yuv.last()

        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val display = when (act.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        width = size.width; height = size.height
        rotation = (sensor - display + 360) % 360

        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manual = s.shutter > 0 && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        iso = 800.coerceIn(isoRange.lower, isoRange.upper)
        label = "${size.width}×${size.height}" + if (s.shutter > 0) (if (manual) " · slutartid 1/${s.shutter}" else " · slutartiden går inte att styra") else ""

        startEncoder(size, fps)
        reader = ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try { onSmallImage(img) } catch (e: Exception) { Log.w("Gymrepris", "motion", e) } finally { img.close() }
            }, cam)
        }

        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (closed) { d.close(); return }
                device = d
                val targets = listOf(encSurface!!, reader!!.surface)
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                targets.forEach(b::addTarget)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                if (manual) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / s.shutter)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    isoSet = iso
                }
                request = b
                if (hidden) {
                    vendor(b, "samsung.android.control.recordingMaxFps", fps)
                    vendor(b, "samsung.android.control.recordingMinFps", fps)
                }
                val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, targets.map { OutputConfiguration(it) }, { cam.post(it) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cs: CameraCaptureSession) {
                            if (closed) return
                            session = cs
                            try { cs.setRepeatingRequest(b.build(), null, cam) } catch (e: Exception) { fail("Kameran kunde inte startas.", e) }
                        }
                        override fun onConfigureFailed(cs: CameraCaptureSession) = fail("Kameran klarar inte den valda inställningen.")
                    })
                // The sensor mode is chosen when the session is set up, so the frame rate has to be known then.
                sc.sessionParameters = b.build()
                try { d.createCaptureSession(sc) } catch (e: Exception) { fail("Kameran klarar inte den valda inställningen.", e) }
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); if (!closed) fail("Kameran används av en annan app.") }
            override fun onError(d: CameraDevice, error: Int) { d.close(); if (!closed) fail("Kamerafel ($error).") }
        }, cam)
    }

    private fun vendor(b: CaptureRequest.Builder, name: String, v: Int) {
        // Tablets of other makes do not know the key and refuse it.
        try { b.set(CaptureRequest.Key(name, Int::class.javaObjectType), v) } catch (_: Exception) {}
    }

    private fun startEncoder(size: Size, fps: Int) {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, (size.width * size.height * fps * 0.1).toInt().coerceIn(4_000_000, 24_000_000))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        f.setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = fail("Videon kunde inte kodas.", e)
            override fun onOutputFormatChanged(c: MediaCodec, out: MediaFormat) {
                // The parameter sets are kept so that every key frame can be made to stand on its own.
                val parts = listOf("csd-0", "csd-1").mapNotNull { k -> out.getByteBuffer(k)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } }
                config = parts.fold(ByteArray(0)) { a, b -> a + b }
                format = out
            }
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                try {
                    val codecConfig = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val pts = bi.presentationTimeUs
                    if (bi.size > 0 && !codecConfig && pts > lastPts) {
                        val buf = c.getOutputBuffer(index)!!
                        val bytes = ByteArray(bi.size)
                        buf.position(bi.offset); buf.get(bytes)
                        val key = bi.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        lastPts = pts
                        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
                        val o = nowUs - pts
                        if (offsetUs.let { it == null || o < it }) offsetUs = o
                        live.add(Frame(pts, key, if (key) (config ?: ByteArray(0)) + bytes else bytes), maxOf(s.delay + 5, 30) * 1_000_000L)
                        countFrame(nowUs)
                        if (!ready && format != null && live.end() > live.first()) { ready = true; main.post { ui.onReady() } }
                    }
                } finally {
                    c.releaseOutputBuffer(index, false)
                }
            }
        }, Handler(encThread.looper))
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
    }

    private fun countFrame(nowUs: Long) {
        fpsCount++
        if (fpsSince == 0L) fpsSince = nowUs
        if (nowUs - fpsSince >= 2_000_000) {
            info = "$label · ${Math.round(fpsCount * 1e6 / (nowUs - fpsSince))} bilder/s" + if (manual) " · ISO $iso" else ""
            fpsCount = 0; fpsSince = nowUs
        }
    }

    /* ---------- Movement and clips ---------- */
    private val gw = 96
    private val gh = 54
    private var prev: ByteArray? = null
    private var lastSample = 0L
    private var motStart = 0L
    private var motLast = 0L
    private var motCount = 0
    private var motPeak = 0f
    private var motThumb: Bitmap? = null

    /** The share of the picture that has to change for it to count as movement. */
    fun threshold() = (0.08 * (0.003 / 0.08).pow((s.sens - 1) / 9.0)).toFloat()

    private fun onSmallImage(img: Image) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastSample < 80) return
        lastSample = nowMs
        val plane = img.planes[0]
        val buf = plane.buffer
        val cur = ByteArray(gw * gh)
        var changed = 0
        var sum = 0L
        val old = prev
        for (y in 0 until gh) {
            val row = (y * img.height / gh) * plane.rowStride
            for (x in 0 until gw) {
                val l = buf.get(row + (x * img.width / gw) * plane.pixelStride)
                val i = y * gw + x
                cur[i] = l
                sum += l.toInt() and 0xff
                if (old != null && abs((l.toInt() and 0xff) - (old[i].toInt() and 0xff)) > 22) changed++
            }
        }
        prev = cur
        adjustExposure((sum / cur.size).toInt())
        if (old == null) return
        score = changed.toFloat() / cur.size
        val moving = score > threshold()
        if (moving) lastMotionMs = nowMs
        val ts = nowTs() ?: return
        val active = moving && s.auto == 1
        if (!recording) {
            if (active) { recording = true; motStart = ts; motLast = ts; motCount = 1; motPeak = score; motThumb = thumb(cur) }
            return
        }
        if (active) { motLast = ts; motCount++; if (score > motPeak) { motPeak = score; motThumb = thumb(cur) } }
        if (ts - motLast > s.postUs || ts - motStart > 20_000_000) {
            recording = false
            // A single flicker is not an exercise; it takes about a third of a second of movement.
            if (motCount >= 4) save(motStart - s.preUs, ts, motThumb)
        }
    }

    /** With a fixed exposure time the camera does not adjust the brightness itself, so the sensitivity is steered here. */
    private fun adjustExposure(mean: Int) {
        if (!manual) return
        val wanted = (iso * (1 + 0.4 * (110.0 / maxOf(mean, 8) - 1))).toInt().coerceIn(isoRange.lower, isoRange.upper)
        iso = wanted
        if (abs(iso - isoSet) * 20 < isoSet) return
        val b = request ?: return
        val cs = session ?: return
        isoSet = iso
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        try { cs.setRepeatingRequest(b.build(), null, cam) } catch (_: Exception) {}
    }

    private fun thumb(luma: ByteArray): Bitmap {
        val px = IntArray(luma.size)
        val flip = rotation == 180
        for (i in luma.indices) {
            val l = luma[if (flip) luma.size - 1 - i else i].toInt() and 0xff
            px[i] = (0xff shl 24) or (l shl 16) or (l shl 8) or l
        }
        return Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888)
    }

    private fun save(from: Long, to: Long, thumb: Bitmap?): Boolean {
        val fmt = format ?: return false
        val frames = live.cut(from, to) ?: return false
        val clip = Clip(frames, width, height, rotation, fmt, System.currentTimeMillis(), thumb)
        main.post { ui.onClip(clip) }
        return true
    }

    fun saveManual(): Boolean {
        val now = nowTs() ?: return false
        return save(now - s.manualUs, now, prev?.let(::thumb))
    }
}
